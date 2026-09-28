package com.ddk.seckill;

import com.ddk.seckill.entity.AsyncReceipt;
import com.ddk.seckill.entity.OrderMessage;
import com.ddk.seckill.service.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/stage2.sql,classpath:db/stage3.sql","seckill.mq.queue=seckill.test.orders.v4","seckill.limits.namespace=seckill:test:stage4"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("stage4")
@EnabledIfEnvironmentVariable(named="SECKILL_PROTECTION_TEST",matches="true")
class ProtectedSeckillIntegrationTest {
    static final String PREFIX="seckill:test:stage4";
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired StockInitializer initializer;
    @Autowired ProtectedSeckillService service;
    @SpyBean AsyncSeckillService async;
    @SpyBean OrderMessagePublisher publisher;
    @Autowired TokenBucketLimiter limiter;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired ConnectionFactory connection;
    @Autowired MockMvc mvc;
    long product;
    Set<String> tickets=ConcurrentHashMap.newKeySet();

    @BeforeEach void setup(){
        clearLimits();
        var key=new GeneratedKeyHolder();
        jdbc.update(c->c.prepareStatement("INSERT INTO product(name,stock,price) VALUES ('stage4-test',10,6999.00)",java.sql.Statement.RETURN_GENERATED_KEYS),key);
        product=key.getKey().longValue();initializer.initialize(product);registry.start();
    }
    void clearLimits(){var keys=redis.keys(PREFIX+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    @AfterEach void cleanup(){
        registry.stop();reset(publisher,async);
        var admin=new RabbitAdmin(connection);admin.purgeQueue("seckill.test.orders.v4",false);admin.purgeQueue("seckill.test.orders.v4.dead",false);
        for(String id:tickets){redis.delete(AsyncRequestStore.key(id));redis.delete(ProtectedSeckillService.marker(id));}
        redis.delete(RedisStockService.keys(product));clearLimits();
        jdbc.update("DELETE FROM seckill_async_order WHERE product_id=?",product);
        jdbc.update("DELETE FROM seckill_order WHERE product_id=?",product);
        jdbc.update("DELETE FROM seckill_stock_baseline WHERE product_id=?",product);
        jdbc.update("DELETE FROM product WHERE id=?",product);
    }
    AsyncReceipt submit(long user,String key){tickets.add(ProtectedSeckillService.requestId(user,key));return service.submit(product,user,key);}
    int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,product);}
    void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("Timed out");Thread.sleep(30);}
    }
    void seed(String key,int tokens){redis.opsForHash().putAll(key,Map.of("tokens",Integer.toString(tokens),"time",Long.toString(System.currentTimeMillis()+60000)));}

    @Test void hundredIdenticalRequestsReserveAndPublishOnlyOnce()throws Exception{
        registry.stop();String key=UUID.randomUUID().toString();Set<String> ids=new HashSet<>();
        try(var pool=Executors.newFixedThreadPool(20)){
            var futures=new ArrayList<Future<AsyncReceipt>>();
            for(int n=0;n<100;n++)futures.add(pool.submit(()->submit(1001,key)));
            for(var future:futures){var r=future.get(30,TimeUnit.SECONDS);assertTrue(Set.of("QUEUED","PENDING").contains(r.status()));ids.add(r.requestId());}
        }
        assertEquals(1,ids.size());verify(publisher,times(1)).publish(any(OrderMessage.class));
        assertEquals(9,async.product(product).getStock());assertEquals(0,count());
        registry.start();await(()->count()==1);
        String id=ids.iterator().next();await(()->"SUCCESS".equals(async.result(id).status()));
        mvc.perform(post("/seckill/"+product).param("userId","1001").header("Idempotency-Key",key))
            .andExpect(status().isOk()).andExpect(jsonPath("requestId").value(id)).andExpect(jsonPath("status").value("SUCCESS"));
        assertEquals(1,count());assertEquals(-1L,redis.getExpire(ProtectedSeckillService.marker(id)));
    }
    @Test void keyCannotBeReusedForDifferentProduct(){
        String key=UUID.randomUUID().toString();submit(1001,key);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.submit(product+1,1001,key)).getStatusCode().value());
        assertEquals(9,async.product(product).getStock());
    }
    @Test void sameKeyIsScopedToUser()throws Exception{
        String key=UUID.randomUUID().toString();var a=submit(1,key);var b=submit(2,key);
        assertNotEquals(a.requestId(),b.requestId());await(()->count()==2);assertEquals(8,async.product(product).getStock());
    }
    @Test void replaySurvivesExpiredTicketAndMissingProductCache()throws Exception{
        String key=UUID.randomUUID().toString();var first=submit(1,key);
        await(()->"SUCCESS".equals(async.result(first.requestId()).status()));
        redis.delete(AsyncRequestStore.key(first.requestId()));redis.delete(RedisStockService.keys(product).get(1));
        var replay=submit(1,key);assertEquals("SUCCESS",replay.status());assertNotNull(replay.orderId());assertEquals(1,count());
        verify(publisher,times(1)).publish(any(OrderMessage.class));
    }
    @Test void publishTimeoutAndRetryDoNotRepublishOrRefund()throws Exception{
        doThrow(new TimeoutException("injected publish uncertainty")).when(publisher).publish(any(OrderMessage.class));
        String key=UUID.randomUUID().toString();var first=submit(1,key);var again=submit(1,key);
        assertEquals("UNKNOWN",first.status());assertEquals(first,again);assertEquals(9,async.product(product).getStock());assertEquals(0,count());
        verify(publisher,times(1)).publish(any(OrderMessage.class));
        redis.delete(AsyncRequestStore.key(first.requestId()));
        assertEquals("UNKNOWN",submit(1,key).status());verify(publisher,times(1)).publish(any(OrderMessage.class));
    }
    @Test void missingOrMalformedIdempotencyKeyIs400()throws Exception{
        mvc.perform(post("/seckill/"+product).param("userId","1")).andExpect(status().isBadRequest());
        mvc.perform(post("/seckill/"+product).param("userId","1").header("Idempotency-Key","1-1-1-1-1")).andExpect(status().isBadRequest());
        assertEquals(10,async.product(product).getStock());verify(publisher,never()).publish(any(OrderMessage.class));
    }
    @Test void userLimitReturns429AndRetryAfterWithoutReserving()throws Exception{
        seed(PREFIX+":write-user:1001",0);
        mvc.perform(post("/seckill/"+product).param("userId","1001").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After",org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
        assertEquals(10,async.product(product).getStock());assertEquals(0,count());verify(publisher,never()).publish(any(OrderMessage.class));
    }
    @Test void spoofedForwardedIpCannotBypassQueryLimit()throws Exception{
        String ip=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("127.0.0.1".getBytes(StandardCharsets.UTF_8)));
        seed(PREFIX+":read-ip:"+ip,0);
        for(String fake:List.of("1.2.3.4","5.6.7.8"))mvc.perform(get("/product/"+product).header("X-Forwarded-For",fake))
            .andExpect(status().isTooManyRequests());
        verify(async,never()).product(product);
    }
    @Test void globalWriteLimitProtectsAcrossUsers()throws Exception{
        seed(PREFIX+":write-global",0);
        for(int user=1;user<=3;user++)mvc.perform(post("/seckill/"+product).param("userId",Integer.toString(user))
            .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isTooManyRequests());
        assertEquals(10,async.product(product).getStock());verify(publisher,never()).publish(any(OrderMessage.class));
    }
    @Test void concurrentTokenConsumptionCannotExceedCapacity()throws Exception{
        String name=PREFIX+":atomic";seed(name,5);var bucket=new TokenBucketLimiter.Bucket(name,1,5);
        try(var pool=Executors.newFixedThreadPool(20)){
            var futures=new ArrayList<Future<Boolean>>();
            for(int n=0;n<100;n++)futures.add(pool.submit(()->{try{limiter.check(List.of(bucket));return true;}catch(RateLimitExceededException e){return false;}}));
            int accepted=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))accepted++;
            assertEquals(5,accepted);
        }
    }
    @Test void rejectionDoesNotSpendOtherBucketTokens(){
        String a=PREFIX+":first",b=PREFIX+":second";seed(a,3);seed(b,0);
        assertThrows(RateLimitExceededException.class,()->limiter.check(List.of(new TokenBucketLimiter.Bucket(a,1,3),new TokenBucketLimiter.Bucket(b,1,1))));
        assertEquals("3",redis.opsForHash().get(a,"tokens"));
    }
    @Test void bucketRefillsAndHasBoundedTtl()throws Exception{
        String name=PREFIX+":refill";var bucket=new TokenBucketLimiter.Bucket(name,1,1);
        limiter.check(List.of(bucket));assertThrows(RateLimitExceededException.class,()->limiter.check(List.of(bucket)));
        assertTrue(redis.getExpire(name)>0 && redis.getExpire(name)<=61);
        Thread.sleep(1100);assertDoesNotThrow(()->limiter.check(List.of(bucket)));
    }
    @Test void corruptLimiterStateFailsClosed()throws Exception{
        redis.opsForValue().set(PREFIX+":write-global","wrong-type");
        mvc.perform(post("/seckill/"+product).param("userId","1").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isServiceUnavailable());
        assertEquals(10,async.product(product).getStock());verify(publisher,never()).publish(any(OrderMessage.class));
    }
    @Test void uniqueRequestsStillCannotOversell()throws Exception{
        try(var pool=Executors.newFixedThreadPool(20)){
            var futures=new ArrayList<Future<Integer>>();
            for(int n=0;n<100;n++)futures.add(pool.submit(()->{try{return "QUEUED".equals(submit(1,UUID.randomUUID().toString()).status())?202:503;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            int accepted=0;for(var f:futures){int status=f.get(30,TimeUnit.SECONDS);if(status==202)accepted++;else assertEquals(409,status);}assertEquals(10,accepted);
        }
        await(()->count()==10);assertEquals(0,async.product(product).getStock());
    }
}
