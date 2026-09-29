package com.ddk.seckill.entity;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String code, String message, String traceId) {}
