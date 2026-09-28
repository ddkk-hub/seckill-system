package com.ddk.seckill.entity;

import java.math.BigDecimal;

public record AsyncOrderRecord(String requestId, long productId, long userId,
                               BigDecimal price, Long orderId) { }
