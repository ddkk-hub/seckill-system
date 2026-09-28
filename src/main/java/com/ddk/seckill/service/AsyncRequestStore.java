package com.ddk.seckill.service;

import com.ddk.seckill.entity.OrderMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AsyncRequestStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final DefaultRedisScript<Long> reserve=script("async-reserve");
    private final DefaultRedisScript<Long> state=script("async-state");
    private final DefaultRedisScript<Long> complete=script("async-complete");
    public AsyncRequestStore(StringRedisTemplate redis,ObjectMapper json){this.redis=redis;this.json=json;}
    public static String key(String id){return "seckill_request_"+id;}
    public void reserve(OrderMessage message){
        var keys=new ArrayList<>(RedisStockService.keys(message.productId()));keys.add(key(message.requestId()));
        try {
            Long result=redis.execute(reserve,keys,message.requestId(),json.writeValueAsString(message));
            if(result!=null && result==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"SOLD_OUT");
            if(result==null || result!=1)throw RedisStockService.unavailable("STOCK_NOT_READY_OR_INVALID");
        } catch(JsonProcessingException e){throw new IllegalStateException("MESSAGE_SERIALIZATION_FAILED",e);}
    }
    public void verify(OrderMessage message){
        Object payload=redis.opsForHash().get(key(message.requestId()),"payload");
        Object pending=redis.opsForHash().get(RedisStockService.keys(message.productId()).get(2),message.requestId());
        if(payload==null || pending==null)throw new IllegalStateException("RESERVATION_NOT_FOUND");
        try {
            OrderMessage saved=json.readValue(payload.toString(),OrderMessage.class);
            if(!saved.equals(message))throw new IllegalStateException("MESSAGE_DOES_NOT_MATCH_RESERVATION");
        } catch(JsonProcessingException e){throw new IllegalStateException("INVALID_RESERVATION",e);}
    }
    public String state(String id){
        Object value=redis.opsForHash().get(key(id),"state");return value==null?null:value.toString();
    }
    public void mark(String id,String value){redis.execute(state,List.of(key(id)),value);}
    public void complete(OrderMessage message,long orderId){
        Long result=redis.execute(complete,List.of(key(message.requestId()),RedisStockService.keys(message.productId()).get(2)),message.requestId(),Long.toString(orderId));
        if(result==null || result!=1)throw new IllegalStateException("ASYNC_CACHE_COMPLETION_FAILED");
    }
    private static DefaultRedisScript<Long> script(String name){
        var result=new DefaultRedisScript<Long>();result.setLocation(new ClassPathResource("lua/"+name+".lua"));result.setResultType(Long.class);return result;
    }
}
