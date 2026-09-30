package com.ddk.seckill.controller;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnExpression("${seckill.auth.enabled:false}")
public class AuthenticatedSeckillController {
    private final ProtectedSeckillService protectedService;
    private final AsyncSeckillService async;
    private final TokenBucketLimiter limiter;
    private final OwnedOrderService owned;

    public AuthenticatedSeckillController(ProtectedSeckillService protectedService, AsyncSeckillService async, TokenBucketLimiter limiter, OwnedOrderService owned) {
        this.protectedService = protectedService; this.async = async; this.limiter = limiter; this.owned = owned;
    }

    @GetMapping("/test")
    public String test() { return "seckill protected async system running"; }

    @GetMapping("/product/{id}")
    public Product product(@PathVariable long id, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return async.product(id);
    }

    @GetMapping("/order/{id}")
    public Order order(@PathVariable long id, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return owned.order(id, AuthWebConfiguration.user(request));
    }

    @GetMapping("/seckill/result/{requestId}")
    public AsyncReceipt result(@PathVariable String requestId, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return owned.result(requestId, AuthWebConfiguration.user(request));
    }

    @PostMapping("/seckill/{productId}")
    public ResponseEntity<AsyncReceipt> purchase(@PathVariable long productId, @RequestParam(required=false) Long userId,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        if (userId != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "USER_ID_MUST_NOT_BE_SUPPLIED");
        userId = AuthWebConfiguration.user(request);
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
