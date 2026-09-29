package com.ddk.seckill.service;

import com.ddk.seckill.entity.AsyncReceipt;
import com.ddk.seckill.entity.OrderMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name="seckill.protection.enabled", havingValue="true")
public class ProtectedSeckillService {
    private static final Logger log = LoggerFactory.getLogger(ProtectedSeckillService.class);
    private final AsyncSeckillService async;
    private final AsyncRequestStore requests;
    private final OrderMessagePublisher publisher;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final DefaultRedisScript<Long> reserve;

    public ProtectedSeckillService(AsyncSeckillService async, AsyncRequestStore requests,
            OrderMessagePublisher publisher, StringRedisTemplate redis, ObjectMapper json) {
        this.async = async; this.requests = requests; this.publisher = publisher; this.redis = redis; this.json = json;
        reserve = new DefaultRedisScript<>();
        reserve.setLocation(new ClassPathResource("lua/idempotent-reserve.lua"));
        reserve.setResultType(Long.class);
    }

    public static String requestId(long userId, String key) {
        try {
            UUID parsed = UUID.fromString(key);
            if (!parsed.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
            return UUID.nameUUIDFromBytes(("seckill-stage4:" + userId + ":" + parsed).getBytes(StandardCharsets.UTF_8)).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MUST_BE_UUID");
        }
    }

    public static String marker(String requestId) { return "seckill_idempotency_" + requestId; }

    public AsyncReceipt submit(long productId, long userId, String key) {
        if (productId <= 0 || userId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID_MUST_BE_POSITIVE");
        String id = requestId(userId, key);
        String fingerprint = userId + ":" + productId;
        // Fast replay path also works after product cache or request ticket has expired.
        try {
            String existing = redis.opsForValue().get(marker(id));
            if (existing != null) {
                if (!existing.equals(fingerprint)) throw conflict();
                return replay(id);
            }
        } catch (DataAccessException e) { return unknown(id, e); }
        var product = async.product(productId);
        var message = new OrderMessage(id, productId, userId, product.getPrice(), LocalDateTime.now().withNano(0));
        var keys = new ArrayList<>(RedisStockService.keys(productId));
        keys.add(AsyncRequestStore.key(id)); keys.add(marker(id));
        Long result;
        try { result = redis.execute(reserve, keys, id, json.writeValueAsString(message), fingerprint); }
        catch (JsonProcessingException e) { throw new IllegalStateException("MESSAGE_SERIALIZATION_FAILED", e); }
        catch (DataAccessException e) { return unknown(id, e); }
        if (result != null && result == 2) return replay(id);
        if (result != null && result == -4) throw conflict();
        if (result != null && result == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "SOLD_OUT");
        if (result == null || result != 1) throw RedisStockService.unavailable("STOCK_OR_IDEMPOTENCY_STATE_INVALID");
        try {
            publisher.publish(message);
            log.info("submission queued request={}", id);
            return new AsyncReceipt(id, "QUEUED", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return unknown(id, e);
        } catch (Exception e) { return unknown(id, e); }
    }

    private AsyncReceipt replay(String id) {
        try { return async.result(id); }
        catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 404) return new AsyncReceipt(id, "UNKNOWN", null);
            throw e;
        }
    }

    private AsyncReceipt unknown(String id, Exception cause) {
        log.error("Protected submission uncertain, request={}; retry with the SAME Idempotency-Key", id, cause);
        try { requests.mark(id, "UNKNOWN"); }
        catch (RuntimeException e) { log.error("Unable to mark uncertain request={}", id, e); }
        return new AsyncReceipt(id, "UNKNOWN", null);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED_FOR_DIFFERENT_PRODUCT");
    }
}
