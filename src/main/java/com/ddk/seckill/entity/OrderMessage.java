package com.ddk.seckill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderMessage(String requestId, long productId, long userId,
                           BigDecimal price, LocalDateTime createdAt) { }
