# 阶段三完整代码附件

这是当前阶段完整文件内容。未展示 application-local.properties，因为其中包含本机凭据。运行说明见 stage3.md。

## pom.xml

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>


    <!-- Spring Boot版本 -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>


    <!-- 项目信息 -->
    <groupId>com.ddk</groupId>
    <artifactId>seckill-system</artifactId>
    <version>0.0.1-SNAPSHOT</version>

    <name>seckill-system</name>
    <description>High concurrency seckill system</description>


    <!-- Java版本 -->
    <properties>
        <java.version>21</java.version>
    </properties>


    <dependencies>


        <!-- Spring MVC Web接口 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>


        <!-- JDBC数据库操作 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>


        <!-- MySQL驱动 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>


        <!-- 单元测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>


        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
            <version>3.0.3</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
    </dependencies>


    <build>

        <plugins>

            <!-- Spring Boot Maven插件 -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>

        </plugins>

    </build>


</project>
```

## src/main/java/com/ddk/seckill/controller/AsyncSeckillController.java

作用：阶段三 HTTP 接口：受理和结果查询

```java
package com.ddk.seckill.controller;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.service.AsyncSeckillService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="seckill.mode",havingValue="async")
public class AsyncSeckillController {
    private final AsyncSeckillService service;
    public AsyncSeckillController(AsyncSeckillService service){this.service=service;}
    @GetMapping("/test") public String test(){return "seckill async system running";}
    @GetMapping("/product/{id}") public Product product(@PathVariable long id){return service.product(id);}
    @GetMapping("/order/{id}") public Order order(@PathVariable long id){return service.order(id);}
    @PostMapping("/seckill/{productId}") public ResponseEntity<AsyncReceipt> purchase(@PathVariable long productId,@RequestParam long userId){
        var receipt=service.submit(productId,userId);
        return ResponseEntity.status("QUEUED".equals(receipt.status())?202:503).body(receipt);
    }
    @GetMapping("/seckill/result/{requestId}") public AsyncReceipt result(@PathVariable String requestId){return service.result(requestId);}
}
```

## src/main/java/com/ddk/seckill/controller/SeckillController.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.controller;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.entity.Product;
import com.ddk.seckill.service.SeckillOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${seckill.mode:mysql}' != 'async'")
@RestController
public class SeckillController {
    private final SeckillOperations service;

    public SeckillController(SeckillOperations service) { this.service = service; }

    @GetMapping("/test")
    public String test() { return "seckill system running"; }

    @GetMapping("/product/{id}")
    public Product product(@PathVariable long id) { return service.getProduct(id); }

    @PostMapping("/seckill/{productId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Order purchase(@PathVariable long productId, @RequestParam long userId) {
        return service.purchase(productId, userId);
    }

    @GetMapping("/order/{id}")
    public Order order(@PathVariable long id) { return service.getOrder(id); }
}
```

## src/main/java/com/ddk/seckill/entity/AsyncOrderRecord.java

作用：数据库请求流水映射

```java
package com.ddk.seckill.entity;

import java.math.BigDecimal;

public record AsyncOrderRecord(String requestId, long productId, long userId,
                               BigDecimal price, Long orderId) { }
```

## src/main/java/com/ddk/seckill/entity/AsyncReceipt.java

作用：异步接口响应

```java
package com.ddk.seckill.entity;

public record AsyncReceipt(String requestId, String status, Long orderId) { }
```

## src/main/java/com/ddk/seckill/entity/Order.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Order {
    private Long id;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal price;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

## src/main/java/com/ddk/seckill/entity/OrderMessage.java

作用：消息数据结构

```java
package com.ddk.seckill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderMessage(String requestId, long productId, long userId,
                           BigDecimal price, LocalDateTime createdAt) { }
```

## src/main/java/com/ddk/seckill/entity/Product.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.entity;

import java.math.BigDecimal;

public class Product {
    private Long id;
    private String name;
    private Integer stock;
    private BigDecimal price;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
```

## src/main/java/com/ddk/seckill/entity/StockBaseline.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.entity;

public record StockBaseline(long productId, int initialStock, long initialOrderQuantity) { }
```

## src/main/java/com/ddk/seckill/mapper/AsyncOrderMapper.java

作用：请求流水唯一约束、加锁及订单关联

```java
package com.ddk.seckill.mapper;

import com.ddk.seckill.entity.AsyncOrderRecord;
import com.ddk.seckill.entity.OrderMessage;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AsyncOrderMapper {
    @Insert("""
        INSERT INTO seckill_async_order(request_id,product_id,user_id,price,created_at)
        VALUES(#{requestId},#{productId},#{userId},#{price},#{createdAt})
        ON DUPLICATE KEY UPDATE request_id=request_id
        """)
    int claim(OrderMessage message);

    @Select("SELECT request_id,product_id,user_id,price,order_id FROM seckill_async_order WHERE request_id=#{id} FOR UPDATE")
    AsyncOrderRecord lock(String id);

    @Select("SELECT request_id,product_id,user_id,price,order_id FROM seckill_async_order WHERE request_id=#{id}")
    AsyncOrderRecord find(String id);

    @Update("UPDATE seckill_async_order SET order_id=#{orderId} WHERE request_id=#{id} AND order_id IS NULL")
    int finish(@Param("id") String id,@Param("orderId") long orderId);
}
```

## src/main/java/com/ddk/seckill/mapper/OrderMapper.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.mapper;

import com.ddk.seckill.entity.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper {
    @Insert("""
        INSERT INTO seckill_order(user_id, product_id, quantity, price, created_at)
        VALUES (#{userId}, #{productId}, #{quantity}, #{price}, #{createdAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);

    @Select("""
        SELECT id, user_id, product_id, quantity, price, created_at
        FROM seckill_order WHERE id = #{id}
        """)
    Order findById(long id);
}
```

## src/main/java/com/ddk/seckill/mapper/ProductMapper.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.mapper;

import com.ddk.seckill.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper {
    @Select("SELECT id, name, stock, price FROM product WHERE id = #{id}")
    Product findById(long id);

    // InnoDB locks the row and checks stock within the same UPDATE.
    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id} AND stock > 0")
    int decreaseStock(long id);
}
```

## src/main/java/com/ddk/seckill/mapper/StockBaselineMapper.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.mapper;

import com.ddk.seckill.entity.StockBaseline;
import org.apache.ibatis.annotations.*;

@Mapper
public interface StockBaselineMapper {
    @Select("SELECT product_id, initial_stock, initial_order_quantity FROM seckill_stock_baseline WHERE product_id=#{id}")
    StockBaseline find(long id);

    @Insert("INSERT INTO seckill_stock_baseline(product_id,initial_stock,initial_order_quantity) VALUES(#{productId},#{initialStock},#{initialOrderQuantity})")
    int insert(StockBaseline baseline);

    @Select("SELECT COALESCE(SUM(quantity),0) FROM seckill_order WHERE product_id=#{id}")
    long orderQuantity(long id);
}
```

## src/main/java/com/ddk/seckill/SeckillApplication.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SeckillApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeckillApplication.class, args);
	}

}
```

## src/main/java/com/ddk/seckill/service/AsyncOrderConsumer.java

作用：消费成功后更新 Redis，由容器 ACK

```java
package com.ddk.seckill.service;

import com.ddk.seckill.entity.OrderMessage;
import org.slf4j.Logger;
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
        var order=writer.create(message);
        try{store.complete(message,order.getId());}
        catch(RuntimeException e){log.error("Order committed; Redis cleanup requires reconciliation, request={}",message.requestId(),e);}
    }
}
```

## src/main/java/com/ddk/seckill/service/AsyncOrderWriter.java

作用：事务内去重和创建订单

```java
package com.ddk.seckill.service;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.entity.OrderMessage;
import com.ddk.seckill.mapper.AsyncOrderMapper;
import com.ddk.seckill.mapper.OrderMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AsyncOrderWriter {
    private final AsyncOrderMapper records;
    private final OrderMapper orders;
    private final AsyncRequestStore store;
    public AsyncOrderWriter(AsyncOrderMapper records,OrderMapper orders,AsyncRequestStore store){this.records=records;this.orders=orders;this.store=store;}
    @Transactional(rollbackFor=Exception.class)
    public Order create(OrderMessage message){
        UUID.fromString(message.requestId());
        if(message.productId()<=0 || message.userId()<=0 || message.price()==null || message.price().signum()<0 || message.createdAt()==null)
            throw new IllegalArgumentException("INVALID_ORDER_MESSAGE");
        records.claim(message);
        var record=records.lock(message.requestId());
        if(record.productId()!=message.productId() || record.userId()!=message.userId() || record.price().compareTo(message.price())!=0)
            throw new IllegalStateException("REQUEST_ID_PAYLOAD_CONFLICT");
        if(record.orderId()!=null)return orders.findById(record.orderId());
        store.verify(message);
        Order order=new Order();order.setUserId(message.userId());order.setProductId(message.productId());
        order.setQuantity(1);order.setPrice(message.price());order.setCreatedAt(message.createdAt());
        if(orders.insert(order)!=1)throw new IllegalStateException("ORDER_INSERT_FAILED");
        if(records.finish(message.requestId(),order.getId())!=1)throw new IllegalStateException("RESULT_WRITE_FAILED");
        return order;
    }
}
```

## src/main/java/com/ddk/seckill/service/AsyncRequestStore.java

作用：Lua 请求与库存状态管理

```java
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
```

## src/main/java/com/ddk/seckill/service/AsyncSeckillService.java

作用：校验、预扣、发布、查询编排

```java
package com.ddk.seckill.service;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.mapper.AsyncOrderMapper;
import com.ddk.seckill.mapper.OrderMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name="seckill.mode",havingValue="async")
public class AsyncSeckillService {
    private static final Logger log=LoggerFactory.getLogger(AsyncSeckillService.class);
    private final RedisStockService stock;
    private final AsyncRequestStore requests;
    private final OrderMessagePublisher publisher;
    private final AsyncOrderMapper records;
    private final OrderMapper orders;
    public AsyncSeckillService(RedisStockService stock,AsyncRequestStore requests,OrderMessagePublisher publisher,AsyncOrderMapper records,OrderMapper orders){
        this.stock=stock;this.requests=requests;this.publisher=publisher;this.records=records;this.orders=orders;
    }
    public Product product(long id){
        positive(id);
        try{return stock.get(id);}catch(DataAccessException e){throw RedisStockService.unavailable("REDIS_UNAVAILABLE");}
    }
    public Order order(long id){
        positive(id);Order result=orders.findById(id);
        if(result==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND");return result;
    }
    public AsyncReceipt submit(long productId,long userId){
        positive(productId);positive(userId);
        Product product=product(productId);
        var message=new OrderMessage(UUID.randomUUID().toString(),productId,userId,product.getPrice(),LocalDateTime.now().withNano(0));
        try{requests.reserve(message);}
        catch(DataAccessException e){return uncertain(message,e);}
        try{publisher.publish(message);return new AsyncReceipt(message.requestId(),"QUEUED",null);}
        catch(InterruptedException e){Thread.currentThread().interrupt();return uncertain(message,e);}
        catch(Exception e){return uncertain(message,e);}
    }
    private AsyncReceipt uncertain(OrderMessage message,Exception cause){
        log.error("Submission uncertain; never auto-refund or blindly retry, request={}",message.requestId(),cause);
        try{requests.mark(message.requestId(),"UNKNOWN");}catch(RuntimeException e){log.error("Unable to mark uncertain request={}",message.requestId(),e);}
        return new AsyncReceipt(message.requestId(),"UNKNOWN",null);
    }
    public AsyncReceipt result(String id){
        try{UUID.fromString(id);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_ID");}
        var record=records.find(id);
        if(record!=null && record.orderId()!=null)return new AsyncReceipt(id,"SUCCESS",record.orderId());
        String state;
        try{state=requests.state(id);}catch(DataAccessException e){throw RedisStockService.unavailable("RESULT_TEMPORARILY_UNAVAILABLE");}
        if(state==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REQUEST_NOT_FOUND");
        // MySQL is authoritative; a cache claiming SUCCESS without its DB record needs review.
        if("SUCCESS".equals(state))state="REVIEW_REQUIRED";
        return new AsyncReceipt(id,state,null);
    }
    private static void positive(long id){if(id<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ID_MUST_BE_POSITIVE");}
}
```

## src/main/java/com/ddk/seckill/service/OrderMessagePublisher.java

作用：发布持久消息并等待确认与检查返回

```java
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
```

## src/main/java/com/ddk/seckill/service/RabbitOrderConfiguration.java

作用：持久队列、交换机、重试、死信和消费者参数

```java
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
```

## src/main/java/com/ddk/seckill/service/RedisSeckillService.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.service;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.entity.Product;
import com.ddk.seckill.mapper.OrderMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "seckill.mode", havingValue = "redis")
public class RedisSeckillService implements SeckillOperations {
    private static final Logger log = LoggerFactory.getLogger(RedisSeckillService.class);
    private final RedisStockService stock;
    private final OrderMapper orders;
    private final TransactionTemplate transaction;

    public RedisSeckillService(RedisStockService stock, OrderMapper orders, PlatformTransactionManager manager) {
        this.stock = stock;
        this.orders = orders;
        this.transaction = new TransactionTemplate(manager);
        // The method owns completion; never return 201 before an outer transaction commits.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Product getProduct(long id) {
        positive(id);
        try { return stock.get(id); }
        catch (DataAccessException e) { throw RedisStockService.unavailable("REDIS_UNAVAILABLE"); }
    }

    public Order getOrder(long id) {
        positive(id);
        Order order = orders.findById(id);
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        return order;
    }

    public Order purchase(long productId, long userId) {
        positive(productId);
        positive(userId);
        Product product = getProduct(productId);
        String token = UUID.randomUUID().toString();
        try { stock.reserve(productId, token); }
        catch (DataAccessException e) {
            // A timeout may mean the script ran. Do not retry or blindly add stock.
            log.error("Reservation uncertain: product={}, token={}", productId, token, e);
            throw RedisStockService.unavailable("RESERVATION_UNCERTAIN");
        }
        try {
            return transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completion) {
                        try {
                            if (completion == STATUS_ROLLED_BACK) stock.release(productId, token);
                            else if (completion == STATUS_COMMITTED) stock.complete(productId, token);
                            else log.error("Transaction outcome unknown; keep reservation: product={}, token={}", productId, token);
                        } catch (RuntimeException e) {
                            // Do not convert a committed order into a failed HTTP response.
                            log.error("Reservation cleanup needs reconciliation: product={}, token={}, status={}", productId, token, completion, e);
                        }
                    }
                });
                Order order = new Order();
                order.setUserId(userId);
                order.setProductId(productId);
                order.setQuantity(1);
                order.setPrice(product.getPrice());
                order.setCreatedAt(LocalDateTime.now().withNano(0));
                if (orders.insert(order) != 1) throw new IllegalStateException("ORDER_INSERT_FAILED");
                return order;
            });
        } catch (RuntimeException e) {
            // Includes failure to start a transaction: retain stock conservatively for reconciliation.
            log.error("Order failed or uncertain: product={}, token={}", productId, token, e);
            throw RedisStockService.unavailable("ORDER_FAILED_OR_UNCERTAIN");
        }
    }

    private static void positive(long id) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID_MUST_BE_POSITIVE");
    }
}
```

## src/main/java/com/ddk/seckill/service/RedisStockService.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.service;

import com.ddk.seckill.entity.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RedisStockService {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final DefaultRedisScript<Long> init = script("initialize", Long.class);
    private final DefaultRedisScript<Long> reserve = script("reserve", Long.class);
    private final DefaultRedisScript<Long> release = script("release", Long.class);
    private final DefaultRedisScript<String> read = script("read", String.class);

    public RedisStockService(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public static List<String> keys(long id) {
        return List.of("product_stock_" + id, "product_info_" + id, "product_pending_" + id);
    }

    public boolean initialize(Product product) {
        try {
            Long code = redis.execute(init, keys(product.getId()), product.getStock().toString(), json.writeValueAsString(product));
            if (code == null || code < 0) throw unavailable("STOCK_INITIALIZATION_FAILED");
            return code == 1;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PRODUCT_SERIALIZATION_FAILED", e);
        }
    }

    public Product get(long id) {
        try {
            String value = redis.execute(read, keys(id));
            if (value == null) throw unavailable("STOCK_NOT_READY");
            int separator = value.indexOf('\n');
            if (separator < 1) throw unavailable("INVALID_PRODUCT_CACHE");
            Product product = json.readValue(value.substring(separator + 1), Product.class);
            product.setStock(Integer.parseInt(value.substring(0, separator)));
            return product;
        } catch (JsonProcessingException e) {
            throw unavailable("INVALID_PRODUCT_CACHE");
        }
    }

    public void reserve(long id, String token) {
        Long code = redis.execute(reserve, keys(id), token);
        if (code != null && code == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "SOLD_OUT");
        if (code == null || code != 1) throw unavailable("STOCK_NOT_READY_OR_INVALID");
    }

    public void release(long id, String token) {
        Long code = redis.execute(release, keys(id), token);
        if (code == null || code < 0) throw unavailable("STOCK_COMPENSATION_FAILED");
    }

    public void complete(long id, String token) {
        redis.opsForHash().delete(keys(id).get(2), token);
    }

    public static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + name + ".lua"));
        script.setResultType(type);
        return script;
    }
}
```

## src/main/java/com/ddk/seckill/service/SeckillOperations.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.service;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.entity.Product;

public interface SeckillOperations {
    Product getProduct(long id);
    Order getOrder(long id);
    Order purchase(long productId, long userId);
}
```

## src/main/java/com/ddk/seckill/service/SeckillService.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.service;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.entity.Product;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.mapper.ProductMapper;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "seckill.mode", havingValue = "mysql", matchIfMissing = true)
public class SeckillService implements SeckillOperations {
    private final ProductMapper products;
    private final OrderMapper orders;

    public SeckillService(ProductMapper products, OrderMapper orders) {
        this.products = products;
        this.orders = orders;
    }

    public Product getProduct(long id) {
        positive(id);
        Product product = products.findById(id);
        if (product == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        return product;
    }

    public Order getOrder(long id) {
        positive(id);
        Order order = orders.findById(id);
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        return order;
    }

    @Transactional(rollbackFor = Exception.class)
    public Order purchase(long productId, long userId) {
        positive(productId);
        positive(userId);
        if (products.decreaseStock(productId) != 1) {
            if (products.findById(productId) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOLD_OUT");
        }
        // Read price after obtaining the row lock; the lock lasts until commit.
        Product product = products.findById(productId);
        Order order = new Order();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setQuantity(1);
        order.setPrice(product.getPrice());
        order.setCreatedAt(LocalDateTime.now().withNano(0));
        if (orders.insert(order) != 1) throw new IllegalStateException("ORDER_INSERT_FAILED");
        return order;
    }

    private static void positive(long value) {
        if (value <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID_MUST_BE_POSITIVE");
    }
}
```

## src/main/java/com/ddk/seckill/service/StockInitializationCommand.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seckill.initialize-product")
public class StockInitializationCommand implements ApplicationRunner {
    private final StockInitializer initializer;
    private final ConfigurableApplicationContext context;
    private final long id;

    public StockInitializationCommand(StockInitializer initializer, ConfigurableApplicationContext context,
            @Value("${seckill.initialize-product}") long id) {
        this.initializer = initializer; this.context = context; this.id = id;
    }

    @Override public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("Initialization must run offline with --spring.main.web-application-type=none");
        }
        var product = initializer.initialize(id);
        System.out.println("Inventory ready: product=" + id + ", remaining=" + product.getStock());
        context.close();
    }
}
```

## src/main/java/com/ddk/seckill/service/StockInitializer.java

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```java
package com.ddk.seckill.service;

import com.ddk.seckill.entity.Product;
import com.ddk.seckill.entity.StockBaseline;
import com.ddk.seckill.mapper.ProductMapper;
import com.ddk.seckill.mapper.StockBaselineMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StockInitializer {
    private final ProductMapper products;
    private final StockBaselineMapper baselines;
    private final RedisStockService stock;
    private final TransactionTemplate transaction;

    public StockInitializer(ProductMapper products, StockBaselineMapper baselines, RedisStockService stock, PlatformTransactionManager manager) {
        this.products = products; this.baselines = baselines; this.stock = stock;
        this.transaction = new TransactionTemplate(manager);
    }

    // Offline administration only: stop ALL writers before importing inventory.
    public Product initialize(long id) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID_MUST_BE_POSITIVE");
        if (baselines.find(id) != null) return stock.get(id); // Never refill missing Redis data.
        Product product = products.findById(id);
        if (product == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        if (product.getStock() < 0) throw new IllegalStateException("NEGATIVE_DATABASE_STOCK");
        transaction.executeWithoutResult(status -> {
            if (baselines.insert(new StockBaseline(id, product.getStock(), baselines.orderQuantity(id))) != 1) {
                throw new IllegalStateException("BASELINE_INSERT_FAILED");
            }
        });
        // A failure here leaves a durable baseline. Recovery then requires offline reconciliation.
        if (!stock.initialize(product)) throw new IllegalStateException("REDIS_KEYS_ALREADY_EXIST_CHECK_BASELINE");
        return stock.get(id);
    }
}
```

## src/main/resources/application-stage2.properties

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```properties
seckill.mode=redis
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.database=${REDIS_DATABASE:0}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.connect-timeout=2s
spring.data.redis.timeout=2s
```

## src/main/resources/application-stage3.properties

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```properties
seckill.mode=async
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.database=${REDIS_DATABASE:0}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.connect-timeout=2s
spring.data.redis.timeout=2s
spring.rabbitmq.host=${RABBITMQ_HOST:localhost}
spring.rabbitmq.port=${RABBITMQ_PORT:5672}
spring.rabbitmq.virtual-host=seckill
spring.rabbitmq.connection-timeout=3s
spring.rabbitmq.publisher-confirm-type=correlated
spring.rabbitmq.publisher-returns=true
spring.rabbitmq.template.mandatory=true
spring.rabbitmq.listener.simple.default-requeue-rejected=false
seckill.mq.queue=seckill.orders.v3
seckill.mq.confirm-timeout-ms=5000
seckill.consumer-enabled=true
seckill.consumer-concurrency=2
seckill.consumer-prefetch=10
```

## src/main/resources/application.properties

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```properties
# 应用名称
spring.application.name=seckill-system

# 端口
server.port=8081

# URL前缀
server.servlet.context-path=/api


# MySQL配置
spring.datasource.url=jdbc:mysql://localhost:3306/seckill?sslMode=REQUIRED&serverTimezone=Asia/Shanghai

spring.datasource.username=root

spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:}

spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver



mybatis.configuration.map-underscore-to-camel-case=true
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.connection-timeout=3000
server.error.include-message=always

# Optional local credentials, excluded from Git.
spring.config.import=optional:file:./application-local.properties
```

## src/main/resources/db/stage1.sql

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```sql
-- Execute against database seckill. Existing product rows are not modified.
-- Verify product uses InnoDB and id is its primary key before running the app.
SHOW CREATE TABLE product;
CREATE TABLE IF NOT EXISTS seckill_order (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    price DECIMAL(10,2) NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_order_product (product_id),
    CONSTRAINT chk_order_quantity CHECK (quantity = 1),
    CONSTRAINT chk_order_user CHECK (user_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## src/main/resources/db/stage2.sql

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```sql
-- Run once before stage2. Existing product and order rows are unchanged.
CREATE TABLE IF NOT EXISTS seckill_stock_baseline (
    product_id BIGINT NOT NULL PRIMARY KEY,
    initial_stock INT NOT NULL,
    initial_order_quantity BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_baseline_stock CHECK (initial_stock >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## src/main/resources/db/stage3.sql

作用：创建异步请求流水表

```sql
-- Consumer deduplication and final result; existing orders remain unchanged.
CREATE TABLE IF NOT EXISTS seckill_async_order (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    order_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_async_order (order_id),
    KEY idx_async_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## src/main/resources/lua/async-complete.lua

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```lua
local kind=redis.call('TYPE',KEYS[1]).ok
if kind ~= 'none' and kind ~= 'hash' then return -1 end
local pending=redis.call('TYPE',KEYS[2]).ok
if pending ~= 'none' and pending ~= 'hash' then return -1 end
redis.call('HSET',KEYS[1],'state','SUCCESS','orderId',ARGV[2])
redis.call('EXPIRE',KEYS[1],604800)
redis.call('HDEL',KEYS[2],ARGV[1])
return 1
```

## src/main/resources/lua/async-reserve.lua

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```lua
-- KEYS: stock, product metadata, pending reservations, request ticket.
if redis.call('TYPE',KEYS[1]).ok ~= 'string' or redis.call('TYPE',KEYS[2]).ok ~= 'string' then return -2 end
local kind=redis.call('TYPE',KEYS[3]).ok
if kind ~= 'none' and kind ~= 'hash' then return -3 end
if redis.call('EXISTS',KEYS[4]) == 1 then return -3 end
local raw=redis.call('GET',KEYS[1])
local stock=tonumber(raw)
if not stock or stock < 0 or stock > 2147483647 or tostring(stock) ~= raw then return -3 end
if redis.call('HEXISTS',KEYS[3],ARGV[1]) == 1 then return -3 end
if stock == 0 then return 0 end
redis.call('HSET',KEYS[4],'payload',ARGV[2],'state','PENDING')
redis.call('HSET',KEYS[3],ARGV[1],'PENDING')
redis.call('DECR',KEYS[1])
return 1
```

## src/main/resources/lua/async-state.lua

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```lua
-- Never downgrade a completed ticket when a delayed publisher callback arrives.
if redis.call('TYPE',KEYS[1]).ok ~= 'hash' then return 0 end
if redis.call('HGET',KEYS[1],'state') == 'SUCCESS' then return 0 end
redis.call('HSET',KEYS[1],'state',ARGV[1])
return 1
```

## src/main/resources/lua/initialize.lua

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```lua
-- Never overwrite any existing inventory, metadata or pending reservation key.
for i = 1, 3 do
    if redis.call('EXISTS', KEYS[i]) == 1 then return 0 end
end
local stock = tonumber(ARGV[1])
if not stock or stock < 0 or stock % 1 ~= 0 then return -3 end
redis.call('SET', KEYS[2], ARGV[2])
redis.call('SET', KEYS[1], ARGV[1])
return 1
```

## src/main/resources/lua/read.lua

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```lua
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return nil end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not string.match(raw, '^%d+$') or not stock or stock > 2147483647 then return nil end
-- Preserve JSON numbers (IDs and prices) without a Lua floating-point round trip.
return raw .. '\n' .. redis.call('GET', KEYS[2])
```

## src/main/resources/lua/release.lua

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```lua
-- Only a known reservation can be released, at most once; never recreate stock.
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return -2 end
local kind = redis.call('TYPE', KEYS[3]).ok
if kind == 'none' then return 0 end
if kind ~= 'hash' then return -3 end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not string.match(raw, '^%d+$') or not stock or stock > 2147483647 then return -3 end
if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 0 then return 0 end
if stock >= 2147483647 then return -3 end
redis.call('INCR', KEYS[1])
redis.call('HDEL', KEYS[3], ARGV[1])
return 1
```

## src/main/resources/lua/reserve.lua

作用：阶段三配置或沿用的基础业务文件；保留完整文件便于复现。

```lua
-- Check types and values BEFORE mutation: Lua errors do not roll back writes.
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return -2 end
local kind = redis.call('TYPE', KEYS[3]).ok
if kind ~= 'none' and kind ~= 'hash' then return -3 end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not string.match(raw, '^%d+$') or not stock or stock > 2147483647 then return -3 end
if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then return -3 end
if stock == 0 then return 0 end
redis.call('HSET', KEYS[3], ARGV[1], 'PENDING')
redis.call('DECR', KEYS[1])
return 1
```

## src/test/java/com/ddk/seckill/AsyncSeckillIntegrationTest.java

作用：真实三组件集成与失败场景测试

```java
package com.ddk.seckill;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.entity.Order;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/stage2.sql,classpath:db/stage3.sql","seckill.mq.queue=seckill.test.orders.v3"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("stage3")
@EnabledIfEnvironmentVariable(named="SECKILL_MQ_TEST",matches="true")
class AsyncSeckillIntegrationTest {
 @Autowired JdbcTemplate jdbc;
 @Autowired StringRedisTemplate redis;
 @Autowired StockInitializer initializer;
 @Autowired AsyncSeckillService service;
 @SpyBean AsyncRequestStore requests;
 @Autowired AsyncOrderWriter writer;
 @SpyBean OrderMessagePublisher publisher;
 @SpyBean OrderMapper orders;
 @Autowired ObjectMapper json;
 @Autowired RabbitListenerEndpointRegistry registry;
 @Autowired ConnectionFactory connection;
 @Autowired MockMvc mvc;
 long id;
 Set<String> tickets=ConcurrentHashMap.newKeySet();
 @BeforeEach void setup(){
  new RabbitAdmin(connection).purgeQueue("seckill.test.orders.v3.dead",false);
  var key=new GeneratedKeyHolder();
  jdbc.update(c->c.prepareStatement("INSERT INTO product(name,stock,price) VALUES ('stage3-test',10,6999.00)",java.sql.Statement.RETURN_GENERATED_KEYS),key);
  id=key.getKey().longValue();initializer.initialize(id);registry.start();
 }
 @AfterEach void cleanup(){
  registry.stop();reset(orders,publisher,requests);
  var admin=new RabbitAdmin(connection);admin.purgeQueue("seckill.test.orders.v3",false);admin.purgeQueue("seckill.test.orders.v3.dead",false);
  for(String ticket:tickets)redis.delete("seckill_request_"+ticket);
  redis.delete(RedisStockService.keys(id));
  jdbc.update("DELETE FROM seckill_async_order WHERE product_id=?",id);
  jdbc.update("DELETE FROM seckill_order WHERE product_id=?",id);
  jdbc.update("DELETE FROM seckill_stock_baseline WHERE product_id=?",id);
  jdbc.update("DELETE FROM product WHERE id=?",id);
 }
 AsyncReceipt submit(){var receipt=service.submit(id,1001);tickets.add(receipt.requestId());return receipt;}
 void waitFor(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("Async condition timed out");Thread.sleep(30);}}
 int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,id);}
 @Test void pausedConsumerReturns202ThenDrains()throws Exception{
  registry.stop();
  String body=mvc.perform(post("/seckill/"+id).param("userId","1001")).andExpect(status().isAccepted()).andExpect(jsonPath("status").value("QUEUED")).andReturn().getResponse().getContentAsString();
  var receipt=json.readValue(body,AsyncReceipt.class);tickets.add(receipt.requestId());
  assertEquals(0,count());assertEquals(9,service.product(id).getStock());
  assertEquals("PENDING",service.result(receipt.requestId()).status());
  registry.start();waitFor(()->"SUCCESS".equals(service.result(receipt.requestId()).status()));
  assertEquals(1,count());assertNotNull(service.result(receipt.requestId()).orderId());
 }
 @Test void concurrentRequestsCannotOversell()throws Exception{
  try(var pool=Executors.newFixedThreadPool(20)){
   var work=new ArrayList<Future<Integer>>();
   for(int n=0;n<100;n++)work.add(pool.submit(()->{try{return "QUEUED".equals(submit().status())?202:503;}catch(org.springframework.web.server.ResponseStatusException e){return e.getStatusCode().value();}}));
   int accepted=0;for(var f:work){int code=f.get(30,TimeUnit.SECONDS);if(code==202)accepted++;else assertEquals(409,code);}assertEquals(10,accepted);
  }
  waitFor(()->count()==10);assertEquals(0,service.product(id).getStock());
 }
 @Test void duplicateMessageCreatesOneOrder()throws Exception{
  registry.stop();var receipt=submit();
  var payload=json.readValue((String)redis.opsForHash().get("seckill_request_"+receipt.requestId(),"payload"),OrderMessage.class);
  publisher.publish(payload);registry.start();
  waitFor(()->"SUCCESS".equals(service.result(receipt.requestId()).status()));
  assertEquals(service.result(receipt.requestId()).orderId(),writer.create(payload).getId());
  registry.stop();assertEquals(1,count());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_async_order WHERE product_id=?",Integer.class,id));
 }
 @Test void transientFailureRollsBackThenRetries()throws Exception{
  doThrow(new IllegalStateException("injected transient failure")).doAnswer(invocation -> { Order order=invocation.getArgument(0); jdbc.update("INSERT INTO seckill_order(user_id,product_id,quantity,price,created_at) VALUES (?,?,?,?,?)",order.getUserId(),order.getProductId(),order.getQuantity(),order.getPrice(),order.getCreatedAt()); order.setId(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)); return 1; }).when(orders).insert(any(Order.class));
  var receipt=submit();waitFor(()->"SUCCESS".equals(service.result(receipt.requestId()).status()));assertEquals(1,count());
 }
 @Test void exhaustedRetryGoesToDeadLetterWithoutRefund()throws Exception{
  doThrow(new IllegalStateException("injected permanent failure")).when(orders).insert(any(Order.class));
  var receipt=submit();waitFor(()->"REVIEW_REQUIRED".equals(service.result(receipt.requestId()).status()));
  var admin=new RabbitAdmin(connection);waitFor(()->{var p=admin.getQueueProperties("seckill.test.orders.v3.dead");return p!=null&&((Integer)p.get(RabbitAdmin.QUEUE_MESSAGE_COUNT))==1;});
  assertEquals(0,count());assertEquals(9,service.product(id).getStock());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_async_order WHERE product_id=?",Integer.class,id));
 }
 @Test void publishFailureRetainsReservationAndRequestId()throws Exception{
  doThrow(new TimeoutException("injected unknown publish result")).when(publisher).publish(any(OrderMessage.class));
  var receipt=submit();assertEquals("UNKNOWN",receipt.status());assertEquals("UNKNOWN",service.result(receipt.requestId()).status());assertEquals(9,service.product(id).getStock());assertEquals(0,count());
 }
 @Test void committedOrderRemainsSuccessfulWhenRedisCleanupFails()throws Exception{
  doThrow(new IllegalStateException("injected cleanup failure")).when(requests).complete(any(OrderMessage.class),anyLong());
  var receipt=submit();waitFor(()->"SUCCESS".equals(service.result(receipt.requestId()).status()));
  assertEquals(1,count());assertEquals(9,service.product(id).getStock());
  assertEquals(1L,redis.opsForHash().size(RedisStockService.keys(id).get(2)));
 }
 @Test void invalidInputRejected()throws Exception{
  mvc.perform(post("/seckill/"+id).param("userId","0")).andExpect(status().isBadRequest());
  mvc.perform(get("/seckill/result/not-a-uuid")).andExpect(status().isBadRequest());assertEquals(10,service.product(id).getStock());
 }
}
```

## scripts/start-rabbitmq.ps1

作用：启动和检查本地 RabbitMQ

```powershell
$ErrorActionPreference = 'Stop'
& wsl.exe -d Ubuntu -u root --exec /usr/sbin/service rabbitmq-server start
if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ service failed to start' }
& wsl.exe -d Ubuntu -u root --exec /usr/sbin/rabbitmq-diagnostics -q ping
if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ health check failed' }
```

## perf/run_stage3.py

作用：JMeter 阶段二/三对比、资源采样、库存订单核验

```python
"""Matched exploratory benchmarks. Run from project root: python perf/run_stage2.py
Runs MySQL and Redis modes sequentially, retains products and all experiment evidence.
Requires psutil, built application JAR and JMeter 5.6.3 under target/tools.
"""
import base64
import argparse,csv,hashlib,json,os,platform,socket,subprocess,time,urllib.request
from collections import Counter
from pathlib import Path
import psutil
from redis_client import Redis

parser=argparse.ArgumentParser()
parser.add_argument('--requests',type=int,default=10000)
parser.add_argument('--port',type=int,default=18084)
parser.add_argument('--modes',nargs='+',choices=['redis','async'],default=['redis','async'])
parser.add_argument('--cases',nargs='+',choices=['warmup','correctness','users-1','users-10','users-100'],default=['warmup','correctness','users-1','users-10','users-100'])
args=parser.parse_args()
assert args.requests>0 and args.requests%100==0
root=Path.cwd();out=root/'perf/results'/('stage3-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True)
config={}
for path in ['src/main/resources/application.properties','application-local.properties']:
    if Path(path).exists():config.update(dict(line.split('=',1) for line in Path(path).read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and '=' in line))
password=os.getenv('SPRING_DATASOURCE_PASSWORD',config.get('spring.datasource.password',''))
if password.startswith('${'):password=''
env=os.environ.copy();env['MYSQL_PWD']=password
mysql=os.getenv('MYSQL_EXE',r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
def sql(query):
    r=subprocess.run([mysql,'-h','127.0.0.1','-u',config['spring.datasource.username'],'-N','-B','seckill','-e',query],env=env,capture_output=True,text=True,encoding='utf-8',timeout=15)
    if r.returncode:raise RuntimeError(r.stderr)
    return r.stdout.strip()
def status():
    return {a:int(b) for a,b in (line.split('\t') for line in sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running','Threads_connected')").splitlines())}
def queue_depth():
    if mode!='async':return {}
    token=base64.b64encode((config['spring.rabbitmq.username']+':'+config['spring.rabbitmq.password']).encode()).decode()
    req=urllib.request.Request('http://127.0.0.1:15672/api/queues/seckill/seckill.benchmark.v3',headers={'Authorization':'Basic '+token})
    with urllib.request.urlopen(req,timeout=3) as response:data=json.load(response)
    return {'queue_ready':data.get('messages_ready',0),'queue_unacked':data.get('messages_unacknowledged',0)}

def redis_info():
    with Redis() as redis:return redis.info()
def redis_cpu(info):return float(info['used_cpu_sys'])+float(info['used_cpu_user'])
def launch(command,log):
    return subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
jar=Path('target/seckill-system-0.0.1-SNAPSHOT.jar')
jmeter=Path('target/tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar').resolve()
assert jar.exists() and jmeter.exists()
with socket.socket() as probe:probe.bind(('127.0.0.1',args.port))
metadata={'time':out.name,'platform':platform.platform(),'logical_cpus':psutil.cpu_count(),'memory_bytes':psutil.virtual_memory().total,'mysql_version':sql('SELECT VERSION()'),'redis_version':redis_info()['redis_version'],'requests_per_throughput_case':args.requests,'pool_size':10,'ramp_seconds':1,'mysql_tls':'REQUIRED','jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'modes_order':args.modes,'cases':args.cases,'repetitions':1,'notes':'Same machine; short exploratory samples; completion QPS is a conservative lower bound including JMeter shutdown and verification delay; broker metrics may lag 5s; SQL and CPU sampling windows include process startup/teardown.'}
(out/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
results=[]
(out/'runner-source.py').write_bytes(Path(__file__).read_bytes())
for mode in args.modes:
    app=None;load=None
    app_log=(out/(mode+'-application.log')).open('w',encoding='utf-8')
    try:
        cmd=['java','-jar',str(jar),f'--server.port={args.port}','--debug=false','--logging.level.root=INFO','--logging.level.org.springframework=INFO',f'--seckill.mode={mode}']
        cmd+=['--spring.profiles.active='+('stage3' if mode=='async' else 'stage2')]
        if mode=='async':cmd+=['--seckill.mq.queue=seckill.benchmark.v3']
        app=launch(cmd,app_log)
        for attempt in range(60):
            if app.poll() is not None:raise RuntimeError('Application exited: '+mode)
            try:
                with urllib.request.urlopen(f'http://localhost:{args.port}/api/test',timeout=1) as r:assert r.status==200
                break
            except OSError:time.sleep(1)
        else:raise RuntimeError('Readiness timeout')
        for label,users,count,initial in [('warmup',10,200,200),('correctness',100,1000,100),('users-1',1,args.requests,args.requests),('users-10',10,args.requests,args.requests),('users-100',100,args.requests,args.requests)]:
            if label not in args.cases:continue
            name=mode+'-'+label
            product=int(sql(f"INSERT INTO product(name,stock,price) VALUES ('{out.name}-{name}',{initial},6999.00); SELECT LAST_INSERT_ID();"))
            if mode in ['redis','async']:
                with (out/(name+'-initialize.log')).open('w',encoding='utf-8') as init_log:
                    init=launch(['java','-jar',str(jar),'--spring.profiles.active=stage2','--spring.main.web-application-type=none',f'--seckill.initialize-product={product}','--debug=false'],init_log)
                    try:code=init.wait(timeout=60)
                    except subprocess.TimeoutExpired:init.terminate();init.wait(timeout=10);raise
                    if code:raise RuntimeError('Inventory initialization failed: '+name)
            # Initialize long-lived process samplers before starting JMeter.
            watched={'app':psutil.Process(app.pid)};errors={}
            for process in psutil.process_iter(['name']):
                if (process.info['name'] or '').lower()=='mysqld.exe':watched['mysql-'+str(process.pid)]=process
            for key,process in list(watched.items()):
                try:process.cpu_percent()
                except psutil.Error as e:errors[key]=type(e).__name__;del watched[key]
            psutil.cpu_percent()
            before=status();rb=redis_info();start=time.monotonic()
            jtl=out/(name+'.jtl')
            with (out/(name+'.log')).open('w',encoding='utf-8') as load_log:
                command=['java','-Xms256m','-Xmx512m','-jar',str(jmeter),'-n','-t','perf/stage1.jmx',f'-Jusers={users}',f'-Jloops={count//users}','-Jramp=1',f'-Jproduct={product}',f'-Jport={args.port}','-l',str(jtl),'-j',str(out/(name+'-jmeter.log'))]
                load=launch(command,load_log)
                jp=psutil.Process(load.pid)
                try:jp.cpu_percent();watched['jmeter']=jp
                except psutil.Error as e:errors['jmeter']=type(e).__name__
                samples=[]
                while load.poll() is None:
                    time.sleep(1)
                    sample={'time':time.time(),'system_cpu':psutil.cpu_percent(),**status(),**queue_depth()}
                    for key,process in watched.items():
                        try:sample[key+'_cpu']=process.cpu_percent()/psutil.cpu_count()
                        except psutil.Error as e:errors[key]=type(e).__name__
                    samples.append(sample)
                if load.returncode:raise RuntimeError('JMeter failed: '+name)
            # Wait for committed orders and Redis completion, not just HTTP acceptance.
            drain_start=time.monotonic()
            if mode=='async':
                deadline=time.monotonic()+120
                while True:
                    committed=int(sql(f'SELECT COUNT(*) FROM seckill_order WHERE product_id={product}'))
                    with Redis() as redis:pending_now=int(redis.call('HLEN',f'product_pending_{product}'))
                    if committed==min(count,initial) and pending_now==0:break
                    if time.monotonic()>deadline:raise RuntimeError('Consumer drain timed out')
                    time.sleep(.1)
            verified_at=time.time()
            drain_wait=time.monotonic()-drain_start
            duration_monitor=time.monotonic()-start;after=status();ra=redis_info()
            rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')));codes=Counter(r['responseCode'] for r in rows)
            seconds=(max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)-min(int(r['timeStamp']) for r in rows))/1000
            db_stock,orders=map(int,sql(f'SELECT stock,(SELECT COUNT(*) FROM seckill_order WHERE product_id={product}) FROM product WHERE id={product}').split('\t'))
            pending=0;remaining=db_stock
            if mode in ['redis','async']:
                with Redis() as redis:
                    remaining=int(redis.call('GET',f'product_stock_{product}'));pending=redis.call('HLEN',f'product_pending_{product}')
            result={'mode':mode,'name':label,'users':users,'requests':len(rows),'product':product,'initial_stock':initial,'remaining_stock':remaining,'mysql_stock':db_stock,'orders':orders,'pending':pending,'seconds':seconds,'qps':len(rows)/seconds,'order_qps':orders/(verified_at-min(int(r['timeStamp']) for r in rows)/1000),'accepted_qps':codes['202']/seconds,'drain_wait_after_jmeter_seconds':drain_wait,'verified_completion_window_seconds':verified_at-min(int(r['timeStamp']) for r in rows)/1000,'avg_ms':sum(int(r['elapsed']) for r in rows)/len(rows),'max_ms':max(int(r['elapsed']) for r in rows),'codes':dict(codes),'status_before':before,'status_after':after,'samples':samples,'cpu_errors':errors,'monitor_seconds':duration_monitor,'redis_cpu_seconds':redis_cpu(ra)-redis_cpu(rb),'redis_commands_delta':int(ra['total_commands_processed'])-int(rb['total_commands_processed'])}
            results.append(result);(out/'metrics.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
            assert len(rows)==count and remaining+orders==initial and remaining>=0 and pending==0
            assert orders==codes['202' if mode=='async' else '201']==min(count,initial) and codes['409']==max(0,count-initial)
            print(f"{name}: QPS={result['qps']:.2f}, order QPS={result['order_qps']:.2f}, avg={result['avg_ms']:.2f}ms, max={result['max_ms']}ms, codes={dict(codes)}",flush=True)
    finally:
        for process in [load,app]:
            if process is not None and process.poll() is None:process.terminate();process.wait(timeout=20)
        app_log.close()
print('Evidence directory: '+str(out),flush=True)
```

## perf/run_stage3_burst.py

作用：暂停消费积压再恢复实验

```python
"""Matched exploratory benchmarks. Run from project root: python perf/run_stage2.py
Runs MySQL and Redis modes sequentially, retains products and all experiment evidence.
Requires psutil, built application JAR and JMeter 5.6.3 under target/tools.
"""
import base64
import argparse,csv,hashlib,json,os,platform,socket,subprocess,time,urllib.request
from collections import Counter
from pathlib import Path
import psutil
from redis_client import Redis

parser=argparse.ArgumentParser()
parser.add_argument('--requests',type=int,default=10000)
parser.add_argument('--port',type=int,default=18084)
parser.add_argument('--modes',nargs='+',choices=['redis','async'],default=['redis','async'])
parser.add_argument('--cases',nargs='+',choices=['warmup','correctness','users-1','users-10','users-100'],default=['warmup','correctness','users-1','users-10','users-100'])
args=parser.parse_args()
assert args.requests>0 and args.requests%100==0
root=Path.cwd();out=root/'perf/results'/('stage3-burst-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True)
config={}
for path in ['src/main/resources/application.properties','application-local.properties']:
    if Path(path).exists():config.update(dict(line.split('=',1) for line in Path(path).read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and '=' in line))
password=os.getenv('SPRING_DATASOURCE_PASSWORD',config.get('spring.datasource.password',''))
if password.startswith('${'):password=''
env=os.environ.copy();env['MYSQL_PWD']=password
mysql=os.getenv('MYSQL_EXE',r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
def sql(query):
    r=subprocess.run([mysql,'-h','127.0.0.1','-u',config['spring.datasource.username'],'-N','-B','seckill','-e',query],env=env,capture_output=True,text=True,encoding='utf-8',timeout=15)
    if r.returncode:raise RuntimeError(r.stderr)
    return r.stdout.strip()
def status():
    return {a:int(b) for a,b in (line.split('\t') for line in sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running','Threads_connected')").splitlines())}
def queue_depth():
    if mode!='async':return {}
    token=base64.b64encode((config['spring.rabbitmq.username']+':'+config['spring.rabbitmq.password']).encode()).decode()
    req=urllib.request.Request('http://127.0.0.1:15672/api/queues/seckill/seckill.benchmark.v3',headers={'Authorization':'Basic '+token})
    with urllib.request.urlopen(req,timeout=3) as response:data=json.load(response)
    return {'queue_ready':data.get('messages_ready',0),'queue_unacked':data.get('messages_unacknowledged',0)}

def redis_info():
    with Redis() as redis:return redis.info()
def redis_cpu(info):return float(info['used_cpu_sys'])+float(info['used_cpu_user'])
def launch(command,log):
    return subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
jar=Path('target/seckill-system-0.0.1-SNAPSHOT.jar')
jmeter=Path('target/tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar').resolve()
assert jar.exists() and jmeter.exists()
with socket.socket() as probe:probe.bind(('127.0.0.1',args.port))
metadata={'time':out.name,'platform':platform.platform(),'logical_cpus':psutil.cpu_count(),'memory_bytes':psutil.virtual_memory().total,'mysql_version':sql('SELECT VERSION()'),'redis_version':redis_info()['redis_version'],'requests_per_throughput_case':args.requests,'pool_size':10,'ramp_seconds':1,'mysql_tls':'REQUIRED','jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'modes_order':args.modes,'cases':args.cases,'repetitions':1,'notes':'Same machine; short exploratory samples; completion QPS is a conservative lower bound including JMeter shutdown and verification delay; broker metrics may lag 5s; SQL and CPU sampling windows include process startup/teardown.'}
(out/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')

mode='async'
processes=[];handles=[]
def start_app(port,enabled):
    log=(out/f'application-{port}.log').open('w',encoding='utf-8');handles.append(log)
    p=launch(['java','-jar',str(jar),'--spring.profiles.active=stage3',f'--server.port={port}','--seckill.mq.queue=seckill.benchmark.v3',f'--seckill.consumer-enabled={str(enabled).lower()}','--debug=false'],log);processes.append(p)
    for _ in range(60):
        if p.poll() is not None:raise RuntimeError('Application exited')
        try:
            urllib.request.urlopen(f'http://localhost:{port}/api/test',timeout=1).close();return p
        except OSError:time.sleep(1)
    raise RuntimeError('Startup timeout')
try:
    count=500
    product=int(sql(f"INSERT INTO product(name,stock,price) VALUES ('{out.name}',{count},6999.00); SELECT LAST_INSERT_ID();"))
    with (out/'initialize.log').open('w') as log:
        p=launch(['java','-jar',str(jar),'--spring.profiles.active=stage2','--spring.main.web-application-type=none',f'--seckill.initialize-product={product}','--debug=false'],log)
        processes.append(p);assert p.wait(timeout=60)==0
    start_app(args.port,False)
    jtl=out/'burst.jtl'
    with (out/'jmeter-output.log').open('w') as log:
        p=launch(['java','-Xms256m','-Xmx512m','-jar',str(jmeter),'-n','-t','perf/stage1.jmx','-Jusers=100','-Jloops=5','-Jramp=1',f'-Jproduct={product}',f'-Jport={args.port}','-l',str(jtl),'-j',str(out/'jmeter.log')],log)
        processes.append(p);assert p.wait(timeout=120)==0
    rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')));codes=Counter(r['responseCode'] for r in rows)
    assert codes=={'202':500}
    before_orders=int(sql(f'SELECT COUNT(*) FROM seckill_order WHERE product_id={product}'))
    with Redis() as redis:before_pending=redis.call('HLEN',f'product_pending_{product}')
    time.sleep(6);backlog=queue_depth()
    assert before_orders==0 and before_pending==500 and backlog['queue_ready']==500
    resume=time.monotonic();start_app(args.port+1,True)
    deadline=time.monotonic()+60
    while True:
        orders=int(sql(f'SELECT COUNT(*) FROM seckill_order WHERE product_id={product}'))
        with Redis() as redis:pending=redis.call('HLEN',f'product_pending_{product}');remaining=int(redis.call('GET',f'product_stock_{product}'))
        if orders==count and pending==0:break
        if time.monotonic()>deadline:raise RuntimeError('Drain timeout')
        time.sleep(.1)
    result={'product':product,'requests':count,'users':100,'codes':dict(codes),'orders_while_paused':before_orders,'pending_while_paused':before_pending,'queue_while_paused':backlog,'final_orders':orders,'final_pending':pending,'final_stock':remaining,'resume_to_verified_seconds_including_jvm_start':time.monotonic()-resume}
    (out/'metrics.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result),flush=True)
finally:
    for p in reversed(processes):
        if p.poll() is None:p.terminate();p.wait(timeout=20)
    for h in handles:h.close()
print('Evidence directory: '+str(out))
```

## perf/report_stage3.py

作用：从原始指标生成阶段三对比报告。

```python
"""Render the stage-three report from saved evidence; never invent missing results."""
import json,sys,statistics
from pathlib import Path
run=Path(sys.argv[1]);burst=Path(sys.argv[2]);rows=json.loads((run/'metrics.json').read_text());b=json.loads((burst/'metrics.json').read_text());env=json.loads((run/'environment.json').read_text())
def avg(values):return statistics.mean(values) if values else 0
lines=['# 阶段三压测报告','',f"测试环境：{env['platform']}；Java 21 / Spring Boot 3.3.5；MySQL {env['mysql_version']}；Redis {env['redis_version']}；RabbitMQ 4.0.5（WSL）。{env['logical_cpus']} 逻辑核、{env['memory_bytes']/1024**3:.1f} GiB 内存。JMeter 5.6.3 与应用/数据库同机。",'', '阶段：阶段二同步 Redis 与阶段三 RabbitMQ 异步的本机短时对比。每种模式预热 200 次；竞争实验 1000 次/100 件；吞吐实验每档 3000 次、库存充足；并发 1/10/100、ramp 1 秒、仅一轮。两个消费者、prefetch=10、连接池 10。','',f'原始证据：`{run.as_posix()}`；削峰：`{burst.as_posix()}`。JAR SHA256：`'+env['jar_sha256']+'`。','','## 请求结果','','| 模式 | 场景 | 并发 | 请求 | QPS | 平均 ms | 最大 ms | 201 成交响应 | 202 受理 | 409 售罄 | 其他失败 | 最终订单 | 完成 QPS 下界 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
 c=r['codes'];other=sum(v for k,v in c.items() if k not in ['201','202','409'])
 lines.append(f"| {r['mode']} | {r['name']} | {r['users']} | {r['requests']} | {r['qps']:.2f} | {r['avg_ms']:.2f} | {r['max_ms']} | {c.get('201',0)} | {c.get('202',0)} | {c.get('409',0)} | {other} | {r['orders']} | {r['order_qps']:.2f} |")
lines+=['','201 是同步创建完成；202 只是 MQ 已确认受理；409 是预期售罄但仍计入非成功请求。表内平均/最大响应时间是 HTTP 响应，不是异步订单端到端延迟。完成 QPS 下界 = 最终订单数 / 从首个请求到脚本确认全部落库的时间，包含 JMeter 退出和采样延迟，**不能当作精确消费者吞吐，也不能与 HTTP QPS 等价比较**。本轮未采集逐单端到端延迟。','','## 数据库、队列与 CPU','','CPU 为归一化到整台机器的百分比；进程 CPU 原始值除以逻辑核数。采样窗口包含 JMeter 启停。MySQL 状态为全局计数，含监控查询及同机活动，不是业务 SQL 的精确计数。Redis CPU 由 INFO 累计 CPU 差计算；未单独采样 RabbitMQ 进程 CPU。队列管理 API 可能约 5 秒刷新，峰值只是观察到的下界。','','| 模式/场景 | MySQL Questions 增量 | 行锁等待次数增量 | 行锁等待 ms | MySQL 平均 CPU% | 应用平均 CPU% | 系统平均/峰值 CPU% | Redis平均CPU% | 观察队列峰值 | JMeter结束后核验等待秒 |','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
 samples=r['samples'];mysql=[v for s in samples for k,v in s.items() if k.startswith('mysql-') and k.endswith('_cpu')];system=[s['system_cpu'] for s in samples];a=r['status_after'];z=r['status_before']
 lines.append(f"| {r['mode']}/{r['name']} | {a['Questions']-z['Questions']} | {a['Innodb_row_lock_waits']-z['Innodb_row_lock_waits']} | {a['Innodb_row_lock_time']-z['Innodb_row_lock_time']} | {avg(mysql):.2f} | {avg([s.get('app_cpu',0) for s in samples]):.2f} | {avg(system):.2f}/{max(system,default=0):.2f} | {r['redis_cpu_seconds']/r['monitor_seconds']/env['logical_cpus']*100:.2f} | {max((s.get('queue_ready',0)+s.get('queue_unacked',0) for s in samples),default=0)} | {r['drain_wait_after_jmeter_seconds']:.3f} |")
lines+=['','## 暂停消费、恢复消费实验','',f"100 并发发送 {b['requests']} 个请求：全部返回 202。暂停时 MySQL 订单 {b['orders_while_paused']}，Redis pending {b['pending_while_paused']}，队列 ready {b['queue_while_paused']['queue_ready']}。恢复后订单 {b['final_orders']}，库存 {b['final_stock']}，pending {b['final_pending']}。从启动消费者进程到确认耗尽 {b['resume_to_verified_seconds_including_jvm_start']:.2f} 秒（包含 JVM 启动，不能当作纯消费耗时）。",'','这证明队列能够在消费者暂停时暂存请求，恢复后继续落库；不代表无限积压或生产集群故障恢复能力。','','## 对比与问题','']
for users in [1,10,100]:
 old=next(r for r in rows if r['mode']=='redis' and r['name']==f'users-{users}');new=next(r for r in rows if r['mode']=='async' and r['name']==f'users-{users}')
 lines.append(f"- {users} 并发：阶段二 HTTP QPS {old['qps']:.2f}，阶段三受理 QPS {new['qps']:.2f}，变化 {(new['qps']/old['qps']-1)*100:+.1f}%。两者返回语义不同，不能由此直接推断成交吞吐提升。")
lines+=['','所有案例均核对剩余库存 + 最终订单 = 初始库存，pending = 0。没有发现超卖或重复订单。单轮短时、同机压测不能推断容量上限或稳定 P99；下一轮性能分析应多次重复、延长持续时间、隔离压测机，并采集逐单端到端时间。发布确认、Redis 请求记录、MySQL 流水都会增加开销；阶段三主要收益是解耦与削峰，实测不保证每档 QPS 提升。','','当前可靠性边界：Redis 与 MQ 无原子事务、无自动恢复；MySQL 已提交后 Redis 清理失败需对账；单节点 classic 队列不是高可用，死信转发仍可能丢失；没有入口限流。完整设计与验收见 stage3.md。']
Path('docs/stage3-report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Generated docs/stage3-report.md')

```
