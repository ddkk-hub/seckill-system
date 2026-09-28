package com.ddk.seckill;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.entity.Order;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/stage2.sql,classpath:db/stage3.sql","seckill.mq.queue=seckill.test.orders.v3"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("stage3")
@EnabledIfEnvironmentVariable(named="SECKILL_MQ_TEST",matches="true")
class AsyncSeckillIntegrationTest {
 @Autowired JdbcTemplate jdbc;
 @Autowired StringRedisTemplate redis;
 @Autowired StockInitializer initializer;
 @Autowired AsyncSeckillService service;
 @SpyBean AsyncRequestStore requests;
 @Autowired AsyncOrderWriter writer;
 @SpyBean OrderMessagePublisher publisher;
 @SpyBean OrderMapper orders;
 @Autowired ObjectMapper json;
 @Autowired RabbitListenerEndpointRegistry registry;
 @Autowired ConnectionFactory connection;
 @Autowired MockMvc mvc;
 long id;
 Set<String> tickets=ConcurrentHashMap.newKeySet();
 @BeforeEach void setup(){
  new RabbitAdmin(connection).purgeQueue("seckill.test.orders.v3.dead",false);
  var key=new GeneratedKeyHolder();
  jdbc.update(c->c.prepareStatement("INSERT INTO product(name,stock,price) VALUES ('stage3-test',10,6999.00)",java.sql.Statement.RETURN_GENERATED_KEYS),key);
  id=key.getKey().longValue();initializer.initialize(id);registry.start();
 }
 @AfterEach void cleanup(){
  registry.stop();reset(orders,publisher,requests);
  var admin=new RabbitAdmin(connection);admin.purgeQueue("seckill.test.orders.v3",false);admin.purgeQueue("seckill.test.orders.v3.dead",false);
  for(String ticket:tickets)redis.delete("seckill_request_"+ticket);
  redis.delete(RedisStockService.keys(id));
  jdbc.update("DELETE FROM seckill_async_order WHERE product_id=?",id);
  jdbc.update("DELETE FROM seckill_order WHERE product_id=?",id);
  jdbc.update("DELETE FROM seckill_stock_baseline WHERE product_id=?",id);
  jdbc.update("DELETE FROM product WHERE id=?",id);
 }
 AsyncReceipt submit(){var receipt=service.submit(id,1001);tickets.add(receipt.requestId());return receipt;}
 void waitFor(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("Async condition timed out");Thread.sleep(30);}}
 int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,id);}
 @Test void pausedConsumerReturns202ThenDrains()throws Exception{
  registry.stop();
  String body=mvc.perform(post("/seckill/"+id).param("userId","1001")).andExpect(status().isAccepted()).andExpect(jsonPath("status").value("QUEUED")).andReturn().getResponse().getContentAsString();
  var receipt=json.readValue(body,AsyncReceipt.class);tickets.add(receipt.requestId());
  assertEquals(0,count());assertEquals(9,service.product(id).getStock());
  assertEquals("PENDING",service.result(receipt.requestId()).status());
  registry.start();waitFor(()->"SUCCESS".equals(service.result(receipt.requestId()).status()));
  assertEquals(1,count());assertNotNull(service.result(receipt.requestId()).orderId());
 }
 @Test void concurrentRequestsCannotOversell()throws Exception{
  try(var pool=Executors.newFixedThreadPool(20)){
   var work=new ArrayList<Future<Integer>>();
   for(int n=0;n<100;n++)work.add(pool.submit(()->{try{return "QUEUED".equals(submit().status())?202:503;}catch(org.springframework.web.server.ResponseStatusException e){return e.getStatusCode().value();}}));
   int accepted=0;for(var f:work){int code=f.get(30,TimeUnit.SECONDS);if(code==202)accepted++;else assertEquals(409,code);}assertEquals(10,accepted);
  }
  waitFor(()->count()==10);assertEquals(0,service.product(id).getStock());
 }
 @Test void duplicateMessageCreatesOneOrder()throws Exception{
  registry.stop();var receipt=submit();
  var payload=json.readValue((String)redis.opsForHash().get("seckill_request_"+receipt.requestId(),"payload"),OrderMessage.class);
  publisher.publish(payload);registry.start();
  waitFor(()->"SUCCESS".equals(service.result(receipt.requestId()).status()));
  assertEquals(service.result(receipt.requestId()).orderId(),writer.create(payload).getId());
  registry.stop();assertEquals(1,count());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_async_order WHERE product_id=?",Integer.class,id));
 }
 @Test void transientFailureRollsBackThenRetries()throws Exception{
  doThrow(new IllegalStateException("injected transient failure")).doAnswer(invocation -> { Order order=invocation.getArgument(0); jdbc.update("INSERT INTO seckill_order(user_id,product_id,quantity,price,created_at) VALUES (?,?,?,?,?)",order.getUserId(),order.getProductId(),order.getQuantity(),order.getPrice(),order.getCreatedAt()); order.setId(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)); return 1; }).when(orders).insert(any(Order.class));
  var receipt=submit();waitFor(()->"SUCCESS".equals(service.result(receipt.requestId()).status()));assertEquals(1,count());
 }
 @Test void exhaustedRetryGoesToDeadLetterWithoutRefund()throws Exception{
  doThrow(new IllegalStateException("injected permanent failure")).when(orders).insert(any(Order.class));
  var receipt=submit();waitFor(()->"REVIEW_REQUIRED".equals(service.result(receipt.requestId()).status()));
  var admin=new RabbitAdmin(connection);waitFor(()->{var p=admin.getQueueProperties("seckill.test.orders.v3.dead");return p!=null&&((Integer)p.get(RabbitAdmin.QUEUE_MESSAGE_COUNT))==1;});
  assertEquals(0,count());assertEquals(9,service.product(id).getStock());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_async_order WHERE product_id=?",Integer.class,id));
 }
 @Test void publishFailureRetainsReservationAndRequestId()throws Exception{
  doThrow(new TimeoutException("injected unknown publish result")).when(publisher).publish(any(OrderMessage.class));
  var receipt=submit();assertEquals("UNKNOWN",receipt.status());assertEquals("UNKNOWN",service.result(receipt.requestId()).status());assertEquals(9,service.product(id).getStock());assertEquals(0,count());
 }
 @Test void committedOrderRemainsSuccessfulWhenRedisCleanupFails()throws Exception{
  doThrow(new IllegalStateException("injected cleanup failure")).when(requests).complete(any(OrderMessage.class),anyLong());
  var receipt=submit();waitFor(()->"SUCCESS".equals(service.result(receipt.requestId()).status()));
  assertEquals(1,count());assertEquals(9,service.product(id).getStock());
  assertEquals(1L,redis.opsForHash().size(RedisStockService.keys(id).get(2)));
 }
 @Test void invalidInputRejected()throws Exception{
  mvc.perform(post("/seckill/"+id).param("userId","0")).andExpect(status().isBadRequest());
  mvc.perform(get("/seckill/result/not-a-uuid")).andExpect(status().isBadRequest());assertEquals(10,service.product(id).getStock());
 }
}
