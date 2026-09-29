package com.ddk.seckill.service;

import com.ddk.seckill.entity.OrderMessage;
import org.slf4j.Logger;
import org.slf4j.MDC;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="seckill.mode",havingValue="async")
public class AsyncOrderConsumer {
    private static final Logger log=LoggerFactory.getLogger(AsyncOrderConsumer.class);
    private final AsyncOrderWriter writer;
    private final AsyncRequestStore store;
    public AsyncOrderConsumer(AsyncOrderWriter writer,AsyncRequestStore store){this.writer=writer;this.store=store;}
    @RabbitListener(id="seckillOrderListener",queues="${seckill.mq.queue}",autoStartup="${seckill.consumer-enabled:true}")
    public void consume(OrderMessage message){
        // The transaction proxy returns only AFTER commit. AUTO ack follows successful method return.
        String trace;
        try { trace=UUID.fromString(message.requestId()).toString(); }
        catch (RuntimeException e) { trace=UUID.randomUUID().toString(); }
        try (MDC.MDCCloseable ignored=MDC.putCloseable("traceId",trace)) {
        var order=writer.create(message);
        try{store.complete(message,order.getId());}
        catch(RuntimeException e){log.error("Order committed; Redis cleanup requires reconciliation, request={}",message.requestId(),e);}
        log.info("order committed request={} order={}",trace,order.getId());
        }
    }
}
