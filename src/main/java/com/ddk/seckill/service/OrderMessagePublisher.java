package com.ddk.seckill.service;

import com.ddk.seckill.entity.OrderMessage;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="seckill.mode",havingValue="async")
public class OrderMessagePublisher {
    private final RabbitTemplate rabbit;
    private final String exchange;
    private final long timeout;
    public OrderMessagePublisher(RabbitTemplate rabbit,@Value("${seckill.mq.queue}") String name,
                                 @Value("${seckill.mq.confirm-timeout-ms:5000}") long timeout){
        this.rabbit=rabbit;this.exchange=name+".exchange";this.timeout=timeout;
    }
    public void publish(OrderMessage payload) throws Exception {
        CorrelationData correlation=new CorrelationData(payload.requestId());
        rabbit.convertAndSend(exchange,"orders",payload,message->{
            message.getMessageProperties().setMessageId(payload.requestId());
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);return message;
        },correlation);
        var confirm=correlation.getFuture().get(timeout,TimeUnit.MILLISECONDS);
        if(!confirm.isAck() || correlation.getReturned()!=null)throw new IllegalStateException("PUBLISH_NOT_CONFIRMED_OR_UNROUTABLE");
    }
}
