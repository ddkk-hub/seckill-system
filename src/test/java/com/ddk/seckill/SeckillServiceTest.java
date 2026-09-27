package com.ddk.seckill;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.entity.Product;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.mapper.ProductMapper;
import com.ddk.seckill.service.SeckillService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeckillServiceTest {
    private ProductMapper products;
    private OrderMapper orders;
    private SeckillService service;

    @BeforeEach void setup() {
        products = mock(ProductMapper.class);
        orders = mock(OrderMapper.class);
        service = new SeckillService(products, orders);
    }
    @Test void successfulPurchaseKeepsPriceSnapshot() {
        Product p = new Product(); p.setPrice(new BigDecimal("6999.00"));
        when(products.decreaseStock(1)).thenReturn(1);
        when(products.findById(1)).thenReturn(p);
        when(orders.insert(any())).thenAnswer(inv -> {
            Order order = inv.getArgument(0); order.setId(42L); return 1;
        });
        Order result = service.purchase(1, 1001);
        assertEquals(42L, result.getId());
        assertEquals(new BigDecimal("6999.00"), result.getPrice());
        assertEquals(1, result.getQuantity());
    }
    @Test void soldOutCreatesNoOrder() {
        when(products.findById(1)).thenReturn(new Product());
        var ex = assertThrows(ResponseStatusException.class, () -> service.purchase(1, 1001));
        assertEquals(409, ex.getStatusCode().value());
        verifyNoInteractions(orders);
    }
    @Test void missingProductReturns404() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.purchase(1, 1001));
        assertEquals(404, ex.getStatusCode().value());
        verifyNoInteractions(orders);
    }
    @Test void invalidUserDoesNotTouchDatabase() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.purchase(1, 0));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(products, orders);
    }
}
