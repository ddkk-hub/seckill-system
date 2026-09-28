# 阶段四完整文件代码

本附件包含阶段四新增/修改文件及当前完整主源码。沿用文件保持原样；本机 application-local.properties 含凭据，不纳入附件。详细运行、测试和原理见 stage4.md。

## pom.xml

作用：Maven 依赖和可覆盖的构建产物名称，默认名称不变。

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
        <seckill.build-name>${project.artifactId}-${project.version}</seckill.build-name>
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
        <finalName>${seckill.build-name}</finalName>

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

作用：阶段三入口；启用 protection 时让位于阶段四控制器。

```java
package com.ddk.seckill.controller;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.service.AsyncSeckillService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnExpression("'${seckill.mode:mysql}' == 'async' and !${seckill.protection.enabled:false}")
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

## src/main/java/com/ddk/seckill/controller/ProtectedSeckillController.java

作用：请求头校验、限流入口、幂等下单与结果查询。

```java
package com.ddk.seckill.controller;

import com.ddk.seckill.entity.*;
import com.ddk.seckill.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="seckill.protection.enabled", havingValue="true")
public class ProtectedSeckillController {
    private final ProtectedSeckillService protectedService;
    private final AsyncSeckillService async;
    private final TokenBucketLimiter limiter;

    public ProtectedSeckillController(ProtectedSeckillService protectedService, AsyncSeckillService async, TokenBucketLimiter limiter) {
        this.protectedService = protectedService; this.async = async; this.limiter = limiter;
    }

    @GetMapping("/test")
    public String test() { return "seckill protected async system running"; }

    @GetMapping("/product/{id}")
    public Product product(@PathVariable long id, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return async.product(id);
    }

    @GetMapping("/order/{id}")
    public Order order(@PathVariable long id, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return async.order(id);
    }

    @GetMapping("/seckill/result/{requestId}")
    public AsyncReceipt result(@PathVariable String requestId, HttpServletRequest request) {
        limiter.query(request.getRemoteAddr()); return async.result(requestId);
    }

    @PostMapping("/seckill/{productId}")
    public ResponseEntity<AsyncReceipt> purchase(@PathVariable long productId, @RequestParam long userId,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
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
```

## src/main/java/com/ddk/seckill/controller/SeckillController.java

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

```java
package com.ddk.seckill.entity;

import java.math.BigDecimal;

public record AsyncOrderRecord(String requestId, long productId, long userId,
                               BigDecimal price, Long orderId) { }
```

## src/main/java/com/ddk/seckill/entity/AsyncReceipt.java

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

```java
package com.ddk.seckill.entity;

public record AsyncReceipt(String requestId, String status, Long orderId) { }
```

## src/main/java/com/ddk/seckill/entity/Order.java

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

```java
package com.ddk.seckill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderMessage(String requestId, long productId, long userId,
                           BigDecimal price, LocalDateTime createdAt) { }
```

## src/main/java/com/ddk/seckill/entity/Product.java

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

```java
package com.ddk.seckill.entity;

public record StockBaseline(long productId, int initialStock, long initialOrderQuantity) { }
```

## src/main/java/com/ddk/seckill/mapper/AsyncOrderMapper.java

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

## src/main/java/com/ddk/seckill/service/ProtectedSeckillService.java

作用：稳定请求编号、原子幂等预扣、首次发布与重复请求查询。

```java
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
```

## src/main/java/com/ddk/seckill/service/RabbitOrderConfiguration.java

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

## src/main/java/com/ddk/seckill/service/RateLimitExceededException.java

作用：返回 429 与 Retry-After 响应头。

```java
package com.ddk.seckill.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class RateLimitExceededException extends ResponseStatusException {
    private final long retrySeconds;

    public RateLimitExceededException(long retryMillis) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        retrySeconds = Math.max(1, (retryMillis + 999) / 1000);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retrySeconds));
        return headers;
    }
}
```

## src/main/java/com/ddk/seckill/service/RedisSeckillService.java

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

## src/main/java/com/ddk/seckill/service/TokenBucketLimiter.java

作用：配置全局/IP/用户桶；调用 Lua 原子检查；Redis 异常时停止放行。

```java
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
```

## src/main/resources/application-stage2.properties

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

## src/main/resources/application-stage4.properties

作用：启用阶段四并配置限流阈值、MQ/Redis 与来源地址策略。

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

# Stage 4 keeps the stage 3 queue so already queued orders continue to drain.
seckill.protection.enabled=true
server.forward-headers-strategy=none
seckill.limits.namespace=seckill:stage4:limits
seckill.limits.write-global-rate=200
seckill.limits.write-global-capacity=100
seckill.limits.write-ip-rate=50
seckill.limits.write-ip-capacity=20
seckill.limits.write-user-rate=2
seckill.limits.write-user-capacity=3
seckill.limits.read-global-rate=400
seckill.limits.read-global-capacity=200
seckill.limits.read-ip-rate=20
seckill.limits.read-ip-capacity=10
```

## src/main/resources/application.properties

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

```lua
-- Never downgrade a completed ticket when a delayed publisher callback arrives.
if redis.call('TYPE',KEYS[1]).ok ~= 'hash' then return 0 end
if redis.call('HGET',KEYS[1],'state') == 'SUCCESS' then return 0 end
redis.call('HSET',KEYS[1],'state',ARGV[1])
return 1
```

## src/main/resources/lua/idempotent-reserve.lua

作用：参数指纹检查、幂等占位和库存扣减在同一脚本中完成。

```lua
-- KEYS: stock, product metadata, pending, request ticket, permanent idempotency marker.
-- ARGV: requestId, immutable message JSON, fingerprint (userId:productId).
local markerType=redis.call('TYPE',KEYS[5]).ok
if markerType~='none' and markerType~='string' then return -3 end
local fingerprint=redis.call('GET',KEYS[5])
if fingerprint then
    if fingerprint~=ARGV[3] then return -4 end
    return 2
end
if redis.call('TYPE',KEYS[1]).ok~='string' or redis.call('TYPE',KEYS[2]).ok~='string' then return -2 end
local kind=redis.call('TYPE',KEYS[3]).ok
if kind~='none' and kind~='hash' then return -3 end
if redis.call('EXISTS',KEYS[4])==1 then return -3 end
local raw=redis.call('GET',KEYS[1])
local stock=tonumber(raw)
if not stock or stock<0 or stock>2147483647 or tostring(stock)~=raw then return -3 end
if redis.call('HEXISTS',KEYS[3],ARGV[1])==1 then return -3 end
if stock==0 then return 0 end
-- No expiry: expiring this marker would allow the same operation to reserve again.
redis.call('SET',KEYS[5],ARGV[3])
redis.call('HSET',KEYS[4],'payload',ARGV[2],'state','PENDING')
redis.call('HSET',KEYS[3],ARGV[1],'PENDING')
redis.call('DECR',KEYS[1])
return 1
```

## src/main/resources/lua/initialize.lua

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

```lua
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return nil end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not string.match(raw, '^%d+$') or not stock or stock > 2147483647 then return nil end
-- Preserve JSON numbers (IDs and prices) without a Lua floating-point round trip.
return raw .. '\n' .. redis.call('GET', KEYS[2])
```

## src/main/resources/lua/release.lua

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

作用：沿用前三阶段的业务、持久化或配置文件；保留完整内容便于运行。

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

## src/main/resources/lua/token-bucket.lua

作用：用 Redis 时间补充令牌；所有桶都允许才统一扣减。

```lua
-- All buckets must allow the request; denied requests consume no tokens.
-- Each key is a hash; ARGV contains rate/second and capacity pairs.
local clock=redis.call('TIME')
local now=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)
local tokens={}
local stamps={}
local retry=0
for i,key in ipairs(KEYS) do
    local kind=redis.call('TYPE',key).ok
    if kind~='none' and kind~='hash' then return -1 end
    local rate=tonumber(ARGV[i*2-1])
    local capacity=tonumber(ARGV[i*2])
    local saved=redis.call('HMGET',key,'tokens','time')
    local previous=tonumber(saved[1])
    local stamp=tonumber(saved[2])
    if kind=='hash' and (not previous or not stamp or previous<0) then return -1 end
    stamps[i]=math.max(now,stamp or now)
    tokens[i]=math.min(capacity,(previous or capacity)+math.max(0,now-(stamp or now))*rate/1000)
    if tokens[i]<1 then retry=math.max(retry,math.max(0,(stamp or now)-now)+math.ceil((1-tokens[i])*1000/rate)) end
end
if retry>0 then return retry end
for i,key in ipairs(KEYS) do
    local rate=tonumber(ARGV[i*2-1])
    local capacity=tonumber(ARGV[i*2])
    redis.call('HSET',key,'tokens',tostring(tokens[i]-1),'time',tostring(stamps[i]))
    redis.call('PEXPIRE',key,math.ceil(capacity*1000/rate)+60000)
end
return 0
```

## src/test/java/com/ddk/seckill/ProtectedSeckillIntegrationTest.java

作用：14 项真实组件测试；涵盖并发幂等、限流、错误及边界。

```java
package com.ddk.seckill;

import com.ddk.seckill.entity.AsyncReceipt;
import com.ddk.seckill.entity.OrderMessage;
import com.ddk.seckill.service.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/stage2.sql,classpath:db/stage3.sql","seckill.mq.queue=seckill.test.orders.v4","seckill.limits.namespace=seckill:test:stage4"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("stage4")
@EnabledIfEnvironmentVariable(named="SECKILL_PROTECTION_TEST",matches="true")
class ProtectedSeckillIntegrationTest {
    static final String PREFIX="seckill:test:stage4";
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired StockInitializer initializer;
    @Autowired ProtectedSeckillService service;
    @SpyBean AsyncSeckillService async;
    @SpyBean OrderMessagePublisher publisher;
    @Autowired TokenBucketLimiter limiter;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired ConnectionFactory connection;
    @Autowired MockMvc mvc;
    long product;
    Set<String> tickets=ConcurrentHashMap.newKeySet();

    @BeforeEach void setup(){
        clearLimits();
        var key=new GeneratedKeyHolder();
        jdbc.update(c->c.prepareStatement("INSERT INTO product(name,stock,price) VALUES ('stage4-test',10,6999.00)",java.sql.Statement.RETURN_GENERATED_KEYS),key);
        product=key.getKey().longValue();initializer.initialize(product);registry.start();
    }
    void clearLimits(){var keys=redis.keys(PREFIX+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    @AfterEach void cleanup(){
        registry.stop();reset(publisher,async);
        var admin=new RabbitAdmin(connection);admin.purgeQueue("seckill.test.orders.v4",false);admin.purgeQueue("seckill.test.orders.v4.dead",false);
        for(String id:tickets){redis.delete(AsyncRequestStore.key(id));redis.delete(ProtectedSeckillService.marker(id));}
        redis.delete(RedisStockService.keys(product));clearLimits();
        jdbc.update("DELETE FROM seckill_async_order WHERE product_id=?",product);
        jdbc.update("DELETE FROM seckill_order WHERE product_id=?",product);
        jdbc.update("DELETE FROM seckill_stock_baseline WHERE product_id=?",product);
        jdbc.update("DELETE FROM product WHERE id=?",product);
    }
    AsyncReceipt submit(long user,String key){tickets.add(ProtectedSeckillService.requestId(user,key));return service.submit(product,user,key);}
    int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,product);}
    void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("Timed out");Thread.sleep(30);}
    }
    void seed(String key,int tokens){redis.opsForHash().putAll(key,Map.of("tokens",Integer.toString(tokens),"time",Long.toString(System.currentTimeMillis()+60000)));}

    @Test void hundredIdenticalRequestsReserveAndPublishOnlyOnce()throws Exception{
        registry.stop();String key=UUID.randomUUID().toString();Set<String> ids=new HashSet<>();
        try(var pool=Executors.newFixedThreadPool(20)){
            var futures=new ArrayList<Future<AsyncReceipt>>();
            for(int n=0;n<100;n++)futures.add(pool.submit(()->submit(1001,key)));
            for(var future:futures){var r=future.get(30,TimeUnit.SECONDS);assertTrue(Set.of("QUEUED","PENDING").contains(r.status()));ids.add(r.requestId());}
        }
        assertEquals(1,ids.size());verify(publisher,times(1)).publish(any(OrderMessage.class));
        assertEquals(9,async.product(product).getStock());assertEquals(0,count());
        registry.start();await(()->count()==1);
        String id=ids.iterator().next();await(()->"SUCCESS".equals(async.result(id).status()));
        mvc.perform(post("/seckill/"+product).param("userId","1001").header("Idempotency-Key",key))
            .andExpect(status().isOk()).andExpect(jsonPath("requestId").value(id)).andExpect(jsonPath("status").value("SUCCESS"));
        assertEquals(1,count());assertEquals(-1L,redis.getExpire(ProtectedSeckillService.marker(id)));
    }
    @Test void keyCannotBeReusedForDifferentProduct(){
        String key=UUID.randomUUID().toString();submit(1001,key);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.submit(product+1,1001,key)).getStatusCode().value());
        assertEquals(9,async.product(product).getStock());
    }
    @Test void sameKeyIsScopedToUser()throws Exception{
        String key=UUID.randomUUID().toString();var a=submit(1,key);var b=submit(2,key);
        assertNotEquals(a.requestId(),b.requestId());await(()->count()==2);assertEquals(8,async.product(product).getStock());
    }
    @Test void replaySurvivesExpiredTicketAndMissingProductCache()throws Exception{
        String key=UUID.randomUUID().toString();var first=submit(1,key);
        await(()->"SUCCESS".equals(async.result(first.requestId()).status()));
        redis.delete(AsyncRequestStore.key(first.requestId()));redis.delete(RedisStockService.keys(product).get(1));
        var replay=submit(1,key);assertEquals("SUCCESS",replay.status());assertNotNull(replay.orderId());assertEquals(1,count());
        verify(publisher,times(1)).publish(any(OrderMessage.class));
    }
    @Test void publishTimeoutAndRetryDoNotRepublishOrRefund()throws Exception{
        doThrow(new TimeoutException("injected publish uncertainty")).when(publisher).publish(any(OrderMessage.class));
        String key=UUID.randomUUID().toString();var first=submit(1,key);var again=submit(1,key);
        assertEquals("UNKNOWN",first.status());assertEquals(first,again);assertEquals(9,async.product(product).getStock());assertEquals(0,count());
        verify(publisher,times(1)).publish(any(OrderMessage.class));
        redis.delete(AsyncRequestStore.key(first.requestId()));
        assertEquals("UNKNOWN",submit(1,key).status());verify(publisher,times(1)).publish(any(OrderMessage.class));
    }
    @Test void missingOrMalformedIdempotencyKeyIs400()throws Exception{
        mvc.perform(post("/seckill/"+product).param("userId","1")).andExpect(status().isBadRequest());
        mvc.perform(post("/seckill/"+product).param("userId","1").header("Idempotency-Key","1-1-1-1-1")).andExpect(status().isBadRequest());
        assertEquals(10,async.product(product).getStock());verify(publisher,never()).publish(any(OrderMessage.class));
    }
    @Test void userLimitReturns429AndRetryAfterWithoutReserving()throws Exception{
        seed(PREFIX+":write-user:1001",0);
        mvc.perform(post("/seckill/"+product).param("userId","1001").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After",org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
        assertEquals(10,async.product(product).getStock());assertEquals(0,count());verify(publisher,never()).publish(any(OrderMessage.class));
    }
    @Test void spoofedForwardedIpCannotBypassQueryLimit()throws Exception{
        String ip=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("127.0.0.1".getBytes(StandardCharsets.UTF_8)));
        seed(PREFIX+":read-ip:"+ip,0);
        for(String fake:List.of("1.2.3.4","5.6.7.8"))mvc.perform(get("/product/"+product).header("X-Forwarded-For",fake))
            .andExpect(status().isTooManyRequests());
        verify(async,never()).product(product);
    }
    @Test void globalWriteLimitProtectsAcrossUsers()throws Exception{
        seed(PREFIX+":write-global",0);
        for(int user=1;user<=3;user++)mvc.perform(post("/seckill/"+product).param("userId",Integer.toString(user))
            .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isTooManyRequests());
        assertEquals(10,async.product(product).getStock());verify(publisher,never()).publish(any(OrderMessage.class));
    }
    @Test void concurrentTokenConsumptionCannotExceedCapacity()throws Exception{
        String name=PREFIX+":atomic";seed(name,5);var bucket=new TokenBucketLimiter.Bucket(name,1,5);
        try(var pool=Executors.newFixedThreadPool(20)){
            var futures=new ArrayList<Future<Boolean>>();
            for(int n=0;n<100;n++)futures.add(pool.submit(()->{try{limiter.check(List.of(bucket));return true;}catch(RateLimitExceededException e){return false;}}));
            int accepted=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))accepted++;
            assertEquals(5,accepted);
        }
    }
    @Test void rejectionDoesNotSpendOtherBucketTokens(){
        String a=PREFIX+":first",b=PREFIX+":second";seed(a,3);seed(b,0);
        assertThrows(RateLimitExceededException.class,()->limiter.check(List.of(new TokenBucketLimiter.Bucket(a,1,3),new TokenBucketLimiter.Bucket(b,1,1))));
        assertEquals("3",redis.opsForHash().get(a,"tokens"));
    }
    @Test void bucketRefillsAndHasBoundedTtl()throws Exception{
        String name=PREFIX+":refill";var bucket=new TokenBucketLimiter.Bucket(name,1,1);
        limiter.check(List.of(bucket));assertThrows(RateLimitExceededException.class,()->limiter.check(List.of(bucket)));
        assertTrue(redis.getExpire(name)>0 && redis.getExpire(name)<=61);
        Thread.sleep(1100);assertDoesNotThrow(()->limiter.check(List.of(bucket)));
    }
    @Test void corruptLimiterStateFailsClosed()throws Exception{
        redis.opsForValue().set(PREFIX+":write-global","wrong-type");
        mvc.perform(post("/seckill/"+product).param("userId","1").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isServiceUnavailable());
        assertEquals(10,async.product(product).getStock());verify(publisher,never()).publish(any(OrderMessage.class));
    }
    @Test void uniqueRequestsStillCannotOversell()throws Exception{
        try(var pool=Executors.newFixedThreadPool(20)){
            var futures=new ArrayList<Future<Integer>>();
            for(int n=0;n<100;n++)futures.add(pool.submit(()->{try{return "QUEUED".equals(submit(1,UUID.randomUUID().toString()).status())?202:503;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            int accepted=0;for(var f:futures){int status=f.get(30,TimeUnit.SECONDS);if(status==202)accepted++;else assertEquals(409,status);}assertEquals(10,accepted);
        }
        await(()->count()==10);assertEquals(0,async.product(product).getStock());
    }
}
```

## perf/stage4.jmx

作用：JMeter 下单计划，每请求新 UUID，可配置每线程间隔。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
<hashTree>
<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Stage 4 protected async">
<boolProp name="TestPlan.functional_mode">false</boolProp>
<elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments"><collectionProp name="Arguments.arguments"/></elementProp>
</TestPlan><hashTree>
<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Purchase">
<stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
<elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController"><boolProp name="LoopController.continue_forever">false</boolProp><stringProp name="LoopController.loops">${__P(loops,10)}</stringProp></elementProp>
<stringProp name="ThreadGroup.num_threads">${__P(users,10)}</stringProp>
<stringProp name="ThreadGroup.ramp_time">${__P(ramp,1)}</stringProp>
<boolProp name="ThreadGroup.scheduler">false</boolProp>
</ThreadGroup><hashTree>
<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="Purchase one item">
<elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments"><elementProp name="userId" elementType="HTTPArgument"><boolProp name="HTTPArgument.always_encode">false</boolProp><stringProp name="Argument.name">userId</stringProp><stringProp name="Argument.value">${__counter(FALSE,)}</stringProp><stringProp name="Argument.metadata">=</stringProp></elementProp></collectionProp></elementProp>
<stringProp name="HTTPSampler.domain">${__P(host,localhost)}</stringProp>
<stringProp name="HTTPSampler.port">${__P(port,8081)}</stringProp>
<stringProp name="HTTPSampler.protocol">http</stringProp>
<stringProp name="HTTPSampler.path">/api/seckill/${__P(product,900001)}</stringProp>
<stringProp name="HTTPSampler.method">POST</stringProp>
<boolProp name="HTTPSampler.follow_redirects">false</boolProp>
<boolProp name="HTTPSampler.use_keepalive">true</boolProp>
<stringProp name="HTTPSampler.connect_timeout">3000</stringProp>
<stringProp name="HTTPSampler.response_timeout">10000</stringProp>
</HTTPSamplerProxy><hashTree>
<HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="Idempotency key"><collectionProp name="HeaderManager.headers"><elementProp name="Idempotency-Key" elementType="Header"><stringProp name="Header.name">Idempotency-Key</stringProp><stringProp name="Header.value">${__UUID()}</stringProp></elementProp></collectionProp></HeaderManager><hashTree/>
<ConstantTimer guiclass="ConstantTimerGui" testclass="ConstantTimer" testname="Optional pacing"><stringProp name="ConstantTimer.delay">${__P(delay,0)}</stringProp></ConstantTimer><hashTree/>
</hashTree>
</hashTree></hashTree></hashTree></jmeterTestPlan>
```

## perf/run_stage4.py

作用：隔离商品与应用实例，对比正常流量和过载，采集原始证据。

```python
"""Sequential same-machine JMeter comparison: stage3 vs protected stage4.
Run from the repository root: python perf/run_stage4.py --requests 3000
Creates dedicated products, queue and limiter namespace; preserves every experiment.
"""
import argparse,base64,csv,hashlib,json,os,platform,socket,subprocess,time,urllib.request
from collections import Counter
from pathlib import Path
import psutil
from redis_client import Redis

parser=argparse.ArgumentParser()
parser.add_argument('--requests',type=int,default=3000)
parser.add_argument('--port',type=int,default=18088)
parser.add_argument('--jar',default='target/seckill-system-stage4.jar')
args=parser.parse_args()
assert args.requests>0 and args.requests%100==0
out=Path('perf/results')/('stage4-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True)
config={}
for filename in ['src/main/resources/application.properties','application-local.properties']:
    p=Path(filename)
    if p.exists():config.update(dict(line.split('=',1) for line in p.read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#')))
env=os.environ.copy();env['MYSQL_PWD']=os.getenv('SPRING_DATASOURCE_PASSWORD',config.get('spring.datasource.password',''))
mysql=os.getenv('MYSQL_EXE',r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
def sql(query):
    r=subprocess.run([mysql,'-h','127.0.0.1','-u',config['spring.datasource.username'],'-N','-B','seckill','-e',query],env=env,capture_output=True,text=True,encoding='utf-8',timeout=15)
    if r.returncode:raise RuntimeError(r.stderr)
    return r.stdout.strip()
def status():
    return {a:int(b) for a,b in (line.split('\t') for line in sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running','Threads_connected')").splitlines())}
def redis_info():
    with Redis() as redis:return redis.info()
def launch(command,log):return subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
queue='seckill.benchmark.v4'
def depth():
    token=base64.b64encode((config['spring.rabbitmq.username']+':'+config['spring.rabbitmq.password']).encode()).decode()
    request=urllib.request.Request('http://127.0.0.1:15672/api/queues/seckill/'+queue,headers={'Authorization':'Basic '+token})
    with urllib.request.urlopen(request,timeout=3) as response:data=json.load(response)
    return {'queue_ready':data.get('messages_ready',0),'queue_unacked':data.get('messages_unacknowledged',0)}
jar=Path(args.jar)
jmeter=Path('target/tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar').resolve()
assert jar.exists() and jmeter.exists()
with socket.socket() as probe:probe.bind(('127.0.0.1',args.port))
metadata={'time':out.name,'platform':platform.platform(),'logical_cpus':psutil.cpu_count(),'memory_bytes':psutil.virtual_memory().total,'mysql_version':sql('SELECT VERSION()'),'redis_version':redis_info()['redis_version'],'rabbitmq_version':'4.0.5','jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'requests':args.requests,'repetitions':1,'queue':queue,'consumers':2,'prefetch':10,'pool_size':10,'ramp_seconds':1,'write_global_rate':200,'write_global_capacity':100,'write_ip_rate_override':200,'write_ip_capacity_override':100,'notes':'All clients use one local IP. IP threshold is raised to the global threshold for this comparison; application defaults remain 50/s and burst 20. Queue management metrics can lag 5s. Verified completion QPS includes JMeter shutdown and polling delay, so it is a conservative lower bound.'}
(out/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
(out/'runner-source.py').write_bytes(Path(__file__).read_bytes());(out/'test-plan.jmx').write_bytes(Path('perf/stage4.jmx').read_bytes())
results=[]
for mode in ['unprotected','protected']:
    app=None;load=None;app_log=(out/(mode+'-application.log')).open('w',encoding='utf-8')
    try:
        command=['java','-jar',str(jar),f'--server.port={args.port}','--spring.profiles.active='+('stage4' if mode=='protected' else 'stage3'),f'--seckill.mq.queue={queue}','--debug=false','--logging.level.root=INFO','--logging.level.org.springframework=INFO']
        if mode=='protected':command += [f'--seckill.limits.namespace=seckill:benchmark:{out.name}','--seckill.limits.write-ip-rate=200','--seckill.limits.write-ip-capacity=100']
        app=launch(command,app_log)
        for attempt in range(60):
            if app.poll() is not None:raise RuntimeError('Application exited')
            try:urllib.request.urlopen(f'http://localhost:{args.port}/api/test',timeout=1).close();break
            except OSError:time.sleep(1)
        else:raise RuntimeError('Readiness timeout')
        for label,users,count,delay in [('warmup',10,200,0),('normal',10,500,100),('overload',100,args.requests,0)]:
            name=mode+'-'+label
            product=int(sql(f"INSERT INTO product(name,stock,price) VALUES ('{out.name}-{name}',{count},6999.00);SELECT LAST_INSERT_ID();"))
            with (out/(name+'-initialize.log')).open('w',encoding='utf-8') as log:
                init=launch(['java','-jar',str(jar),'--spring.profiles.active=stage2','--spring.main.web-application-type=none',f'--seckill.initialize-product={product}','--debug=false'],log)
                try:code=init.wait(timeout=60)
                except subprocess.TimeoutExpired:init.terminate();init.wait(timeout=10);raise
                assert code==0
            watched={'app':psutil.Process(app.pid)}
            for process in psutil.process_iter(['name']):
                if (process.info['name'] or '').lower()=='mysqld.exe':watched['mysql-'+str(process.pid)]=process
            errors={}
            for name_cpu,process in list(watched.items()):
                try:process.cpu_percent()
                except psutil.Error as e:errors[name_cpu]=type(e).__name__;del watched[name_cpu]
            psutil.cpu_percent();before=status();rb=redis_info();start=time.monotonic();samples=[]
            def sample(phase):
                point={'time':time.time(),'phase':phase,'system_cpu':psutil.cpu_percent(),**status(),**depth()}
                for name_cpu,process in watched.items():
                    try:point[name_cpu+'_cpu']=process.cpu_percent()/psutil.cpu_count()
                    except psutil.Error as e:errors[name_cpu]=type(e).__name__
                point['orders']=int(sql(f'SELECT COUNT(*) FROM seckill_order WHERE product_id={product}'))
                samples.append(point)
                return point['orders']
            jtl=out/(name+'.jtl')
            with (out/(name+'-load.log')).open('w',encoding='utf-8') as log:
                load=launch(['java','-Xms256m','-Xmx512m','-jar',str(jmeter),'-n','-t','perf/stage4.jmx',f'-Jusers={users}',f'-Jloops={count//users}','-Jramp=1',f'-Jdelay={delay}',f'-Jproduct={product}',f'-Jport={args.port}','-l',str(jtl),'-j',str(out/(name+'-jmeter.log'))],log)
                while load.poll() is None:time.sleep(.5);sample('load')
                if load.returncode:raise RuntimeError('JMeter failed')
            rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')));codes=Counter(row['responseCode'] for row in rows);accepted=codes['202']
            end_load=time.monotonic();deadline=end_load+120
            while True:
                orders=sample('drain')
                with Redis() as redis:pending=int(redis.call('HLEN',f'product_pending_{product}'));remaining=int(redis.call('GET',f'product_stock_{product}'))
                if orders==accepted and pending==0:break
                if time.monotonic()>deadline:raise RuntimeError('Consumer drain timeout')
                time.sleep(.5)
            verified=time.time();monitor_seconds=time.monotonic()-start;drain_wait=time.monotonic()-end_load
            after=status();ra=redis_info();first=min(int(row['timeStamp']) for row in rows)/1000
            seconds=(max(int(row['timeStamp'])+int(row['elapsed']) for row in rows)/1000)-first
            times=[int(row['elapsed']) for row in rows];accepted_times=[int(row['elapsed']) for row in rows if row['responseCode']=='202']
            result={'mode':mode,'case':label,'users':users,'requests':len(rows),'delay_ms':delay,'product':product,'initial_stock':count,'remaining_stock':remaining,'orders':orders,'pending':pending,'codes':dict(codes),'seconds':seconds,'http_qps':len(rows)/seconds,'accepted_qps':accepted/seconds,'avg_ms':sum(times)/len(times),'max_ms':max(times),'accepted_avg_ms':sum(accepted_times)/len(accepted_times) if accepted_times else None,'verified_completion_qps_lower_bound':orders/(verified-first),'drain_wait_after_jmeter_seconds':drain_wait,'samples':samples,'cpu_errors':errors,'status_before':before,'status_after':after,'monitor_seconds':monitor_seconds,'redis_cpu_seconds':sum(float(ra[k])-float(rb[k]) for k in ['used_cpu_sys','used_cpu_user']),'redis_commands_delta':int(ra['total_commands_processed'])-int(rb['total_commands_processed'])}
            results.append(result);(out/'metrics.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
            assert len(rows)==count and remaining+orders==count and pending==0
            assert accepted+codes['429']==count and (mode=='protected' or codes['429']==0)
            if label=='normal':assert codes['429']==0,'Normal traffic should not be throttled'
            if mode=='protected':assert accepted<=100+200*(seconds+.1),'Token bucket admission bound exceeded'
            print(f"{name}: HTTP QPS={result['http_qps']:.2f}, accepted QPS={result['accepted_qps']:.2f}, avg={result['avg_ms']:.2f}ms, codes={dict(codes)}, final orders={orders}, drain={drain_wait:.2f}s",flush=True)
    finally:
        for process in [load,app]:
            if process is not None and process.poll() is None:process.terminate();process.wait(timeout=20)
        app_log.close()
print('Evidence directory: '+str(out),flush=True)
```

## perf/report_stage4.py

作用：读取原始指标生成报告，不把 429 计为成功。

```python
"""Generate a report strictly from a completed stage-four experiment."""
import json,statistics,sys
from pathlib import Path
run=Path(sys.argv[1]);rows=json.loads((run/'metrics.json').read_text());env=json.loads((run/'environment.json').read_text())
assert len(rows)==6, 'Incomplete experiment: expected both modes and all three cases'
def avg(values):return statistics.mean(values) if values else 0
lines=['# 阶段四压测报告','', '阶段：入口限流、接口防刷、请求幂等。比较同一阶段四 JAR 的 stage3 无保护模式和 stage4 保护模式。','',f"测试环境：{env['platform']}，{env['logical_cpus']} 逻辑核、{env['memory_bytes']/1024**3:.1f} GiB；Java 21、Spring Boot 3.3.5、MySQL {env['mysql_version']}、Redis {env['redis_version']}、RabbitMQ {env['rabbitmq_version']}。JMeter 5.6.3、应用和 MySQL 同机；Redis/RabbitMQ 位于 WSL。",'',f"测试环境参数：连接池 {env['pool_size']}、消费者 {env['consumers']}、prefetch {env['prefetch']}、ramp {env['ramp_seconds']} 秒、仅一轮。全局写入 rate={env['write_global_rate']}/s、capacity={env['write_global_capacity']}；因压测同源 IP，临时提升 IP 配额至 {env['write_ip_rate_override']}/s、capacity={env['write_ip_capacity_override']}；实际应用默认 IP 配额为 50/s、capacity=20。",'',f"并发用户/请求数量：每模式预热 10 并发 200 次；正常流量 10 并发 500 次、每线程额外等待 100ms；过载 100 并发 {env['requests']} 次、无额外等待。每请求 userId 和 UUID 键不同。两个模式顺序执行。",'',f"原始证据：`{run.as_posix()}`。JAR SHA256：`{env['jar_sha256']}`。包含原始 JTL、JMeter/application 日志、CPU/MySQL/队列时序、脚本和测试计划快照。",'','## 请求与业务结果','', '| 模式/场景 | 并发 | 请求 | 总 HTTP QPS | 受理 QPS | 平均 ms | 最大 ms | 受理平均 ms | 202 成功受理 | 429 拒绝 | 其他失败 | 最终订单 | 剩余库存 |','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
 c=r['codes'];other=sum(v for k,v in c.items() if k not in ['202','429']);accepted_avg='未采集' if r['accepted_avg_ms'] is None else f"{r['accepted_avg_ms']:.2f}"
 lines.append(f"| {r['mode']}/{r['case']} | {r['users']} | {r['requests']} | {r['http_qps']:.2f} | {r['accepted_qps']:.2f} | {r['avg_ms']:.2f} | {r['max_ms']} | {accepted_avg} | {c.get('202',0)} | {c.get('429',0)} | {other} | {r['orders']} | {r['remaining_stock']} |")
lines+=['','成功请求指 HTTP 202 被 MQ 接收；成交必须以最终订单为准。429 在 JMeter 中计入失败请求，但这里单独列为预期主动拒绝；总非成功请求 = 429 + 其他失败。两组初始库存都等于请求量，没有人为用售罄掩盖限流效果。正常组预期不拒绝，过载组预期出现 429。','','总 HTTP QPS 包含快速返回的 429；受理 QPS = 202 数量 / 请求窗口。令牌桶允许最初的突发，短窗口受理 QPS 可超过 rate，须检查 capacity + rate × 窗口时长。HTTP 响应延迟不是最终订单端到端延迟。本轮未记录逐单最终延迟。','','## 数据库、队列和资源','', '| 模式/场景 | MySQL Questions 增量 | 行锁等待次数/ms | MySQL 平均 CPU% | 应用平均 CPU% | 系统平均/峰值 CPU% | Redis平均CPU% | 观察队列峰值 | JMeter结束后核验等待秒 | 完成 QPS 下界 |','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
 samples=r['samples'];system=[s['system_cpu'] for s in samples];mysql=[v for s in samples for k,v in s.items() if k.startswith('mysql-') and k.endswith('_cpu')];a=r['status_after'];b=r['status_before'];peak=max(s['queue_ready']+s['queue_unacked'] for s in samples)
 lines.append(f"| {r['mode']}/{r['case']} | {a['Questions']-b['Questions']} | {a['Innodb_row_lock_waits']-b['Innodb_row_lock_waits']}/{a['Innodb_row_lock_time']-b['Innodb_row_lock_time']} | {avg(mysql):.2f} | {avg([s.get('app_cpu',0) for s in samples]):.2f} | {avg(system):.2f}/{max(system):.2f} | {r['redis_cpu_seconds']/r['monitor_seconds']/env['logical_cpus']*100:.2f} | {peak} | {r['drain_wait_after_jmeter_seconds']:.2f} | {r['verified_completion_qps_lower_bound']:.2f} |")
lines+=['','CPU 为整机归一化百分比；进程 CPU 除以逻辑核数。MySQL Questions 是全局增量，包含监控查询和其他同机活动；不能等同于业务写入次数。采样含 JMeter 启停及队列耗尽阶段。RabbitMQ 进程 CPU 未单独采集。队列管理 API 有约 5 秒统计延迟，观察峰值只是下界。完成 QPS 下界的时间窗口延伸至脚本确认全部订单完成，含 JMeter 退出和轮询延迟，不是精确消费者吞吐。','','## 限流前后比较','']
old=next(r for r in rows if r['mode']=='unprotected' and r['case']=='overload');new=next(r for r in rows if r['mode']=='protected' and r['case']=='overload')
old_peak=max(s['queue_ready']+s['queue_unacked'] for s in old['samples']);new_peak=max(s['queue_ready']+s['queue_unacked'] for s in new['samples'])
lines += [f"- 同为 {old['requests']} 次过载请求：无保护受理 {old['codes'].get('202',0)}，有保护受理 {new['codes'].get('202',0)}、主动拒绝 {new['codes'].get('429',0)}。",f"- 观察队列峰值：{old_peak} → {new_peak}；JMeter 结束后到全部订单/pending 核验完成等待：{old['drain_wait_after_jmeter_seconds']:.2f}s → {new['drain_wait_after_jmeter_seconds']:.2f}s。",f"- 本轮令牌桶受理界限：受理 {new['codes'].get('202',0)} ≤ 100 + 200 × ({new['seconds']:.3f}s + 0.1s测量余量)。",'- 每个案例均验证最终订单数等于受理数、剩余库存 + 订单 = 初始库存、pending=0。正常流量组未出现限流拒绝。','', '## 幂等和接口保护验证','', '独立真实集成测试验证 100 次相同业务请求只发布一次消息、最终只建一单；相同键换商品冲突；不同用户可使用相同键；ticket 过期后仍可返回原数据库订单；发布超时和重试不再次扣库存/发消息；缺失键返回 400；全局/用户/IP 查询桶拒绝；伪造 X-Forwarded-For 无法绕过来源限制；令牌并发扣减和补充正确；损坏限流状态停止放行。42 项全量测试通过，详见 stage4-test-results.txt。', '', '## 当前问题与结论边界','', '本轮实验验证入口主动拒绝、减少进入 MQ 的请求和最终库存一致性。限流是在过载时牺牲部分请求受理率保护下游，不是数据库写入能力提升。正常请求增加 Redis 检查可能变慢；429 很快返回可能抬高总 QPS。', '', '本实验只有一轮、时间短、JMeter 闭环发送且与服务同机；429 更快返回会改变后续请求发送节奏，两组不是严格相同的开环到达率。不能据此证明长期稳定性、最大容量或生产 SLA；还需要长时间持续超载、外部压测机和多轮重复。userId 尚未认证、永久幂等 marker 需要归档、Redis/MQ 无跨系统事务，均未在本阶段消除。']
Path('docs/stage4-report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Generated docs/stage4-report.md')
```
