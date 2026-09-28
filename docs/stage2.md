# 阶段二：Redis + Lua 库存预扣

## 1. 阶段目标
流程：请求 → Redis 读取商品快照 → Lua 判断并预扣库存 → 同步 MySQL 创建订单 → 返回 201。

本阶段将实时库存查询与扣减迁移到 Redis，订单仍同步写入 MySQL。未引入 MQ、限流或接口幂等。阶段一已避免超卖，阶段二优化的是竞争位置和数据库压力。

## 2. 当前设计及原因

### 两种模式
默认模式继续使用阶段一 MySQL 实现；指定 `--spring.profiles.active=stage2` 才使用 Redis 实现。Controller 依赖 SeckillOperations 接口，由配置选择实现，URL 保持不变。同一个商品不能由两个模式同时售卖。切换时先停止旧应用和所有该商品的写入，再导入库存。

### Redis 数据
- `product_stock_1`：String，实时可用库存。如果商品 1 在阶段一已购买两件，应导入 98，而不是重置为 100。
- `product_info_1`：String，JSON 商品及价格快照。库存字段由读取脚本返回的实时值覆盖。
- `product_pending_1`：Hash，内部预扣 token → PENDING，用于防止同一预扣重复补偿。正常提交后清理，异常未完成时保留。

这是内部补偿标记，不是面向客户端的请求幂等。同一个 userId 再次提交仍会再次购买；接口幂等和一人一单后续再设计。

### Lua 为什么能避免竞争
普通 GET 然后 DECR 是两条命令，之间可能穿插其他请求。reserve.lua 在一次脚本执行内完成检查与扣减。Redis 原子执行脚本，其他命令不会在中间穿插。脚本必须短小；写入前检查 key 类型、库存整数格式与范围。Lua 运行异常并不会自动撤销之前的写入，这与 MySQL 事务不同。

read.lua 一次读取库存与元数据，原样传回 JSON；避免 Lua cjson 浮点转换造成大整数 ID 或金额精度损失。价格使用 BigDecimal，导入后价格快照在本次活动期间不随 MySQL 改价自动更新。

### MySQL 库存字段含义改变
Redis 模式下 `product.stock` 是导入前的存量，不再每单更新；下单只 INSERT 订单。新表 seckill_stock_baseline 保存导入库存和导入时历史订单数量，不修改既有订单。

没有在途请求、补偿失败或待核对预扣时：

`初始导入库存 = Redis 剩余库存 + 当前订单购买总量 - 导入时订单购买总量`

故障中的未决预扣需要单独核对，不能只看到库存不相等就把差额加回。已提交订单但标记清理失败时，pending 里也可能包含已提交项，不能把 HLEN 直接当作未下单数量。

### 初始化不覆盖
StockInitializer 是显式离线管理操作，没有公开 HTTP 初始化接口。先保存 MySQL 基线，再用 Lua 创建 Redis keys；任何一个 key 已存在都不会覆盖。已有基线时只读取现有 Redis，不会补满。

若首次保存基线成功但 Redis 初始化失败，重复初始化也不会从旧库存自动恢复，需要停单核对。这个选择牺牲自动恢复便利，避免未知情况下重新放出库存。初始化期间必须停止该商品的所有写入。

### 跨系统失败与补偿
Redis 不属于 MySQL 本地事务。先预扣，再用 TransactionTemplate 开启独立事务写订单，等待提交完成后才返回成功。

|情况|处理|
|---|---|
|库存为零|409，不进入数据库事务|
|库存未初始化、key 丢失或不合法|503，不自动从 MySQL 补满|
|Redis 连接失败|503，不创建订单|
|明确收到 MySQL 回滚结果|执行 release.lua；同一 token 最多补偿一次|
|MySQL 提交结果未知|保留预扣并记录 token，返回 503，禁止盲目补偿|
|Redis 预扣响应超时|可能已扣减，保留现场并记录 token，不自动重试扣减|
|订单提交成功、Redis 标记清理失败|订单仍返回成功，保留标记待核对|
|回滚后的补偿失败|保留预扣，可能少卖，需要停单对账|

当前并非完整分布式事务方案。进程崩溃可能产生未决预扣，自动恢复和持久化补偿任务尚未实现。日志 token 是诊断线索，不替代可靠恢复账本。

## 3. 修改与新增文件
所有主代码仍在 `src/main/java/com/ddk/seckill` 下，没有新建其他主代码包层级。

|文件|作用|
|---|---|
|pom.xml（修改）|Spring Data Redis，版本由 Boot 3.3.5 管理|
|src/main/resources/application.properties（修改）|MySQL 连接改为 sslMode=REQUIRED，修复重启后认证失败|
|controller/SeckillController.java（修改）|依赖统一服务接口|
|service/SeckillService.java（修改）|保留阶段一逻辑，按模式启用|
|service/SeckillOperations.java|服务接口|
|service/RedisStockService.java|Redis 脚本调用、商品与库存读取|
|service/RedisSeckillService.java|预扣、同步订单事务、事务完成回调|
|service/StockInitializer.java|库存基线与显式初始化|
|service/StockInitializationCommand.java|无 Web 的一次性初始化入口|
|entity/StockBaseline.java|库存基线记录|
|mapper/StockBaselineMapper.java|基线写入、历史订单数量查询|
|src/main/resources/application-stage2.properties|Redis 连接及 Redis 模式配置|
|src/main/resources/db/stage2.sql|基线表 DDL|
|src/main/resources/lua/initialize.lua|只创建不存在的库存数据|
|src/main/resources/lua/reserve.lua|原子预扣及内部标记|
|src/main/resources/lua/release.lua|确认回滚后的去重补偿|
|src/main/resources/lua/read.lua|原子读取库存和元数据|
|src/test/java/com/ddk/seckill/RedisSeckillIntegrationTest.java|9 项真实 Redis/MySQL 集成测试|
|src/test/java/com/ddk/seckill/RedisFailureTest.java|3 项模拟故障语义测试|
|scripts/start-redis.ps1|隐藏运行 WSL Redis 前台进程，保持本机服务可用|
|perf/redis_client.py|实验监控使用的轻量 RESP 客户端|
|perf/report_stage2.py|从原始实验与复测生成报告|
|perf/run_stage2.py|MySQL 与 Redis 同条件压测、原始数据留存|
|docs/stage2-source.md|当前完整代码，不是片段|
|docs/stage2-report.md|性能、数据库压力、CPU 与对比分析|

表中 controller/service/entity/mapper 路径均以 `src/main/java/com/ddk/seckill/` 为前缀。脚本和测试文件均有各自独立目录。

## 4. 完整代码
见 [stage2-source.md](stage2-source.md)。本机密码只保存在被 Git 忽略的 application-local.properties，文档不会复制该文件。

## 5. 启动与测试

### Redis
已安装 Ubuntu（WSL 2）和 Redis 8.0.5。仅监听回环地址，protected-mode=yes，appendonly=yes，appendfsync=everysec，maxmemory=256mb，maxmemory-policy=noeviction。

AOF everysec 在突然掉电时仍可能丢失最近写入；noeviction 避免主动淘汰库存，但内存满时会拒绝写入。Redis 在本阶段是库存数据源，不是可随意删除的缓存。异常重启/恢复后必须先停单对账，不能认为 AOF 等价于零数据丢失。

Redis 尚未运行时，在 PowerShell 执行：
```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-redis.ps1
wsl -d Ubuntu -u root -- redis-cli ping
```
预期 PONG。脚本针对本机环境；不要在有购买流量时重启 Redis。当前 WSL 服务通过 redis 用户运行，无需把 Redis 暴露到局域网。

### 建表与打包
在 seckill 数据库执行 src/main/resources/db/stage2.sql（本机集成测试已创建此表）。
```powershell
.\mvnw.cmd package
```

### 导入商品 1（先停止该商品的旧应用与写入）
```powershell
java -jar target/seckill-system-0.0.1-SNAPSHOT.jar --spring.profiles.active=stage2 --spring.main.web-application-type=none --seckill.initialize-product=1
```
正常输出 Inventory ready，程序自行退出。重复执行只返回现有库存，不补满。存在基线但 Redis 数据缺失时应失败，需要核对后恢复。

### 启动阶段二
```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=stage2'
```
或者：
```powershell
java -jar target/seckill-system-0.0.1-SNAPSHOT.jar --spring.profiles.active=stage2
```
仅执行默认启动命令会运行阶段一；不要用它售卖已迁移的商品。

### 手动验证
```powershell
Invoke-RestMethod -Uri 'http://localhost:8081/api/product/1'
$order = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/seckill/1?userId=1001'
Invoke-RestMethod -Uri "http://localhost:8081/api/order/$($order.id)"
wsl -d Ubuntu -u root -- redis-cli GET product_stock_1
```
若导入时为 98，购买后接口及 Redis 都应为 97，MySQL product.stock 仍为 98，订单增加一条。阶段二商品查询完全读 Redis，因此未导入商品（含不存在的商品）统一返回 503 STOCK_NOT_READY；初始化不存在商品时才查 MySQL 并返回 404。订单不存在仍为 404，非法 ID 为 400。

### 全量测试
```powershell
$env:SECKILL_MYSQL_TEST='true'
$env:SECKILL_REDIS_TEST='true'
.\mvnw.cmd test
Remove-Item Env:SECKILL_MYSQL_TEST
Remove-Item Env:SECKILL_REDIS_TEST
```
预期 20 项通过，失败/错误/跳过均为 0。默认未设置开关时只跑 7 项单测，数据库相关测试会跳过。测试创建独立商品并只清理自己的 keys/订单，不扣商品 1，不执行 FLUSHDB。

真实集成测试覆盖：正常购买、初始化不补满、100 请求争抢 10 库存、明确回滚恢复、重复补偿、key 丢失、错误类型、提交后清理失败、回滚后补偿失败、HTTP 路由。多个检查合并于 9 个测试方法。连接失败、预扣超时和提交未知采用可控模拟，不代表已做真实网络分区/掉电测试。

## 6. 压测设计与报告
```powershell
python perf/run_stage2.py --requests 10000
```
使用现有 JMeter 计划，同机依次运行 MySQL 和 Redis 两组。每组先预热 200 次，然后 100 并发/1000 请求/100 库存验证售罄，接着 1、10、100 并发分别执行 10000 次有库存下单。每轮独立商品，Redis 组通过真实初始化命令导入。

原始 JTL、日志、CPU/MySQL/Redis 指标、参数和应用 JAR 哈希保存到 `perf/results/stage2-时间戳`。本次报告见 stage2-report.md；不覆盖阶段一原始文件。当前默认每档单轮，不是稳定容量测试。要形成容量结论，应让每轮至少持续 60 秒，重复至少 3 轮并交替组顺序，最好使用独立施压机。

HTTP QPS 与成功下单 QPS 分开；409 是业务拒绝，不是系统故障。售罄请求很快不能代表数据库订单写入能力。新的 MySQL 对照组与 Redis 使用相同 TLS 和连接池配置，历史阶段一数据作为背景，不能把新旧环境差异都归功于 Redis。

## 7. 当前问题与边界
1. MySQL 仍同步写订单，吞吐受订单写入、连接池和磁盘影响；尚无异步削峰。
2. Redis 与 MySQL 无共同事务，存在少卖/未决预扣；异常重启、AOF 丢失还可能使库存回退，恢复前必须停单对账。
3. 尚无自动对账、自动恢复和活动管理接口；不要手动 DEL 库存后按原始数量重建。
4. Redis 单实例；多 key 脚本当前 key 名不含 Cluster hash tag，不能直接迁移 Redis Cluster。
5. 无认证、限流、客户端幂等或一人一单，仍为教学系统。
6. 初始化后商品元数据固定；禁止活动期间绕过本系统改库存或混用阶段一。
7. 原子预扣解决正常运行中的库存竞争，不代表已覆盖所有故障下的库存安全。

## 8. 与阶段一比较
新增 Redis、Lua、库存导入基线、事务完成后的补偿。成功购买不再 UPDATE MySQL product 热点行，售罄请求不再查 MySQL；商品读取也转到 Redis。增加了 Redis 往返和双系统一致性复杂度，所以低并发不保证更快。订单仍同步持久化，阶段三经用户确认后再引入 MQ。

## 9. 知识点
String/Hash；Lua 与原子性边界；Spring Data Redis 与脚本缓存；TransactionTemplate 和事务完成状态；内部补偿 token 与客户端幂等的区别；价格快照和 BigDecimal；显式初始化、库存基线及停单对账；AOF/noeviction；压测口径、锁等待与 CPU。

## 错误分析记录
- WSL 安装需要重启：先完成系统组件生效，再安装 Ubuntu/Redis。
- WSL 系统服务未保持 Windows 端口可用：改为隐藏驻留的 WSL Redis 前台进程，Windows PING 验证成功。
- MySQL 重启后 Public Key Retrieval is not allowed：旧 URL useSSL=false，改为 sslMode=REQUIRED 后完整测试通过；未修改账号密码。REQUIRED 加密连接但不校验证书身份，本机教学环境适用，远端部署需配置证书验证。

参考：[Redis Lua](https://redis.io/docs/latest/develop/programmability/eval-intro/)、[Spring 事务完成状态](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)、[MySQL TLS](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-using-ssl.html)。

## 本次交付状态

阶段二代码、环境、测试和探索性压测已完成。Redis 8.0.5 在 Ubuntu 26.04.1 LTS / WSL 2 中运行；商品 1 已导入 98 件，pending=0，没有在压测中购买商品 1。数据库基线已创建。

20 项测试通过；首轮和复测的 73600 次请求均已保存并核对。报告中说明了一组受初始化干扰的单并发数据以及独立复测结果。短时单轮数据只支持初步对照，不表示容量验证完成。阶段三尚未开始，等待用户确认。

生成报告：
```powershell
python perf/report_stage2.py perf/results/stage2-20260927-100654 --rerun perf/results/stage2-20260927-101413
```

当前应用源码使用库存单一来源的设计：默认 MySQL 模式只能用于尚未迁移的独立商品。已迁移商品 1 请始终使用 stage2 profile。若要退回阶段一，必须先停单、对账并迁移实时库存，不能直接切换配置。

## 2026-09-28 恢复检查

本机 PowerShell 默认禁止直接运行 ps1，启动命令改为仅对当前子进程使用 ExecutionPolicy Bypass；没有修改系统或用户的持久执行策略。Redis 启动后对全部已导入商品核对持久化库存、历史基线和订单数。实际结果见 stage2-restart-check.json。此次是正常恢复检查，不代表突然断电、网络分区等故障验证。
