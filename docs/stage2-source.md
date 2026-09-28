# 阶段二完整文件代码

文件作用、运行步骤与核心原理见 [stage2.md](stage2.md)。不包含本机密码配置。历史阶段一快照保持独立。

## pom.xml

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

## src/main/java/com/ddk/seckill/controller/SeckillController.java

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

## src/main/java/com/ddk/seckill/entity/Order.java

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

## src/main/java/com/ddk/seckill/entity/Product.java

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

```java
package com.ddk.seckill.entity;

public record StockBaseline(long productId, int initialStock, long initialOrderQuantity) { }
```

## src/main/java/com/ddk/seckill/mapper/OrderMapper.java

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

## src/main/java/com/ddk/seckill/service/RedisSeckillService.java

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

## src/test/java/com/ddk/seckill/RedisFailureTest.java

```java
package com.ddk.seckill;

import com.ddk.seckill.entity.Product;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.service.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RedisFailureTest {
    private Product product() {
        Product p = new Product(); p.setId(1L); p.setStock(10); p.setPrice(new BigDecimal("6999.00")); return p;
    }
    @Test void redisUnavailableNeverCreatesOrder() {
        var stock=mock(RedisStockService.class); var orders=mock(OrderMapper.class);
        var service=new RedisSeckillService(stock,orders,mock(PlatformTransactionManager.class));
        when(stock.get(1)).thenThrow(new RedisConnectionFailureException("offline"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.purchase(1,1)).getStatusCode().value());
        verifyNoInteractions(orders);
    }
    @Test void reservationTimeoutIsNeverBlindlyCompensated() {
        var stock=mock(RedisStockService.class); var orders=mock(OrderMapper.class);
        var service=new RedisSeckillService(stock,orders,mock(PlatformTransactionManager.class));
        when(stock.get(1)).thenReturn(product());
        doThrow(new RedisConnectionFailureException("uncertain timeout")).when(stock).reserve(eq(1L),anyString());
        assertThrows(ResponseStatusException.class,()->service.purchase(1,1));
        verify(stock,never()).release(anyLong(),anyString());
        verifyNoInteractions(orders);
    }
    @Test void unknownCommitOutcomeNeverRestoresInventory() {
        var stock=mock(RedisStockService.class); var orders=mock(OrderMapper.class);
        when(stock.get(1)).thenReturn(product()); when(orders.insert(any())).thenReturn(1);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object tx,TransactionDefinition definition){}
            protected void doCommit(DefaultTransactionStatus status){throw new TransactionSystemException("Commit response lost");}
            protected void doRollback(DefaultTransactionStatus status){}
        };
        var service=new RedisSeckillService(stock,orders,manager);
        assertThrows(ResponseStatusException.class,()->service.purchase(1,1));
        verify(stock,never()).release(anyLong(),anyString());
        verify(stock,never()).complete(anyLong(),anyString());
    }
}
```

## src/test/java/com/ddk/seckill/RedisSeckillIntegrationTest.java

```java
package com.ddk.seckill;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.service.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties = {"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:db/stage2.sql"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("stage2")
@EnabledIfEnvironmentVariable(named = "SECKILL_REDIS_TEST", matches = "true")
class RedisSeckillIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @SpyBean RedisStockService stock;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired StockInitializer initializer;
    @Autowired SeckillOperations service;
    @SpyBean OrderMapper orders;
    long id;

    @BeforeEach void setup() {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement("INSERT INTO product(name,stock,price) VALUES ('stage2-test',10,6999.00)", java.sql.Statement.RETURN_GENERATED_KEYS), key);
        id = key.getKey().longValue();
        initializer.initialize(id);
    }
    @AfterEach void cleanup() {
        if (id > 0) {
            redis.delete(RedisStockService.keys(id));
            jdbc.update("DELETE FROM seckill_order WHERE product_id=?", id);
            jdbc.update("DELETE FROM seckill_stock_baseline WHERE product_id=?", id);
            jdbc.update("DELETE FROM product WHERE id=?", id);
        }
    }
    @Test void purchaseUsesRedisAndRepeatedInitializationNeverRefills() {
        Order order = service.purchase(id, 1);
        assertNotNull(order.getId());
        assertEquals(9, initializer.initialize(id).getStock());
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, id));
        assertEquals(id, service.getOrder(order.getId()).getProductId());
        assertEquals(0, redis.opsForHash().size(RedisStockService.keys(id).get(2)));
    }
    @Test void concurrentRequestsCannotOversell() throws Exception {
        try (var pool = Executors.newFixedThreadPool(20)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int i=0;i<100;i++) futures.add(pool.submit(() -> {
                start.await();
                try { service.purchase(id, 1001); return 201; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            }));
            start.countDown();
            int success=0;
            for (var future:futures) {
                int code=future.get(30,TimeUnit.SECONDS);
                if(code==201)success++;else assertEquals(409,code);
            }
            assertEquals(10,success);
        }
        assertEquals(0, service.getProduct(id).getStock());
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,id));
    }
    @Test void confirmedRollbackRestoresInventory() {
        doThrow(new IllegalStateException("Injected insert failure")).when(orders).insert(any(Order.class));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.purchase(id,1)).getStatusCode().value());
        assertEquals(10,stock.get(id).getStock());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,id));
    }
    @Test void repeatedCompensationOnlyRestoresOnce() {
        stock.reserve(id,"test-reservation");
        stock.release(id,"test-reservation");
        stock.release(id,"test-reservation");
        assertEquals(10,stock.get(id).getStock());
    }
    @Test void missingStockFailsClosedAndCannotBeReinitialized() {
        redis.delete(RedisStockService.keys(id).get(0));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.purchase(id,1)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->initializer.initialize(id));
        verify(orders,never()).insert(any(Order.class));
    }
    @Test void invalidPendingKeyDoesNotDeductStock() {
        redis.opsForValue().set(RedisStockService.keys(id).get(2),"wrong-type");
        assertThrows(ResponseStatusException.class,()->stock.reserve(id,"token"));
        assertEquals(10,stock.get(id).getStock());
    }

    @Test void committedOrderRemainsSuccessfulWhenCleanupFails() {
        doThrow(new IllegalStateException("Injected cleanup failure")).when(stock).complete(eq(id), anyString());
        Order order = service.purchase(id,1);
        assertNotNull(order.getId());
        assertEquals(9,stock.get(id).getStock());
        assertEquals(1,redis.opsForHash().size(RedisStockService.keys(id).get(2)));
        assertNotNull(service.getOrder(order.getId()));
    }
    @Test void compensationFailureKeepsStockReserved() {
        doThrow(new IllegalStateException("Injected insert failure")).when(orders).insert(any(Order.class));
        doThrow(new IllegalStateException("Injected Redis failure")).when(stock).release(eq(id), anyString());
        assertThrows(ResponseStatusException.class,()->service.purchase(id,1));
        assertEquals(9,stock.get(id).getStock());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?",Integer.class,id));
    }
    @Test void redisProfileHttpFlow() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/"+id).param("userId","1001"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/product/"+id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.stock").value(9));
    }
}
```

## src/test/java/com/ddk/seckill/SeckillMySqlTest.java

```java
package com.ddk.seckill;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.service.SeckillService;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SECKILL_MYSQL_TEST", matches = "true")
class SeckillMySqlTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired SeckillService service;
    @SpyBean OrderMapper orders;
    private long productId;

    @BeforeEach void setup() {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                "INSERT INTO product(name,stock,price) VALUES ('stage1-test',10,6999.00)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            return statement;
        }, key);
        productId = key.getKey().longValue();
    }
    @AfterEach void cleanup() {
        if (productId > 0) {
            jdbc.update("DELETE FROM seckill_order WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }

    @Test void httpFlowAndValidation() throws Exception {
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/product/" + productId);
        mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/" + productId).param("userId", "1001"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/order/" + id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.productId").value(productId));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/" + productId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/" + productId).param("userId", "0"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/product/9223372036854775807"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        jdbc.update("UPDATE product SET stock=0 WHERE id=?", productId);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/seckill/" + productId).param("userId", "1002"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }
    @Test void concurrentPurchasesNeverOversell() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 100; i++) {
                final long user = i + 1;
                futures.add(executor.submit(() -> {
                    start.await();
                    try { service.purchase(productId, user); return 201; }
                    catch (ResponseStatusException e) { return e.getStatusCode().value(); }
                }));
            }
            start.countDown();
            int success = 0;
            for (var future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 201) success++;
                else assertEquals(409, status);
            }
            assertEquals(10, success);
        }
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, productId));
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?", Integer.class, productId));
    }
    @Test void failedOrderInsertRollsBackStock() {
        doThrow(new IllegalStateException("Injected database failure")).when(orders).insert(any(Order.class));
        assertThrows(IllegalStateException.class, () -> service.purchase(productId, 1001));
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, productId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id=?", Integer.class, productId));
    }
}
```

## src/test/java/com/ddk/seckill/SeckillServiceTest.java

```java
package com.ddk.seckill;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.entity.Product;
import com.ddk.seckill.mapper.OrderMapper;
import com.ddk.seckill.mapper.ProductMapper;
import com.ddk.seckill.service.SeckillService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeckillServiceTest {
    private ProductMapper products;
    private OrderMapper orders;
    private SeckillService service;

    @BeforeEach void setup() {
        products = mock(ProductMapper.class);
        orders = mock(OrderMapper.class);
        service = new SeckillService(products, orders);
    }
    @Test void successfulPurchaseKeepsPriceSnapshot() {
        Product p = new Product(); p.setPrice(new BigDecimal("6999.00"));
        when(products.decreaseStock(1)).thenReturn(1);
        when(products.findById(1)).thenReturn(p);
        when(orders.insert(any())).thenAnswer(inv -> {
            Order order = inv.getArgument(0); order.setId(42L); return 1;
        });
        Order result = service.purchase(1, 1001);
        assertEquals(42L, result.getId());
        assertEquals(new BigDecimal("6999.00"), result.getPrice());
        assertEquals(1, result.getQuantity());
    }
    @Test void soldOutCreatesNoOrder() {
        when(products.findById(1)).thenReturn(new Product());
        var ex = assertThrows(ResponseStatusException.class, () -> service.purchase(1, 1001));
        assertEquals(409, ex.getStatusCode().value());
        verifyNoInteractions(orders);
    }
    @Test void missingProductReturns404() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.purchase(1, 1001));
        assertEquals(404, ex.getStatusCode().value());
        verifyNoInteractions(orders);
    }
    @Test void invalidUserDoesNotTouchDatabase() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.purchase(1, 0));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(products, orders);
    }
}
```

## src/test/java/com/example/demo/DemoApplicationTests.java

```java
package com.example.demo;

import com.ddk.seckill.SeckillApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SeckillApplication.class)
@EnabledIfEnvironmentVariable(named = "SECKILL_MYSQL_TEST", matches = "true")
class DemoApplicationTests {
    @Test void contextLoads() { }
}
```

## src/main/resources/application-stage2.properties

```properties
seckill.mode=redis
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.database=${REDIS_DATABASE:0}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.connect-timeout=2s
spring.data.redis.timeout=2s
```

## src/main/resources/application.properties

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

## src/main/resources/lua/initialize.lua

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

```lua
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return nil end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not string.match(raw, '^%d+$') or not stock or stock > 2147483647 then return nil end
-- Preserve JSON numbers (IDs and prices) without a Lua floating-point round trip.
return raw .. '\n' .. redis.call('GET', KEYS[2])
```

## src/main/resources/lua/release.lua

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

## scripts/start-redis.ps1

```powershell
$ErrorActionPreference = 'Stop'
# For the local Ubuntu instance configured during stage2 setup.
$redisClient = [System.Net.Sockets.TcpClient]::new()
try {
    $redisClient.Connect('127.0.0.1', 6379)
    $redisStream = $redisClient.GetStream()
    $redisStream.ReadTimeout = 1500
    $pingBytes = [System.Text.Encoding]::ASCII.GetBytes("PING`r`n")
    $redisStream.Write($pingBytes, 0, $pingBytes.Length)
    $replyBytes = New-Object byte[] 128
    $readCount = $redisStream.Read($replyBytes, 0, $replyBytes.Length)
    if ([System.Text.Encoding]::ASCII.GetString($replyBytes, 0, $readCount).StartsWith('+PONG')) {
        Write-Host 'Redis is already responding on 127.0.0.1:6379.'
        return
    }
} catch {
    # Redis is not reachable; continue with the configured local startup.
} finally {
    $redisClient.Dispose()
}
wsl -d Ubuntu -u root -- service redis-server stop
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the background Redis service.' }
Start-Process -FilePath 'wsl.exe' -ArgumentList '-d','Ubuntu','-u','redis','--exec','/usr/bin/redis-server','/etc/redis/redis.conf','--daemonize','no','--supervised','no' -WindowStyle Hidden
Write-Host 'Redis process started. Verify with: wsl -d Ubuntu -u root -- redis-cli ping'
```

## perf/redis_client.py

```python
"""Minimal local RESP2 client for experiment monitoring; no third-party Redis dependency."""
import os
import socket

class Redis:
    def __enter__(self):
        self.socket = socket.create_connection((os.getenv('REDIS_HOST','127.0.0.1'),int(os.getenv('REDIS_PORT','6379'))),3)
        self.stream = self.socket.makefile('rb')
        if os.getenv('REDIS_PASSWORD'): self.call('AUTH',os.environ['REDIS_PASSWORD'])
        self.call('SELECT',os.getenv('REDIS_DATABASE','0'))
        return self
    def __exit__(self,*args):
        self.stream.close();self.socket.close()
    def call(self,*args):
        values=[str(x).encode('utf-8') for x in args]
        self.socket.sendall(b'*'+str(len(values)).encode()+b'\r\n'+b''.join(b'$'+str(len(v)).encode()+b'\r\n'+v+b'\r\n' for v in values))
        return self.read()
    def read(self):
        prefix=self.stream.read(1);line=self.stream.readline().rstrip(b'\r\n')
        if prefix==b'+':return line.decode()
        if prefix==b'-':raise RuntimeError(line.decode())
        if prefix==b':':return int(line)
        if prefix==b'$':
            size=int(line)
            if size<0:return None
            data=self.stream.read(size);self.stream.read(2);return data.decode('utf-8')
        if prefix==b'*':return [self.read() for _ in range(int(line))]
        raise RuntimeError('Unexpected Redis response')
    def info(self):
        return dict(line.split(':',1) for line in self.call('INFO').splitlines() if ':' in line and not line.startswith('#'))
```

## perf/run_stage2.py

```python
"""Matched exploratory benchmarks. Run from project root: python perf/run_stage2.py
Runs MySQL and Redis modes sequentially, retains products and all experiment evidence.
Requires psutil, built application JAR and JMeter 5.6.3 under target/tools.
"""
import argparse,csv,hashlib,json,os,platform,socket,subprocess,time,urllib.request
from collections import Counter
from pathlib import Path
import psutil
from redis_client import Redis

parser=argparse.ArgumentParser()
parser.add_argument('--requests',type=int,default=10000)
parser.add_argument('--port',type=int,default=18082)
parser.add_argument('--modes',nargs='+',choices=['mysql','redis'],default=['mysql','redis'])
parser.add_argument('--cases',nargs='+',choices=['warmup','correctness','users-1','users-10','users-100'],default=['warmup','correctness','users-1','users-10','users-100'])
args=parser.parse_args()
assert args.requests>0 and args.requests%100==0
root=Path.cwd();out=root/'perf/results'/('stage2-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True)
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
def redis_info():
    with Redis() as redis:return redis.info()
def redis_cpu(info):return float(info['used_cpu_sys'])+float(info['used_cpu_user'])
def launch(command,log):
    return subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
jar=Path('target/seckill-system-0.0.1-SNAPSHOT.jar')
jmeter=Path('target/tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar').resolve()
assert jar.exists() and jmeter.exists()
with socket.socket() as probe:probe.bind(('127.0.0.1',args.port))
metadata={'time':out.name,'platform':platform.platform(),'logical_cpus':psutil.cpu_count(),'memory_bytes':psutil.virtual_memory().total,'mysql_version':sql('SELECT VERSION()'),'redis_version':redis_info()['redis_version'],'requests_per_throughput_case':args.requests,'pool_size':10,'ramp_seconds':1,'mysql_tls':'REQUIRED','jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'modes_order':args.modes,'cases':args.cases,'repetitions':1,'notes':'Same machine; short exploratory samples; SQL and CPU sampling windows include process startup/teardown.'}
(out/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
results=[]
(out/'runner-source.py').write_bytes(Path(__file__).read_bytes())
for mode in args.modes:
    app=None;load=None
    app_log=(out/(mode+'-application.log')).open('w',encoding='utf-8')
    try:
        cmd=['java','-jar',str(jar),f'--server.port={args.port}','--debug=false','--logging.level.root=INFO','--logging.level.org.springframework=INFO',f'--seckill.mode={mode}']
        if mode=='redis':cmd+=['--spring.profiles.active=stage2']
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
            if mode=='redis':
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
                    sample={'time':time.time(),'system_cpu':psutil.cpu_percent(),**status()}
                    for key,process in watched.items():
                        try:sample[key+'_cpu']=process.cpu_percent()/psutil.cpu_count()
                        except psutil.Error as e:errors[key]=type(e).__name__
                    samples.append(sample)
                if load.returncode:raise RuntimeError('JMeter failed: '+name)
            duration_monitor=time.monotonic()-start;after=status();ra=redis_info()
            rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')));codes=Counter(r['responseCode'] for r in rows)
            seconds=(max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)-min(int(r['timeStamp']) for r in rows))/1000
            db_stock,orders=map(int,sql(f'SELECT stock,(SELECT COUNT(*) FROM seckill_order WHERE product_id={product}) FROM product WHERE id={product}').split('\t'))
            pending=0;remaining=db_stock
            if mode=='redis':
                with Redis() as redis:
                    remaining=int(redis.call('GET',f'product_stock_{product}'));pending=redis.call('HLEN',f'product_pending_{product}')
            result={'mode':mode,'name':label,'users':users,'requests':len(rows),'product':product,'initial_stock':initial,'remaining_stock':remaining,'mysql_stock':db_stock,'orders':orders,'pending':pending,'seconds':seconds,'qps':len(rows)/seconds,'order_qps':codes['201']/seconds,'avg_ms':sum(int(r['elapsed']) for r in rows)/len(rows),'max_ms':max(int(r['elapsed']) for r in rows),'codes':dict(codes),'status_before':before,'status_after':after,'samples':samples,'cpu_errors':errors,'monitor_seconds':duration_monitor,'redis_cpu_seconds':redis_cpu(ra)-redis_cpu(rb),'redis_commands_delta':int(ra['total_commands_processed'])-int(rb['total_commands_processed'])}
            results.append(result);(out/'metrics.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
            assert len(rows)==count and remaining+orders==initial and remaining>=0 and pending==0
            assert orders==codes['201']==min(count,initial) and codes['409']==max(0,count-initial)
            print(f"{name}: QPS={result['qps']:.2f}, order QPS={result['order_qps']:.2f}, avg={result['avg_ms']:.2f}ms, max={result['max_ms']}ms, codes={dict(codes)}",flush=True)
    finally:
        for process in [load,app]:
            if process is not None and process.poll() is None:process.terminate();process.wait(timeout=20)
        app_log.close()
print('Evidence directory: '+str(out),flush=True)
```

## perf/report_stage2.py

```python
"""Generate a report from retained evidence. No network or database writes.
python perf/report_stage2.py PRIMARY_DIR --rerun RERUN_DIR
"""
import argparse,json,hashlib
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('primary',type=Path)
parser.add_argument('--rerun',type=Path)
args=parser.parse_args()
primary=json.loads((args.primary/'metrics.json').read_text(encoding='utf-8'))
env=json.loads((args.primary/'environment.json').read_text(encoding='utf-8'))
selected={(r['mode'],r['name']):r for r in primary}
all_rows=list(primary)
if args.rerun:
    rerun=json.loads((args.rerun/'metrics.json').read_text(encoding='utf-8'))
    replacement=next(r for r in rerun if r['mode']=='redis' and r['name']=='users-1')
    selected[('redis','users-1')]=replacement
    all_rows+=rerun

def cpu(row,prefix):
    values=[]
    for sample in row['samples']:
        if prefix=='mysql':
            matches=[v for k,v in sample.items() if k.startswith('mysql-') and k.endswith('_cpu')]
            if matches:values.append(sum(matches))
        elif prefix+'_cpu' in sample:values.append(sample[prefix+'_cpu'])
    return f'{sum(values)/len(values):.2f}/{max(values):.2f}' if values else '未测'

lines=['# 阶段二压测报告','','阶段：Redis + Lua 预扣库存、同步 MySQL 下单；同时重测 MySQL 对照组。','',f"测试环境：{env['platform']}；32 逻辑核，约 {env['memory_bytes']/1024**3:.1f} GiB 内存；Java 21、Boot 3.3.5、MySQL {env['mysql_version']}、WSL 2 Ubuntu Redis {env['redis_version']}、JMeter 5.6.3。",'', '同机顺序施压，连接池 10，TLS REQUIRED，ramp-up 1 秒。每组预热 200 次；正确性场景 100 并发、1000 请求、100 库存；吞吐场景分别 1/10/100 并发、各 10000 请求与库存。模式顺序 MySQL → Redis；每档单轮。', '', '原始目录：`'+args.primary.as_posix()+'`。']
if args.rerun:
    lines+=['','复测目录：`'+args.rerun.as_posix()+'`。首轮 Redis 单并发与商品 1 初始化重叠，被排除出主要比较；其他场景结束后重新预热并单独复测。旧数据保留在原目录和 experiment-notes.txt，未覆盖或删除。复测还有一组正确性测试，主要表格仅替换 users-1，其他复测结果保留在原始数据。']
lines+=['','## 请求结果','','|模式|场景|请求数|时长 s|HTTP QPS|下单 QPS|平均 ms|最大 ms|成功 201|售罄 409|其他失败|','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
rows=[]
for label in ['correctness','users-1','users-10','users-100']:
    for mode in ['mysql','redis']:
        r=selected[(mode,label)];rows.append(r);codes=r['codes'];other=r['requests']-codes.get('201',0)-codes.get('409',0)
        lines.append(f"|{mode}|{label}|{r['requests']}|{r['seconds']:.2f}|{r['qps']:.2f}|{r['order_qps']:.2f}|{r['avg_ms']:.2f}|{r['max_ms']}|{codes.get('201',0)}|{codes.get('409',0)}|{other}|")
lines+=['','QPS 测量窗口为首个样本开始到最后一个样本结束；与 JMeter 控制台含额外收尾时间的 summary 数值可能略有差异。409 单列为业务拒绝，不能用其吞吐代替成功下单吞吐。','','## 数据库压力与 CPU','','|模式/场景|锁等待次数增量|锁等待累计 ms 增量|Questions 增量|Threads_running 峰值|系统 CPU 均/峰 %|应用 CPU 均/峰 %|MySQL CPU 均/峰 %|Redis CPU 均 %|','|---|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
    a=r['status_after'];b=r['status_before'];redis_pct=r['redis_cpu_seconds']/r['monitor_seconds']/env['logical_cpus']*100
    lines.append(f"|{r['mode']}/{r['name']}|{a['Innodb_row_lock_waits']-b['Innodb_row_lock_waits']}|{a['Innodb_row_lock_time']-b['Innodb_row_lock_time']}|{a['Questions']-b['Questions']}|{max(s['Threads_running'] for s in r['samples'])}|{cpu(r,'system')}|{cpu(r,'app')}|{cpu(r,'mysql')}|{redis_pct:.3f}|")
lines+=['','CPU 按整机 32 逻辑核归一化，Windows 进程与系统每秒附近采样，实际间隔含采样查询开销。Redis CPU 来自 INFO 中用户态/内核态累计秒差除以监控墙钟窗口与整机逻辑核数；没有采 Redis 瞬时峰值。MySQL 是本机 mysqld 进程合计，计数器是全局值，包含监控查询和可能的其他连接。监控窗口包括 JMeter 启动/收尾，不与 HTTP 样本窗口严格一致；JMeter 进程缺样/退出信息记录于 cpu_errors，不以零替代。','','## 同条件比较']
for n in [1,10,100]:
    m=selected[('mysql',f'users-{n}')];r=selected[('redis',f'users-{n}')]
    lines+=['',f"- {n} 并发：Redis 下单 QPS 为 MySQL 的 {r['order_qps']/m['order_qps']:.2f} 倍（变化 {(r['order_qps']/m['order_qps']-1)*100:+.2f}%）；平均响应 {m['avg_ms']:.2f} → {r['avg_ms']:.2f} ms。"]
lines+=['','Redis 模式不再更新 product 热点库存行，售罄请求在 Redis 返回；数据库商品行锁竞争减少，订单仍同步写入。单并发增加了 Redis 网络往返、脚本和补偿标记维护，可能更慢。不是所有场景都会因为引入 Redis 而更快。100 并发下 Questions 总量约从 8 万降到 5 万、行锁等待从 9999 降到 0，但 MySQL CPU 平均占比从 2.65% 升到 5.45%；因为单位时间处理的订单更多，不能宣称所有数据库压力指标都下降。','','历史阶段一（2026-09-26）下单 QPS 为 256.61 / 375.21 / 313.11，对应 1/10/100 并发。旧实验没有本次 TLS/WSL 环境，保留作历史背景，主要结论使用本次匹配对照组。','','## 一致性、失败与证据']
lines+=['',f"包含预热和复测的全部留存请求：{sum(r['requests'] for r in all_rows)}；201：{sum(r['codes'].get('201',0) for r in all_rows)}；409：{sum(r['codes'].get('409',0) for r in all_rows)}；其他失败：{sum(r['requests']-r['codes'].get('201',0)-r['codes'].get('409',0) for r in all_rows)}。"]
for r in all_rows:
    assert r['remaining_stock']+r['orders']==r['initial_stock'] and r['pending']==0
lines+=['','每组校验初始库存=剩余库存+订单数，成功响应数=订单数，Redis 组末尾 pending=0，库存非负。压力测试全部使用新商品，商品 1 只导入 98 件库存，没有被用于压测。20 项功能/故障/回归测试全部通过；详见 stage2-test-results.txt。','','## 限制与问题','','- 单轮短时实验；Redis 高并发场景持续时间尤其短，不能把观察到的 QPS 当作稳定容量。需要 >=60 秒、多轮、交替顺序和独立施压机进一步验证。','- 固定先 MySQL 后 Redis，存在机器温度、缓存和时间顺序影响。预热仅 200 次，不代表充分达到稳态。','- JMeter、应用、MySQL 和 WSL Redis 同机，数据不是生产部署容量。','- Redis 与 MySQL 没有分布式事务；未知提交、补偿失败、进程崩溃需要停单对账。AOF everysec 仍可能在故障时丢失最近写入，不能据本次无超卖推断所有故障下都安全。','- 本阶段无 MQ 削峰、认证、限流或客户端幂等；下一阶段需用户确认。','','## 复现','','```powershell','python perf/run_stage2.py --requests 10000','python perf/run_stage2.py --requests 10000 --modes redis --cases warmup correctness users-1',f'python perf/report_stage2.py {args.primary.as_posix()}'+(f' --rerun {args.rerun.as_posix()}' if args.rerun else ''),'```','','仅在该环境下用以上命令复现；软件版本、资源和请求数须记录，结果不会逐次完全相同。']
Path('docs/stage2-report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Generated docs/stage2-report.md')
```

## perf/stage1.jmx

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
<hashTree>
<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Stage 1 MySQL">
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
</HTTPSamplerProxy><hashTree/>
</hashTree></hashTree></hashTree></jmeterTestPlan>
```
