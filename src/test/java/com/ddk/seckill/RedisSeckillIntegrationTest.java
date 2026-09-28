package com.ddk.seckill;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.service.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties = {"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:db/stage2.sql"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("stage2")
@EnabledIfEnvironmentVariable(named = "SECKILL_REDIS_TEST", matches = "true")
class RedisSeckillIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @SpyBean RedisStockService stock;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired StockInitializer initializer;
    @Autowired SeckillOperations service;
    @SpyBean OrderMapper orders;
    long id;

    @BeforeEach void setup() {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement("INSERT INTO product(name,stock,price) VALUES ('stage2-test',10,6999.00)", java.sql.Statement.RETURN_GENERATED_KEYS), key);
        id = key.getKey().longValue();
        initializer.initialize(id);
    }
    @AfterEach void cleanup() {
        if (id > 0) {
            redis.delete(RedisStockService.keys(id));
            jdbc.update("DELETE FROM seckill_order WHERE product_id=?", id);
            jdbc.update("DELETE FROM seckill_stock_baseline WHERE product_id=?", id);
            jdbc.update("DELETE FROM product WHERE id=?", id);
        }
    }
    @Test void purchaseUsesRedisAndRepeatedInitializationNeverRefills() {
        Order order = service.purchase(id, 1);
        assertNotNull(order.getId());
        assertEquals(9, initializer.initialize(id).getStock());
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, id));
        assertEquals(id, service.getOrder(order.getId()).getProductId());
        assertEquals(0, redis.opsForHash().size(RedisStockService.keys(id).get(2)));
    }
    @Test void concurrentRequestsCannotOversell() throws Exception {
        try (var pool = Executors.newFixedThreadPool(20)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int i=0;i<100;i++) futures.add(pool.submit(() -> {
                start.await();
                try { service.purchase(id, 1001); return 201; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            }));
            start.countDown();
            int success=0;
            for (var future:futures) {
                int code=future.get(30,TimeUnit.SECONDS);
                if(code==201)success++;else assertEquals(409,code);
            }
            assertEquals(10,success);
        }
        assertEquals(0, service.getProduct(id).getStock());
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,id));
    }
    @Test void confirmedRollbackRestoresInventory() {
        doThrow(new IllegalStateException("Injected insert failure")).when(orders).insert(any(Order.class));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.purchase(id,1)).getStatusCode().value());
        assertEquals(10,stock.get(id).getStock());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,id));
    }
    @Test void repeatedCompensationOnlyRestoresOnce() {
        stock.reserve(id,"test-reservation");
        stock.release(id,"test-reservation");
        stock.release(id,"test-reservation");
        assertEquals(10,stock.get(id).getStock());
    }
    @Test void missingStockFailsClosedAndCannotBeReinitialized() {
        redis.delete(RedisStockService.keys(id).get(0));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.purchase(id,1)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->initializer.initialize(id));
        verify(orders,never()).insert(any(Order.class));
    }
    @Test void invalidPendingKeyDoesNotDeductStock() {
        redis.opsForValue().set(RedisStockService.keys(id).get(2),"wrong-type");
        assertThrows(ResponseStatusException.class,()->stock.reserve(id,"token"));
        assertEquals(10,stock.get(id).getStock());
    }

    @Test void committedOrderRemainsSuccessfulWhenCleanupFails() {
        doThrow(new IllegalStateException("Injected cleanup failure")).when(stock).complete(eq(id), anyString());
        Order order = service.purchase(id,1);
        assertNotNull(order.getId());
        assertEquals(9,stock.get(id).getStock());
        assertEquals(1,redis.opsForHash().size(RedisStockService.keys(id).get(2)));
        assertNotNull(service.getOrder(order.getId()));
    }
    @Test void compensationFailureKeepsStockReserved() {
        doThrow(new IllegalStateException("Injected insert failure")).when(orders).insert(any(Order.class));
        doThrow(new IllegalStateException("Injected Redis failure")).when(stock).release(eq(id), anyString());
        assertThrows(ResponseStatusException.class,()->service.purchase(id,1));
        assertEquals(9,stock.get(id).getStock());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,id));
    }
    @Test void redisProfileHttpFlow() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/"+id).param("userId","1001"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/product/"+id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.stock").value(9));
    }
}
