package com.ddk.seckill;

import com.ddk.seckill.service.*;
import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/stage2.sql,classpath:db/stage3.sql,classpath:db/stage6.sql","seckill.mq.queue=seckill.test.orders.v6","seckill.limits.namespace=seckill:test:stage6:limits","seckill.auth.namespace=seckill:test:stage6:auth","logging.file.name=target/stage6-test.log"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("stage6")
@EnabledIfEnvironmentVariable(named="SECKILL_AUTH_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @org.springframework.boot.test.mock.mockito.SpyBean AuthService auth;
    @Autowired StockInitializer initializer;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired TokenBucketLimiter limiter;
    String a,b,username,password="Stage6_test_password_123";
    long userA,userB,product;
    Set<String> requests=new HashSet<>();
    final String prefix="seckill:test:stage6:";
    JsonNode credentials(String path,String name,String pass,int status)throws Exception {
        return json.readTree(mvc.perform(post(path).contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",name,"password",pass))))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    @BeforeAll void users()throws Exception {
        clearLimits();username="t6_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        userA=credentials("/auth/register",username,password,201).get("userId").asLong();
        a=credentials("/auth/login",username,password,200).get("accessToken").asText();
        userB=credentials("/auth/register",username+"b",password,201).get("userId").asLong();
        b=credentials("/auth/login",username+"b",password,200).get("accessToken").asText();
    }
    void clearLimits(){for(String pat:List.of(prefix+"limits*",prefix+"auth:limit:*")){var keys=redis.keys(pat);if(keys!=null&&!keys.isEmpty())redis.delete(keys);}}
    @BeforeEach void setup() {
        clearLimits();registry.start();
        var key=new GeneratedKeyHolder();
        jdbc.update(c->c.prepareStatement("INSERT INTO product(name,stock,price) VALUES ('stage6-test',10,6999.00)",java.sql.Statement.RETURN_GENERATED_KEYS),key);
        product=key.getKey().longValue();initializer.initialize(product);
    }
    String purchase(String token,String key)throws Exception {
        var result=mvc.perform(post("/seckill/"+product).header("Authorization","Bearer "+token).header("Idempotency-Key",key))
            .andExpect(status().isAccepted()).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).get("requestId").asText();requests.add(id);return id;
    }
    long awaitOrder(String id)throws Exception {
        for(int i=0;i<150;i++) {
            Long order=jdbc.queryForObject("SELECT MAX(order_id) FROM seckill_async_order WHERE request_id=?",Long.class,id);
            if(order!=null)return order;Thread.sleep(100);
        }
        throw new AssertionError("Order timeout");
    }
    @AfterEach void cleanup()throws Exception {
        org.mockito.Mockito.reset(auth);registry.start();for(String id:requests)awaitOrder(id);
        registry.stop();
        for(String id:requests){redis.delete(AsyncRequestStore.key(id));redis.delete(ProtectedSeckillService.marker(id));}
        requests.clear();redis.delete(RedisStockService.keys(product));
        jdbc.update("DELETE FROM seckill_async_order WHERE product_id=?",product);
        jdbc.update("DELETE FROM seckill_order WHERE product_id=?",product);
        jdbc.update("DELETE FROM seckill_stock_baseline WHERE product_id=?",product);
        jdbc.update("DELETE FROM product WHERE id=?",product);clearLimits();
    }
    @AfterAll void removeUsers(){jdbc.update("DELETE FROM seckill_user WHERE id IN (?,?)",userA,userB);var keys=redis.keys(prefix+"auth:session:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    @Test void anonymousPrivateEndpointsAre401()throws Exception {
        for(String url:List.of("/order/1","/seckill/result/"+UUID.randomUUID(),"/auth/me"))
            mvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer")).andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/seckill/"+product).param("userId","1001").header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        assertEquals("10",redis.opsForValue().get("product_stock_"+product));
    }
    @Test void productRemainsPublic()throws Exception {mvc.perform(get("/product/"+product)).andExpect(status().isOk()).andExpect(jsonPath("stock").value(10));}
    @Test void identityComesFromTokenAndPasswordIsHashed()throws Exception {
        mvc.perform(get("/auth/me").header("Authorization","Bearer "+a)).andExpect(status().isOk()).andExpect(jsonPath("userId").value(userA)).andExpect(header().string("Cache-Control","no-store"));
        String hash=jdbc.queryForObject("SELECT password_hash FROM seckill_user WHERE id=?",String.class,userA);
        assertTrue(hash.startsWith("{pbkdf2-sha256-600k}"));assertFalse(hash.contains(password));
        assertFalse(auth.sessionKey(a).contains(a));assertTrue(redis.getExpire(auth.sessionKey(a))>0);
    }
    @Test void cannotSupplyAnotherUserId()throws Exception {
        mvc.perform(post("/seckill/"+product).param("userId",Long.toString(userB)).header("Authorization","Bearer "+a).header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("USER_ID_MUST_NOT_BE_SUPPLIED"));
        assertEquals("10",redis.opsForValue().get("product_stock_"+product));
    }
    @Test void pendingAndCompletedResultsAreOwnerOnly()throws Exception {
        registry.stop();String id=purchase(a,UUID.randomUUID().toString());
        mvc.perform(get("/seckill/result/"+id).header("Authorization","Bearer "+a)).andExpect(status().isOk()).andExpect(jsonPath("status").value("PENDING"));
        mvc.perform(get("/seckill/result/"+id).header("Authorization","Bearer "+b)).andExpect(status().isNotFound());
        registry.start();long order=awaitOrder(id);
        mvc.perform(get("/seckill/result/"+id).header("Authorization","Bearer "+a)).andExpect(status().isOk()).andExpect(jsonPath("orderId").value(order));
        mvc.perform(get("/seckill/result/"+id).header("Authorization","Bearer "+b)).andExpect(status().isNotFound());
        mvc.perform(get("/order/"+order).header("Authorization","Bearer "+a)).andExpect(status().isOk()).andExpect(jsonPath("userId").value(userA));
        mvc.perform(get("/order/"+order).header("Authorization","Bearer "+b)).andExpect(status().isNotFound());
        redis.delete(AsyncRequestStore.key(id));
        mvc.perform(get("/seckill/result/"+id).header("Authorization","Bearer "+a)).andExpect(status().isOk()).andExpect(jsonPath("orderId").value(order));
        mvc.perform(get("/seckill/result/"+id).header("Authorization","Bearer "+b)).andExpect(status().isNotFound());
    }
    @Test void sameKeySameUserIsOneOrderAndDifferentUsersAreIndependent()throws Exception {
        String key=UUID.randomUUID().toString(),id=purchase(a,key);long order=awaitOrder(id);
        mvc.perform(post("/seckill/"+product).header("Authorization","Bearer "+a).header("Idempotency-Key",key)).andExpect(status().isOk()).andExpect(jsonPath("orderId").value(order));
        String other=purchase(b,key);assertNotEquals(id,other);awaitOrder(other);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,product));
        assertEquals("8",redis.opsForValue().get("product_stock_"+product));
    }
    @Test void logoutRevokesOnlyCurrentToken()throws Exception {
        String token=credentials("/auth/login",username,password,200).get("accessToken").asText();
        mvc.perform(post("/auth/logout").header("Authorization","Bearer "+token)).andExpect(status().isNoContent());
        mvc.perform(get("/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me").header("Authorization","Bearer "+a)).andExpect(status().isOk());
    }
    @Test void expiredAndForgedTokensAre401()throws Exception {
        String token=credentials("/auth/login",username,password,200).get("accessToken").asText();
        redis.expire(auth.sessionKey(token),Duration.ofMillis(1));Thread.sleep(20);
        for(String t:List.of(token,"x".repeat(43),"malformed"))mvc.perform(get("/auth/me").header("Authorization","Bearer "+t)).andExpect(status().isUnauthorized());
    }
    @Test void wrongPasswordAndUnknownAccountHaveSameResponse()throws Exception {
        JsonNode known=credentials("/auth/login",username,"Incorrect_password_123",401),unknown=credentials("/auth/login","missing_"+System.nanoTime(),password,401);
        assertEquals(known.get("code"),unknown.get("code"));assertEquals(known.get("message"),unknown.get("message"));
    }
    @Test void registrationNormalizesAndRejectsDuplicates()throws Exception {credentials("/auth/register",username.toUpperCase(Locale.ROOT),password,409);}
    @Test void invalidCredentialsAre400()throws Exception {credentials("/auth/register","bad","short",400);}
    @Test void loginIsRateLimitedBeforePasswordHashing()throws Exception {
        var bucket=new TokenBucketLimiter.Bucket(prefix+"auth:limit:ip:"+AuthService.digest("127.0.0.1"),1,5);
        for(int i=0;i<5;i++)limiter.check(List.of(bucket));
        mvc.perform(post("/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",password))))
            .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
    @Test void sessionStoreFailureFailsClosedWithoutReachingOrder()throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_REDIS_FAILURE"))
            .when(auth).authenticate("Bearer "+a);
        var result=mvc.perform(post("/seckill/"+product).header("Authorization","Bearer "+a).header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("DEPENDENCY_UNAVAILABLE")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("PRIVATE_REDIS_FAILURE"));
        assertEquals("10",redis.opsForValue().get("product_stock_"+product));
    }

}
