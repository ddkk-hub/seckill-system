package com.ddk.seckill.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="seckill.protection.enabled", havingValue="true")
public class TokenBucketLimiter {
    public record Bucket(String key, int rate, int capacity) {
        public Bucket {
            if (key == null || key.isBlank() || rate < 1 || capacity < 1 || rate > 1000000 || capacity > 1000000)
                throw new IllegalArgumentException("Invalid token bucket configuration");
        }
    }

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final String namespace;
    private final Bucket writeGlobal, writeIp, writeUser, readGlobal, readIp;

    public TokenBucketLimiter(StringRedisTemplate redis, Environment env) {
        this.redis = redis;
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/token-bucket.lua"));
        script.setResultType(Long.class);
        namespace = env.getProperty("seckill.limits.namespace", "seckill:stage4:limits");
        writeGlobal = configured(env, "write-global", 200, 100);
        writeIp = configured(env, "write-ip", 50, 20);
        writeUser = configured(env, "write-user", 2, 3);
        readGlobal = configured(env, "read-global", 400, 200);
        readIp = configured(env, "read-ip", 20, 10);
    }

    private Bucket configured(Environment env, String name, int rate, int capacity) {
        return new Bucket(namespace + ":" + name,
            env.getProperty("seckill.limits." + name + "-rate", Integer.class, rate),
            env.getProperty("seckill.limits." + name + "-capacity", Integer.class, capacity));
    }

    private Bucket scoped(Bucket template, String suffix) {
        return new Bucket(template.key() + ":" + suffix, template.rate(), template.capacity());
    }

    public void purchase(String remoteAddress, long userId) {
        check(List.of(writeGlobal, scoped(writeIp, hash(remoteAddress)), scoped(writeUser, Long.toString(userId))));
    }

    public void query(String remoteAddress) {
        check(List.of(readGlobal, scoped(readIp, hash(remoteAddress))));
    }

    public void check(List<Bucket> buckets) {
        if (buckets.isEmpty() || buckets.size() > 3) throw new IllegalArgumentException("Expected one to three buckets");
        var keys = new ArrayList<String>();
        var args = new ArrayList<String>();
        for (Bucket bucket : buckets) {
            keys.add(bucket.key());
            args.add(Integer.toString(bucket.rate()));
            args.add(Integer.toString(bucket.capacity()));
        }
        Long retry;
        try { retry = redis.execute(script, keys, args.toArray()); }
        catch (DataAccessException e) { throw RedisStockService.unavailable("RATE_LIMITER_UNAVAILABLE"); }
        if (retry == null || retry < 0) throw RedisStockService.unavailable("RATE_LIMITER_STATE_INVALID");
        if (retry > 0) throw new RateLimitExceededException(retry);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
