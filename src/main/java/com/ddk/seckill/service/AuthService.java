package com.ddk.seckill.service;

import com.ddk.seckill.entity.AuthUser;
import com.ddk.seckill.mapper.AuthUserMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name="seckill.auth.enabled", havingValue="true")
public class AuthService {
    public record Credentials(String username, String password) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record Identity(long userId, String username) {}
    public record Session(String tokenType, String accessToken, long expiresIn, long userId) {
        @Override public String toString() { return "Session[redacted]"; }
    }
    private final AuthUserMapper users;
    private final StringRedisTemplate redis;
    private final TokenBucketLimiter limiter;
    private final long ttl;
    private final String namespace;
    private final SecureRandom random = new SecureRandom();
    private static final String HASH_VERSION="{pbkdf2-sha256-600k}";
    private final Pbkdf2PasswordEncoder passwords = new Pbkdf2PasswordEncoder("",16,600000,
        Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    private final String dummy;
    public AuthService(AuthUserMapper users, StringRedisTemplate redis, TokenBucketLimiter limiter,
            @Value("${seckill.auth.session-seconds:1800}") long ttl,
            @Value("${seckill.auth.namespace:seckill:auth:v6}") String namespace) {
        if (ttl<1 || ttl>86400) throw new IllegalArgumentException("Session TTL must be 1..86400 seconds");
        this.users=users;this.redis=redis;this.limiter=limiter;this.ttl=ttl;this.namespace=namespace;
        dummy=passwords.encode(UUID.randomUUID().toString());
    }
    private String validate(Credentials c) {
        if (c==null || c.username()==null || !c.username().matches("[A-Za-z0-9_]{3,32}") ||
            c.password()==null || c.password().length()<12 || c.password().length()>128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_CREDENTIAL_FORMAT");
        return c.username().toLowerCase(Locale.ROOT);
    }
    private void throttle(String ip,String username) {
        limiter.check(List.of(new TokenBucketLimiter.Bucket(namespace+":limit:global",5,10),
            new TokenBucketLimiter.Bucket(namespace+":limit:ip:"+digest(ip),1,5),
            new TokenBucketLimiter.Bucket(namespace+":limit:account:"+digest(username),1,5)));
    }
    public Identity register(Credentials credentials,String ip) {
        String username=validate(credentials);throttle(ip,username);
        String hash=HASH_VERSION+passwords.encode(credentials.password());
        for(int attempt=0;attempt<5;attempt++) {
            long id=1_000_000_000_000L+random.nextLong(8_000_000_000_000L);
            if(users.idUsed(id))continue;
            try {users.insert(new AuthUser(id,username,hash));return new Identity(id,username);}
            catch(DuplicateKeyException e) {
                if(users.find(username)!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"USERNAME_UNAVAILABLE");
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"USER_ID_ALLOCATION_FAILED");
    }
    public Session login(Credentials credentials,String ip) {
        String username=validate(credentials);throttle(ip,username);
        AuthUser user=users.find(username);
        String stored=user==null?null:user.passwordHash();
        boolean supported=stored!=null && stored.startsWith(HASH_VERSION);
        boolean matches=passwords.matches(credentials.password(),supported?stored.substring(HASH_VERSION.length()):dummy);
        if(!matches || user==null || !supported)throw unauthorized();
        for(int i=0;i<3;i++) {
            byte[] bytes=new byte[32];random.nextBytes(bytes);
            String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(sessionKey(token),Long.toString(user.id()),Duration.ofSeconds(ttl))))
                return new Session("Bearer",token,ttl,user.id());
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SESSION_CREATION_FAILED");
    }
    public long authenticate(String authorization) {
        String token=token(authorization);
        String value=redis.opsForValue().get(sessionKey(token));
        if(value==null)throw unauthorized();
        try {long id=Long.parseLong(value);if(id<=0)throw new NumberFormatException();return id;}
        catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SESSION_STATE_INVALID");}
    }
    public void logout(String authorization) {redis.delete(sessionKey(token(authorization)));}
    private static String token(String header) {
        if(header==null || !header.matches("(?i:Bearer) [A-Za-z0-9_-]{43}"))throw unauthorized();
        return header.substring(7);
    }
    public String sessionKey(String token) {return namespace+":session:"+digest(token);}
    public static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static ResponseStatusException unauthorized() {return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED");}
}
