package com.ddk.seckill;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.service.SeckillService;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SECKILL_MYSQL_TEST", matches = "true")
class SeckillMySqlTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired SeckillService service;
    @SpyBean OrderMapper orders;
    private long productId;

    @BeforeEach void setup() {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                "INSERT INTO product(name,stock,price) VALUES ('stage1-test',10,6999.00)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            return statement;
        }, key);
        productId = key.getKey().longValue();
    }
    @AfterEach void cleanup() {
        if (productId > 0) {
            jdbc.update("DELETE FROM seckill_order WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }

    @Test void httpFlowAndValidation() throws Exception {
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/product/" + productId);
        mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/" + productId).param("userId", "1001"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/order/" + id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.productId").value(productId));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/" + productId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/" + productId).param("userId", "0"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/product/9223372036854775807"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        jdbc.update("UPDATE product SET stock=0 WHERE id=?", productId);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/" + productId).param("userId", "1002"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }
    @Test void concurrentPurchasesNeverOversell() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 100; i++) {
                final long user = i + 1;
                futures.add(executor.submit(() -> {
                    start.await();
                    try { service.purchase(productId, user); return 201; }
                    catch (ResponseStatusException e) { return e.getStatusCode().value(); }
                }));
            }
            start.countDown();
            int success = 0;
            for (var future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 201) success++;
                else assertEquals(409, status);
            }
            assertEquals(10, success);
        }
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, productId));
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?", Integer.class, productId));
    }
    @Test void failedOrderInsertRollsBackStock() {
        doThrow(new IllegalStateException("Injected database failure")).when(orders).insert(any(Order.class));
        assertThrows(IllegalStateException.class, () -> service.purchase(productId, 1001));
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, productId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?", Integer.class, productId));
    }
}
