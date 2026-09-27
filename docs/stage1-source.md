# 阶段一完整文件代码

各文件作用及运行步骤见 [阶段说明](stage1.md)。密码已替换成环境变量引用，复制此配置运行前请设置 SPRING_DATASOURCE_PASSWORD。

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

## src/main/resources/application.properties

```properties
# 应用名称
spring.application.name=seckill-system

# 端口
server.port=8081

# URL前缀
server.servlet.context-path=/api


# MySQL配置
spring.datasource.url=jdbc:mysql://localhost:3306/seckill?useSSL=false&serverTimezone=Asia/Shanghai

spring.datasource.username=root

spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}

spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver



mybatis.configuration.map-underscore-to-camel-case=true
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.connection-timeout=3000
server.error.include-message=always
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

## src/main/java/com/ddk/seckill/controller/SeckillController.java

```java
package com.ddk.seckill.controller;

import com.ddk.seckill.entity.Order;
import com.ddk.seckill.entity.Product;
import com.ddk.seckill.service.SeckillService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeckillController {
    private final SeckillService service;

    public SeckillController(SeckillService service) { this.service = service; }

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
public class SeckillService {
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

## perf/report.py

```python
"""Summarize a JMeter CSV JTL. Run: python perf/report.py result.jtl"""
import csv
import sys
from collections import Counter
from pathlib import Path
rows = list(csv.DictReader(Path(sys.argv[1]).open(encoding="utf-8-sig")))
if not rows:
    raise SystemExit("No samples")
counts = Counter(r["responseCode"] for r in rows)
elapsed = [int(r["elapsed"]) for r in rows]
seconds = (max(int(r["timeStamp"]) + int(r["elapsed"]) for r in rows) - min(int(r["timeStamp"]) for r in rows)) / 1000
success = sum(r["responseCode"] == "201" and r["success"] == "true" for r in rows)
print(f"Requests: {len(rows)}; duration: {seconds:.3f}s")
print(f"HTTP QPS: {len(rows)/seconds:.2f}; order QPS: {success/seconds:.2f}")
print(f"Average: {sum(elapsed)/len(rows):.2f}ms; max: {max(elapsed)}ms")
print(f"Successful orders: {success}; sold out: {counts['409']}; other failures: {len(rows)-success-counts['409']}")
print(f"HTTP status counts: {dict(counts)}")
```

## perf/run_stage1.py

```python
"""Run from project root after mvnw package. Requires Python psutil and JMeter.
Creates dedicated products, retains evidence, starts/stops only its own app.
Use --requests 10000 (default); this is an exploratory baseline, not capacity certification.
"""
import argparse
import csv
import json
import os
import platform
import subprocess
import time
import urllib.request
from collections import Counter
from pathlib import Path
import psutil

parser = argparse.ArgumentParser()
parser.add_argument('--requests', type=int, default=10000)
parser.add_argument('--port', type=int, default=18081)
args = parser.parse_args()
assert args.requests > 0 and args.requests % 100 == 0
root = Path.cwd()
out = root / 'perf' / 'results' / time.strftime('%Y%m%d-%H%M%S')
out.mkdir(parents=True)
config = dict(line.split('=', 1) for line in Path('src/main/resources/application.properties').read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and '=' in line)
mysql = os.environ.get('MYSQL_EXE', r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
env = os.environ.copy()
env['MYSQL_PWD'] = os.environ.get('SPRING_DATASOURCE_PASSWORD', config['spring.datasource.password'])

def sql(query):
    # Explicitly target the local seckill database, never print credentials.
    result = subprocess.run([mysql, '-h', '127.0.0.1', '-u', config['spring.datasource.username'], '-N', '-B', 'seckill', '-e', query], env=env, capture_output=True, text=True, encoding='utf-8', timeout=15)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout.strip()

def status():
    data = sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Questions','Threads_running','Threads_connected')")
    return {row.split('\t')[0]: int(row.split('\t')[1]) for row in data.splitlines()}

def product(stock):
    return int(sql(f"INSERT INTO product(name,stock,price) VALUES ('stage1-perf-{out.name}',{stock},6999.00); SELECT LAST_INSERT_ID();"))

jmeter = root / 'target/tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar'
assert jmeter.exists(), 'Download JMeter first'
app_log = (out / 'application.log').open('w', encoding='utf-8')
app = subprocess.Popen(['java', '-jar', 'target/seckill-system-0.0.1-SNAPSHOT.jar', f'--server.port={args.port}', '--debug=false', '--logging.level.root=INFO', '--logging.level.org.springframework=INFO'], stdout=app_log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
results = []
load = None
try:
    for attempt in range(60):
        if app.poll() is not None: raise RuntimeError('App failed to start; see application.log')
        try:
            with urllib.request.urlopen(f'http://localhost:{args.port}/api/product/1', timeout=1) as response:
                assert response.status == 200
            break
        except (OSError, AssertionError): time.sleep(1)
    else: raise RuntimeError('App readiness timeout')
    for name, users, count, stock in [('warmup',10,200,200), ('correctness',100,1000,100), ('users-1',1,args.requests,args.requests), ('users-10',10,args.requests,args.requests), ('users-100',100,args.requests,args.requests)]:
        pid = product(stock)
        jtl = out / (name + '.jtl')
        before = status()
        log = (out / (name + '.log')).open('w', encoding='utf-8')
        command = ['java', '-Xms256m', '-Xmx512m', '-jar', str(jmeter), '-n', '-t', 'perf/stage1.jmx', f'-Jusers={users}', f'-Jloops={count//users}', '-Jramp=1', f'-Jproduct={pid}', f'-Jport={args.port}', '-l', str(jtl), '-j', str(out/(name+'-jmeter.log'))]
        load = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        watched = {'app': psutil.Process(app.pid), 'jmeter': psutil.Process(load.pid)}
        errors = {}
        for process in psutil.process_iter(['name']):
            if process.info['name'] and process.info['name'].lower() == 'mysqld.exe':
                watched['mysql-' + str(process.pid)] = process
        for key, process in list(watched.items()):
            try: process.cpu_percent()
            except psutil.Error as exc:
                errors[key] = type(exc).__name__
                del watched[key]
        psutil.cpu_percent()
        samples = []
        while load.poll() is None:
            time.sleep(1)
            sample = {'time': time.time(), 'system_cpu': psutil.cpu_percent(), **status()}
            for key, process in watched.items():
                try: sample[key+'_cpu'] = process.cpu_percent()/psutil.cpu_count()
                except psutil.Error: pass
            samples.append(sample)
        log.close()
        if load.returncode: raise RuntimeError('JMeter failed; inspect ' + str(out))
        after = status()
        rows = list(csv.DictReader(jtl.open(encoding='utf-8-sig')))
        assert len(rows) == count, (name, len(rows), count)
        codes = Counter(row['responseCode'] for row in rows)
        duration = (max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)-min(int(r['timeStamp']) for r in rows))/1000
        remaining, orders = map(int, sql(f'SELECT stock,(SELECT COUNT(*) FROM seckill_order WHERE product_id={pid}) FROM product WHERE id={pid}').split('\t'))
        row = {'name':name,'users':users,'requests':count,'product':pid,'initial_stock':stock,'remaining_stock':remaining,'orders':orders,'seconds':duration,'qps':count/duration,'order_qps':codes['201']/duration,'avg_ms':sum(int(r['elapsed']) for r in rows)/count,'max_ms':max(int(r['elapsed']) for r in rows),'codes':dict(codes),'status_before':before,'status_after':after,'cpu_errors':errors,'samples':samples}
        results.append(row)
        (out/'metrics.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        assert remaining+orders == stock and remaining >= 0
        assert orders == codes['201'] == min(count, stock)
        assert codes['409'] == max(0,count-stock)
        print(f"{name}: requests={count}, orders={orders}, QPS={row['qps']:.2f}, avg={row['avg_ms']:.2f}ms, max={row['max_ms']}ms",flush=True)
finally:
    if load is not None and load.poll() is None:
        load.terminate(); load.wait(timeout=20)
    if app.poll() is None:
        app.terminate(); app.wait(timeout=20)
    app_log.close()

report = ['# 阶段一压测报告', '', '阶段：单体 MySQL（本机探索性基线，每档 1 轮）', '', f'测试时间：{out.name}，Asia/Shanghai', '', f'测试环境：{platform.platform()}；AMD Ryzen 9 7940HX；{psutil.cpu_count()} 逻辑核；{psutil.virtual_memory().total/1024**3:.1f} GiB 内存。Java 21 / Boot 3.3.5 / MyBatis 3.0.3 / MySQL '+sql('SELECT VERSION();')+' / JMeter 5.6.3。', '', '应用与数据库、施压端在同一台机器。连接池最大 10，测试端口 18081，业务配置默认仍为 8081。预热 200 次后测试，各档独立商品，未修改商品 1。', '', '|场景|并发|请求数|时长 s|HTTP QPS|下单 QPS|平均 ms|最大 ms|201|409|其他失败|', '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for row in results[1:]:
    c=row['codes']; failures=row['requests']-c.get('201',0)-c.get('409',0)
    report.append(f"|{row['name']}|{row['users']}|{row['requests']}|{row['seconds']:.2f}|{row['qps']:.2f}|{row['order_qps']:.2f}|{row['avg_ms']:.2f}|{row['max_ms']}|{c.get('201',0)}|{c.get('409',0)}|{failures}|")
report += ['', '数据库压力与 CPU：目标约每秒采样，查询开销会拉长间隔。短场景样本可能不足，初始化晚于请求启动，不能把 0 当成全程无 CPU 消耗。CPU 为占整机总算力百分比；窗口包括 JMeter 启动/收尾，CPU 与 HTTP 测量窗口不完全一致。MySQL 全局指标包含采样查询和本机其他连接，不能全部归因于业务。', '', '|场景|行锁等待次数增量|行锁等待累计 ms 增量|Questions 增量|Threads_running 峰值|系统 CPU 均值/峰值 %|应用 CPU 均值/峰值 %|', '|---|---:|---:|---:|---:|---:|---:|']
for row in results[1:]:
    samples=row['samples']; b=row['status_before']; a=row['status_after']
    def cpu(key):
        values=[s[key] for s in samples if key in s]
        return f'{sum(values)/len(values):.2f}/{max(values):.2f}' if values else '未测'
    report.append(f"|{row['name']}|{a['Innodb_row_lock_waits']-b['Innodb_row_lock_waits']}|{a['Innodb_row_lock_time']-b['Innodb_row_lock_time']}|{a['Questions']-b['Questions']}|{max(s['Threads_running'] for s in samples)}|{cpu('system_cpu')}|{cpu('app_cpu')}|")
report += ['', '数据库进程 CPU 采集情况：'+json.dumps(results[-1]['cpu_errors'],ensure_ascii=False)+'。可读取的进程样本保存在 metrics.json；缺少样本的进程未测；AccessDenied 表示当前账户无法读取服务进程 CPU，不以系统 CPU 冒充数据库 CPU。', '', '一致性：所有场景均校验初始库存=剩余库存+订单数，成功响应数量=订单数。售罄场景恰好生成 100 条订单，库存为零；未发现超卖。', '', '测试结果：8 项自动化测试全部通过（4 项单测、3 项真实 MySQL/HTTP 测试、1 项启动测试），打包通过。', '', '问题：每档仅 1 轮、同机施压，且部分窗口可能不足 60 秒；结果只作为初始基线，不是稳定容量上限。需要至少 60 秒、多轮和独立施压机才能形成可靠容量结论。行锁等待数据用于分析竞争，不能直接证明数据库是唯一瓶颈。售罄场景 HTTP QPS 不代表下单能力。', '', '缺点：请求仍直达 MySQL；热点行锁竞争；尚无认证、限流、幂等、一人一单。下一阶段必须经用户确认才能开始。', '', '复现：`python perf/run_stage1.py --requests 10000`（需要 psutil、打包产物及本地 JMeter），详细步骤见 stage1.md。', '', '原始证据目录：'+str(out.relative_to(root)).replace('\\','/')+'，内含 JTL、日志和 metrics.json。']
Path('docs/stage1-report.md').write_text('\n'.join(report)+'\n',encoding='utf-8')
print('Report: docs/stage1-report.md',flush=True)
```
