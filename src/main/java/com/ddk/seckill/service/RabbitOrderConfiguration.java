package com.ddk.seckill.service;

import com.ddk.seckill.entity.OrderMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="seckill.mode",havingValue="async")
public class RabbitOrderConfiguration {
    @Bean public Declarables orderTopology(@Value("${seckill.mq.queue}") String name){
        var exchange=new DirectExchange(name+".exchange",true,false);
        var deadExchange=new DirectExchange(name+".dlx",true,false);
        var queue=QueueBuilder.durable(name).maxLength(100000).overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange(name+".dlx").deadLetterRoutingKey("dead").build();
        var dead=QueueBuilder.durable(name+".dead").build();
        return new Declarables(exchange,deadExchange,queue,dead,
                BindingBuilder.bind(queue).to(exchange).with("orders"),
                BindingBuilder.bind(dead).to(deadExchange).with("dead"));
    }
    @Bean public Jackson2JsonMessageConverter orderMessageConverter(ObjectMapper json){return new Jackson2JsonMessageConverter(json);}
    @Bean public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connection,SimpleRabbitListenerContainerFactoryConfigurer configurer,
            Jackson2JsonMessageConverter converter,AsyncRequestStore store,ObjectMapper json,
            @Value("${seckill.consumer-concurrency:2}") int concurrency,
            @Value("${seckill.consumer-prefetch:10}") int prefetch){
        var factory=new SimpleRabbitListenerContainerFactory();configurer.configure(factory,connection);
        factory.setMessageConverter(converter);factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setConcurrentConsumers(concurrency);factory.setMaxConcurrentConsumers(concurrency);factory.setPrefetchCount(prefetch);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3).backOffOptions(200,2,1000)
            .recoverer((message,cause)->{
                try {
                    var payload=json.readValue(message.getBody(),OrderMessage.class);
                    store.mark(payload.requestId(),"REVIEW_REQUIRED");
                } catch(Exception e){LoggerFactory.getLogger(RabbitOrderConfiguration.class).error("Dead-letter status update failed",e);}
                new RejectAndDontRequeueRecoverer().recover(message,cause);
            }).build());
        return factory;
    }
}
