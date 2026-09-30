package com.ddk.seckill.service;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name="seckill.auth.enabled", havingValue="true")
public class OwnedOrderService {
    private final AsyncOrderMapper records;
    private final OrderMapper orders;
    private final AsyncSeckillService async;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public OwnedOrderService(AsyncOrderMapper records,OrderMapper orders,AsyncSeckillService async,StringRedisTemplate redis,ObjectMapper json) {
        this.records=records;this.orders=orders;this.async=async;this.redis=redis;this.json=json;
    }
    public Order order(long id,long user) {
        Order order=orders.findById(id);
        if(order==null || order.getUserId()!=user)throw missing();
        return order;
    }
    public AsyncReceipt result(String id,long user) {
        try {if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_ID");}
        AsyncOrderRecord record=records.find(id);
        if(record!=null) {
            if(record.userId()!=user)throw missing();
            return async.result(id);
        }
        // Pending requests have no DB row yet; ownership comes from the server-created reservation.
        Object payload=redis.opsForHash().get(AsyncRequestStore.key(id),"payload");
        if(payload==null)throw missing();
        OrderMessage message;
        try {message=json.readValue(payload.toString(),OrderMessage.class);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"REQUEST_STATE_INVALID");}
        if(!id.equals(message.requestId()) || message.userId()!=user)throw missing();
        return async.result(id);
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
}
