package com.ddk.seckill;

import com.ddk.seckill.entity.Product;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.service.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RedisFailureTest {
    private Product product() {
        Product p = new Product(); p.setId(1L); p.setStock(10); p.setPrice(new BigDecimal("6999.00")); return p;
    }
    @Test void redisUnavailableNeverCreatesOrder() {
        var stock=mock(RedisStockService.class); var orders=mock(OrderMapper.class);
        var service=new RedisSeckillService(stock,orders,mock(PlatformTransactionManager.class));
        when(stock.get(1)).thenThrow(new RedisConnectionFailureException("offline"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.purchase(1,1)).getStatusCode().value());
        verifyNoInteractions(orders);
    }
    @Test void reservationTimeoutIsNeverBlindlyCompensated() {
        var stock=mock(RedisStockService.class); var orders=mock(OrderMapper.class);
        var service=new RedisSeckillService(stock,orders,mock(PlatformTransactionManager.class));
        when(stock.get(1)).thenReturn(product());
        doThrow(new RedisConnectionFailureException("uncertain timeout")).when(stock).reserve(eq(1L),anyString());
        assertThrows(ResponseStatusException.class,()->service.purchase(1,1));
        verify(stock,never()).release(anyLong(),anyString());
        verifyNoInteractions(orders);
    }
    @Test void unknownCommitOutcomeNeverRestoresInventory() {
        var stock=mock(RedisStockService.class); var orders=mock(OrderMapper.class);
        when(stock.get(1)).thenReturn(product()); when(orders.insert(any())).thenReturn(1);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object tx,TransactionDefinition definition){}
            protected void doCommit(DefaultTransactionStatus status){throw new TransactionSystemException("Commit response lost");}
            protected void doRollback(DefaultTransactionStatus status){}
        };
        var service=new RedisSeckillService(stock,orders,manager);
        assertThrows(ResponseStatusException.class,()->service.purchase(1,1));
        verify(stock,never()).release(anyLong(),anyString());
        verify(stock,never()).complete(anyLong(),anyString());
    }
}
