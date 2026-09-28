# 阶段三：Redis + RabbitMQ 异步下单

## 1. 阶段目标
用户请求 → Redis Lua 预扣并记录请求 → RabbitMQ 确认接收 → HTTP 202；消费者 → MySQL 事务创建订单 → Redis 标记完成 → ACK。
实现请求受理与数据库下单解耦，通过队列暂存突发流量。停留在阶段三，限流、防刷、客户端重复提交保护留到阶段四。

## 2. 设计原因与边界
选择 RabbitMQ，便于演示队列、发布确认、ACK、重试和死信。生产者等待 publisher confirm，并检查 mandatory return，确认路由成功后才返回 202。202 不代表购买成功。

Redis Lua 一次完成库存校验、预扣、请求 payload 和 pending 记录。MySQL 新增请求流水表，以 request_id 主键处理同一消息的重复投递：事务先锁定流水，已有 order_id 就返回原订单，否则插入订单并更新流水。订单与流水在同一事务中提交；消费者方法返回后由 Spring AUTO ACK。这里的 AUTO 是容器处理成功后确认，不是 RabbitMQ 的收到即确认。

同一消息最多尝试三次，间隔 200、400ms；仍失败则拒绝重新入队并进入死信队列。发布超时、失败和死信保留预扣库存，避免消息实际已成交后错误补库存。需人工核对才能重放或补偿；本阶段不实现自动对账工具。

状态：QUEUED 是提交响应；PENDING 表示待处理；SUCCESS 表示 MySQL 已有订单；UNKNOWN 表示发布结果不确定；REVIEW_REQUIRED 表示需要核查。结果查询优先查 MySQL，即使 Redis 完成标记失败也能返回已成交订单。成功请求缓存保留七天，数据库流水继续保留。未完成请求不自动过期。

## 3. 文件变更与完整代码
修改 pom.xml（AMQP 依赖）、controller/SeckillController.java（异步模式使用独立控制器）。
新增文件及作用、所有当前源码的完整内容见 [stage3-source.md](stage3-source.md)。包名保持 com.ddk.seckill，Java 主目录仍仅使用 controller/service/entity/mapper。
新增 application-stage3.properties、db/stage3.sql、三份 async Lua、集成测试、perf/run_stage3.py、perf/run_stage3_burst.py、scripts/start-rabbitmq.ps1。
application-local.properties 只在本机保存账号密码，已被 Git 忽略，不进入源码附件。

## 4. 环境与启动
本机 RabbitMQ 4.0.5 安装在 WSL Ubuntu；专用 vhost 为 seckill，本地随机密码存放于 application-local.properties。Redis 和 RabbitMQ 都仅监听本机地址。RabbitMQ 管理页 http://localhost:15672，登录使用本地配置中的专用账号，勿将密码复制到报告或 Git。

PowerShell：
```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-redis.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-rabbitmq.ps1
.\mvnw.cmd -DskipTests package
```
启动前在 **mysql>** 提示符执行：
```sql
SOURCE C:/seckill-system/src/main/resources/db/stage3.sql;
```
阶段二 baseline 表必须已存在。不要在 PowerShell 直接输入 SELECT。

停止自己原来占用 8081 的阶段二应用，再启动：
```powershell
java -jar target/seckill-system-0.0.1-SNAPSHOT.jar --spring.profiles.active=stage3
```
只启动一个版本。商品 1 沿用阶段二现有 Redis 库存；不要重置为 100，也不要重新从 MySQL 的旧库存覆盖 Redis。新商品须先在停止售卖时执行阶段二的离线初始化命令，再启动售卖。

## 5. 接口验收
PowerShell 命令中的 URL 使用纯文本，不要复制 Markdown 链接括号。
```powershell
Invoke-RestMethod 'http://localhost:8081/api/product/1'
$receipt = Invoke-RestMethod -Method Post 'http://localhost:8081/api/seckill/1?userId=1001'
$receipt
Invoke-RestMethod "http://localhost:8081/api/seckill/result/$($receipt.requestId)"
```
预期 POST 返回 requestId、status=QUEUED、orderId=null（HTTP 202）。查询可能先返回 PENDING，稍后再查询应为 SUCCESS 并带 orderId。消费者很快时首次查询直接 SUCCESS 也正常。
```powershell
$result = Invoke-RestMethod "http://localhost:8081/api/seckill/result/$($receipt.requestId)"
Invoke-RestMethod "http://localhost:8081/api/order/$($result.orderId)"
wsl -d Ubuntu -u root -- redis-cli GET product_stock_1
```
只有 SUCCESS 时执行订单查询。库存应比请求前减少 1；MySQL product.stock 仍是阶段二初始化基线，不是实时库存。MySQL 中按 request_id 查询：
```sql
SELECT request_id,product_id,user_id,order_id FROM seckill_async_order WHERE request_id='替换为返回的UUID';
SELECT id,user_id,product_id,quantity,price FROM seckill_order WHERE id=替换为返回的订单ID;
```
售罄返回 409，参数非法 400。遇到 UNKNOWN/503 保留 requestId 并查询，不要反复提交生成新请求。如果 Redis 预扣超时且实际未写入，请求查询可能返回 404；此时仍需结合日志核查，不能仅凭超时判断是否扣过库存。本阶段同一个 userId 可以多次购买，客户端幂等尚未实现。

## 6. 自动化测试与压测
```powershell
$env:SECKILL_MYSQL_TEST='true'
$env:SECKILL_REDIS_TEST='true'
$env:SECKILL_MQ_TEST='true'
.\mvnw.cmd test
python perf/run_stage3.py --requests 3000
python perf/run_stage3_burst.py --port 18086
```
需已有 JMeter 5.6.3（target/tools/apache-jmeter-5.6.3）和 Python psutil。不要执行 mvnw clean 后直接运行压测，因为它会删除 target 中的 JMeter。
压测创建独立商品，结果保存在 perf/results，不删除原始 JTL。主对比使用 18084、队列 seckill.benchmark.v3；削峰实验使用 18086/18087 和同一独立队列，必须顺序执行。测试使用另一队列 seckill.test.orders.v3。
测试与压测不要并行运行；禁止在真实生产环境执行这些演示脚本。
报告见 [stage3-report.md](stage3-report.md)，原始断言与测试摘要见 [stage3-test-results.txt](stage3-test-results.txt)。
完成标准：202 后可查询最终订单；100 个请求争抢 10 件只能生成 10 单；重复消息不重复建单；事务失败无半成品；暂停消费能积压且恢复后库存订单一致。

## 7. 当前问题
- Redis 与 RabbitMQ 之间没有跨系统事务：预扣后进程崩溃可能尚未发布。请求记录可核查，但没有自动恢复，不能宣称绝不丢单。
- MySQL 提交后 Redis 更新也可能失败，需要按数据库流水修复 pending。
- 本机 RabbitMQ 单节点、classic durable 队列和持久消息不等于高可用。classic 队列的默认死信转发不是端到端可靠投递，故障时仍有丢失风险。
- 队列最大长度 100000（reject-publish），超过时提交可能 UNKNOWN，需核查；这不是入口限流。
- 发布确认也需要网络/磁盘时间，异步不保证本机 QPS 比同步高。数据库仍须写每笔订单，还新增流水写入。
- Redis AOF everysec 有宕机丢失窗口；Lua 多 key 使用当前单节点方案，不能直接迁移 Redis Cluster。
- 无登录鉴权、客户端幂等和防刷；结果接口用于教学，本阶段不适合公网生产。

## 8. 与阶段二比较
阶段二：请求线程等待 MySQL 事务提交后返回 201 和订单。阶段三：请求等待 RabbitMQ 确认后返回 202 和请求号，两个消费者以固定并发落库，prefetch=10。MQ 吸收突发流量，数据库消费速度与入口速度解耦；积压越多，最终下单等待越长。必须同时看受理 QPS、订单完成情况、队列长度和耗尽时间，不能只拿 202 吞吐当成交能力。

## 9. 核心知识点
publisher confirm 确认 broker 接收；mandatory return 检查是否路由到队列；consumer ACK 确认消费成功，三者解决不同环节的问题。
至少一次投递可能重复，因此用 request_id 唯一约束和数据库事务实现重复消息只产生一个业务订单。它不等于端到端 exactly-once，也不阻止用户用新 requestId 重复提交。
削峰是暂存高峰请求、按下游可承受速度消费，不会凭空提高数据库容量。死信是隔离失败消息，后续仍需核查与处理。

参考：[RabbitMQ 确认机制](https://www.rabbitmq.com/docs/confirms)、[可靠性](https://www.rabbitmq.com/docs/reliability)、[死信队列](https://www.rabbitmq.com/docs/dlx)。
