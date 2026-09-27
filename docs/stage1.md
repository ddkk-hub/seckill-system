# 阶段一：单体 MySQL 秒杀系统

## 1. 阶段目标
完成商品查询、每次购买一件、扣减库存、创建订单和查询订单。流程：HTTP → Controller → Service → Mapper → MySQL。首先验证业务正确性，再建立性能基线。本阶段不引入 Redis、MQ、限流、幂等。

## 2. 设计原因与核心原理
Controller 接收 HTTP 参数，Service 组织业务和事务，MyBatis Mapper 执行 SQL。这样职责明确，后续可以优化业务内部实现而保持接口稳定。

直接访问 MySQL 的原因是结构简单、方便验证库存和订单是否一致。限制是每次购买都访问数据库，同一商品库存记录的行锁会使写操作排队。

`UPDATE product SET stock=stock-1 WHERE id=? AND stock>0` 把判断和扣减放在同一条 SQL。InnoDB 锁住命中的记录；并发更新需要等待先前事务结束，之后根据当前库存判断。只有影响行数为 1，才继续生成订单。因此不要先 SELECT 库存，再根据 Java 中的旧值决定能否扣减。

`@Transactional` 使扣库存和插订单使用同一事务，插入失败则库存回滚。Controller 调用 Spring 注入的 Service 代理，才能触发事务；不要使用 `this.purchase()` 绕过代理。锁一直持有到提交或回滚，因此事务越长，排队越严重。SQL 使用 `#{id}` 绑定参数，避免拼接用户输入。

扣减成功后读取商品，使用 BigDecimal 记录成交价格快照；将来商品改价不会改变历史订单。订单数量固定为 1。表名 seckill_order 避免 ORDER 关键字。自增 ID 允许有间断，不能用最大 ID 代表订单总数。

已核实当前 product 使用 InnoDB，id 为主键，stock 非空，price 为 decimal。MySQL 是 8.0.46。商品 1 的初始库存为 100。

## 3. 修改文件与作用
主代码目录保持 com/ddk/seckill 下的 controller、service、entity、mapper 和 SeckillApplication.java。

| 文件 | 变化和作用 |
|---|---|
| pom.xml | 增加 MyBatis 3.0.3，保留 Boot 3.3.5、Java 21 |
| src/main/resources/application.properties | 配置下划线映射、最大 10 的连接池，保留端口 8081 和 /api |
| src/main/java/com/ddk/seckill/entity/Product.java | 补全商品实体 |
| src/main/java/com/ddk/seckill/entity/Order.java | 补全订单实体和价格快照 |
| src/main/java/com/ddk/seckill/mapper/ProductMapper.java | 新增查询和条件扣库存 |
| src/main/java/com/ddk/seckill/mapper/OrderMapper.java | 新增订单插入、主键回填及查询 |
| src/main/java/com/ddk/seckill/service/SeckillService.java | 实现参数检查、事务下单、业务状态 |
| src/main/java/com/ddk/seckill/controller/SeckillController.java | 实现商品、下单、订单接口，保留 /test |
| src/main/resources/db/stage1.sql | 新建订单表，不清空商品数据 |
| src/test/java/com/example/demo/DemoApplicationTests.java | 指定真实启动类，修复旧包名导致的定位问题 |
| src/test/java/com/ddk/seckill/SeckillServiceTest.java | 业务分支单测 |
| src/test/java/com/ddk/seckill/SeckillMySqlTest.java | HTTP、真实 MySQL 并发和事务回滚测试 |
| perf/stage1.jmx | JMeter 压测计划 |
| perf/run_stage1.py | 自动创建独立商品、启动临时应用、执行压测、采集指标、生成报告 |
| perf/report.py | 读取 JTL，区分成功订单、售罄和异常 |
| docs/stage1-report.md | 实测报告 |
| docs/stage1-source.md | 上述代码文件的完整内容 |

## 4. 完整代码
请阅读 [stage1-source.md](stage1-source.md)，其中包含完整文件代码而非片段。主代码已写入项目，可以直接运行。为避免把本机密码复制到文档，文档里的密码配置使用环境变量；现有本机配置保留，可通过 SPRING_DATASOURCE_PASSWORD 覆盖。

MyBatis 3.0 系列的兼容信息：[官方说明](https://github.com/mybatis/spring-boot-starter)。

## 5. 启动与功能测试
本机已执行订单建表脚本。新环境需要先在 MySQL Workbench 中选择 seckill，再执行 src/main/resources/db/stage1.sql；不能重复插入已有商品。

PowerShell 项目根目录执行：
```powershell
.\mvnw.cmd test
$env:SECKILL_MYSQL_TEST='true'
.\mvnw.cmd test
Remove-Item Env:SECKILL_MYSQL_TEST
.\mvnw.cmd spring-boot:run
```
第一条只运行无需数据库的测试；打开环境开关后增加真实数据库测试，测试创建独立商品并清理自己的记录。Linux/macOS 使用 ./mvnw。

```powershell
Invoke-RestMethod http://localhost:8081/api/product/1
$order = Invoke-RestMethod -Method Post 'http://localhost:8081/api/seckill/1?userId=1001'
Invoke-RestMethod "http://localhost:8081/api/order/$($order.id)"
Invoke-RestMethod http://localhost:8081/api/product/1
```
若购买前为 100，则下单成功后为 99；POST 返回 201，包含 id、userId、productId、quantity=1、price=6999.00、createdAt。GET 订单返回同一条记录。售罄返回 409，商品不存在返回 404，非正 ID、缺少 userId 或非数字参数返回 400。

完成标准：每次成功恰好扣 1 并写入 1 条订单；订单可查询；并发不超卖；插单失败库存回滚；完成真实 MySQL 测试与可复现压测。

## 6. JMeter 压测
工具：Apache JMeter 5.6.3 非 GUI 模式。首次运行可使用 target/tools/apache-jmeter-5.6.3/bin/jmeter.bat；此目录是本地工具缓存，不应提交 Git。

每轮创建独立商品，先确认指定 ID 未被占用，保留测试数据供核对，不重置商品 1。
```sql
INSERT INTO product(id,name,stock,price) VALUES(900001,'stage1-perf',100,6999.00);
```
正确性：100 用户，每用户 10 次，初始库存 100，期望 100 次 201 和 900 次 409，无系统错误。
```powershell
.\target\tools\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/stage1.jmx -Jusers=100 -Jloops=10 -Jramp=1 -Jproduct=900001 -l perf/correctness.jtl -e -o perf/correctness-html
python perf/report.py perf/correctness.jtl
```
吞吐场景：分别 1、10、50、100 并发。每轮新商品，库存至少等于总请求量，保证比较的是成功下单性能。先预热，再每轮持续至少 60 秒，按实测速度调整 loops，各重复 3 轮。以下示例需要先创建 900002，库存至少 100000：
```powershell
.\target\tools\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/stage1.jmx -Jusers=100 -Jloops=1000 -Jramp=5 -Jproduct=900002 -l perf/throughput.jtl -e -o perf/throughput-html
python perf/report.py perf/throughput.jtl
```
使用新的 JTL 文件和 HTML 输出目录，避免旧数据混入。报告的实际轮数和持续时间必须写清楚，短测不能称为容量上限。

HTTP QPS=总请求数/测量窗口；成功下单 QPS=201 数量/相同窗口。平均和最大响应时间取 JTL elapsed。409 是业务拒绝，JMeter 默认将它标为失败，必须与超时和 5xx 分开统计。大量售罄请求的吞吐不能代表订单写入吞吐。

数据库校验：
```sql
SELECT stock FROM product WHERE id=900001;
SELECT COUNT(*),COALESCE(SUM(quantity),0) FROM seckill_order WHERE product_id=900001;
```
初始库存必须等于剩余库存加订单数量，且库存非负。

数据库压力：采样 Threads_running、Threads_connected，记录 Innodb_row_lock_waits、Innodb_row_lock_time、Questions 的前后差值。查 performance_schema.data_lock_waits 观察等待，但瞬时为零不等于没有竞争。结合 CPU、磁盘和连接池分析，不能只凭低 QPS 断言唯一瓶颈。

CPU：每秒采样应用 java、JMeter java、mysqld 和整机 CPU。进程占整机百分比=进程 CPU 秒增量/墙钟秒数/逻辑核数×100。注明逻辑核数、平均值、峰值、是否同机。采集失败要写未测及原因。

压测报告包含阶段、环境、并发、数量、QPS、平均/最大响应、成功/失败数、数据库压力、CPU 和问题，见 stage1-report.md。

## 7. 当前问题
1. 每次购买都访问 MySQL，热点行锁使同一商品写入排队，连接池可能成为等待点。
2. 无登录认证，userId 是教学参数，查询订单尚无归属验证，不适合公开部署。
3. 无一人一单或请求幂等，重复提交会重复购买，但不会使库存变成负数。
4. 提交成功后网络响应丢失会使客户端不确定结果，之后需用幂等处理重试。
5. 不包含活动时间、支付、取消、库存返还。当前下单代表完成一次购买。
6. 统一异常、日志等工程化留待阶段五。
7. 正确版本不应超卖。先查再扣的错误实现有竞态，不能为展示风险而放入正式接口。Redis 的价值是后续减少库存竞争和数据库压力，不是 MySQL 正确扣减的必需条件。

## 8. 与上一阶段比较
这是第一阶段，没有上一阶段性能数据。相比项目空壳增加 MyBatis、事务和订单持久化，建立完整业务流程及压测基线。后续比较需保持相同硬件、参数和业务成功口径，不能承诺固定倍数提升。

## 9. 需要掌握
Spring MVC 参数绑定与 HTTP 状态；依赖注入与分层；MyBatis 参数绑定、自增主键回填；InnoDB 事务和行锁；条件 UPDATE 原子性；BigDecimal 金额；JMeter 与成功下单吞吐。

本阶段完成后必须等待用户确认，再进入阶段二。

## 本机自动执行入口
已提供 Python 自动脚本（依赖 psutil，本机已有）：
```powershell
python perf/run_stage1.py --requests 10000
```
脚本固定连接本机 seckill，读取已有数据库用户名和密码（密码可由环境变量覆盖），MySQL CLI 默认路径为 C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe，可设置 MYSQL_EXE 覆盖。需要 Maven 打包产物和已下载的 JMeter。它使用空闲端口 18081 启动应用，完成后停止自己启动的应用；每轮创建独立商品，保留订单用于核对。requests 必须是 100 的正整数倍。

默认预热 200 次、售罄场景 1000 次，随后 1、10、100 并发各 10000 次；这是探索性基线。每轮数据保存在 perf/results 的时间戳目录，报告生成到 docs/stage1-report.md。不能用短时间单轮结果宣称稳定容量。

错误分析记录：首次构建出现 MyBatis 注解类缺失，随后依赖树确认 MyBatis 3.5.14 和 MyBatis-Spring 3.0.3 已解析，clean test 与真实 MySQL 测试均通过；初次失败根因未完全确认，不为规避错误修改业务设计。Windows 管道编码问题通过显式 UTF-8 修复。JMeter 归档下载 TLS 失败后，使用 Apache 官方下载站并验证 SHA-512。
