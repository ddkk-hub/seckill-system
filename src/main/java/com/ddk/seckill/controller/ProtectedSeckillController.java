package com.ddk.seckill.controller;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="seckill.protection.enabled", havingValue="true")
public class ProtectedSeckillController {
    private final ProtectedSeckillService protectedService;
    private final AsyncSeckillService async;
    private final TokenBucketLimiter limiter;

    public ProtectedSeckillController(ProtectedSeckillService protectedService, AsyncSeckillService async, TokenBucketLimiter limiter) {
        this.protectedService = protectedService; this.async = async; this.limiter = limiter;
    }

    @GetMapping("/test")
    public String test() { return "seckill protected async system running"; }

    @GetMapping("/product/{id}")
    public Product product(@PathVariable long id, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return async.product(id);
    }

    @GetMapping("/order/{id}")
    public Order order(@PathVariable long id, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return async.order(id);
    }

    @GetMapping("/seckill/result/{requestId}")
    public AsyncReceipt result(@PathVariable String requestId, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return async.result(requestId);
    }

    @PostMapping("/seckill/{productId}")
    public ResponseEntity<AsyncReceipt> purchase(@PathVariable long productId, @RequestParam long userId,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        if (productId <= 0 || userId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID_MUST_BE_POSITIVE");
        ProtectedSeckillService.requestId(userId, key); // Reject invalid keys before allocating Redis keys.
        limiter.purchase(request.getRemoteAddr(), userId);
        AsyncReceipt receipt = protectedService.submit(productId, userId, key);
        int status = switch (receipt.status()) {
            case "SUCCESS" -> 200;
            case "QUEUED", "PENDING" -> 202;
            default -> 503;
        };
        return ResponseEntity.status(status).body(receipt);
    }
}
