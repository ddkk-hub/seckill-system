# 阶段四：限流、接口防刷与请求幂等

## 1. 阶段目标
保留阶段三的 Redis → RabbitMQ → MySQL 异步链路，在进入业务前限制请求速率，并让同一次操作的重复提交得到同一个 requestId 和最终订单。

流程：参数检查 → Redis 多维令牌桶 → 幂等检查/原子预扣 → MQ 确认 → 返回请求号 → 消费者创建订单。
完成后，超限请求返回 429；同一用户使用相同 Idempotency-Key 并发重试不会重复扣库存或发送消息；查询接口也受保护。

## 2. 当前设计原因
### 为什么使用令牌桶
桶最多存 capacity 个令牌，每秒补充 rate 个。每个被允许的请求消耗一个令牌，因此既允许小规模突发，也限制持续平均速率。任意长度 T 秒的窗口，允许量不应超过 capacity + rate × T（考虑测量误差）。限流返回 429 是主动拒绝，不是系统故障；它也不保证公平分配。

用 Redis Lua 在一次脚本执行中检查全局、IP、用户三个桶；只有所有桶都允许才一起扣令牌，避免某个桶拒绝后其他桶仍被扣减。时间来自 Redis TIME，各应用实例共享 Redis 中的令牌状态。桶空闲后自动过期；过期时间大于补满所需时间，过期重建满桶不会额外增加额度。

Lua 保证脚本执行期间不被其他请求插入，不代表发生运行时错误可以自动回滚已经执行的写命令。因此脚本先检查数据类型与状态，再执行写入；遇到 Redis 异常直接停止放行，返回 503。

### 防刷的具体范围
- 下单：全局 200/s、突发 100；单 IP 50/s、突发 20；单 userId 2/s、突发 3。
- 查询商品、订单和异步结果共享查询桶：全局 400/s、突发 200；单 IP 20/s、突发 10。
- 数值是本机演示配置，不是从压测证明的生产容量。配置位于 application-stage4.properties。
- 重复提交也必须经过限流，防止使用同一幂等键高频读取数据库。
- 来源使用 HttpServletRequest.getRemoteAddr；明确关闭 forwarded-header 自动转换，不直接信任 X-Forwarded-For。部署在可信反向代理后需要重新设计可信代理范围，否则同一代理下的用户会共享 IP 配额。
- 当前 userId 仍由请求参数传入，不是可信登录身份。换 userId 可绕开用户桶，但同一来源仍受 IP 与全局桶限制；分布式攻击、鉴权、验证码和用户行为识别不在本阶段实现范围。

### 幂等与消息去重的区别
阶段三防的是同一 MQ 消息重复消费；不同 POST 会生成不同消息，仍可能买多次。阶段四让客户端为“一次购买操作”生成 UUID 作为 Idempotency-Key；网络超时后重试必须复用这个键，不能重新生成。

键的作用域是 userId + Idempotency-Key，requestId 由该作用域稳定派生。Redis 用永久 marker 记录 userId:productId。首次预扣时，marker、请求 payload、pending 和库存扣减在同一 Lua 脚本完成；并发请求只有一个能得到“首次执行”，其余查询原结果。同一键换商品返回 409。

同一商品换一个新键表示新的购买操作，允许再次购买。本阶段实现请求幂等，不是“每人限购一件”。没有预扣成功的请求（例如参数错误、429、售罄）不会建立幂等记录。

成功请求 ticket 七天后过期，marker 仍保留；重复提交可以查 MySQL 流水返回原订单。marker 暂不自动过期，避免幂等期限过后再次扣库存；代价是 Redis 内存持续增长，需要后续持久化归档和明确业务保留期。

发布结果未知时保留预扣，重复使用同一键返回原 UNKNOWN，不会自动重新发送。若首次进程在预扣后崩溃，重试仍可能 PENDING，需人工对账，不能将幂等误解为自动补发或自动恢复。

## 3. 文件变更与作用
| 操作 | 路径 | 作用 |
|---|---|---|
| 修改 | pom.xml | 新增可选构建名称参数，避免 Windows 运行中 JAR 占用；默认名称不变 |
| 修改 | src/main/java/com/ddk/seckill/controller/AsyncSeckillController.java | 开启阶段四时停用阶段三 HTTP 控制器 |
| 新增 | src/main/java/com/ddk/seckill/controller/ProtectedSeckillController.java | 必需请求头、限流入口、200/202/503 响应 |
| 新增 | src/main/java/com/ddk/seckill/service/TokenBucketLimiter.java | 多维令牌桶参数和 Lua 调用 |
| 新增 | src/main/java/com/ddk/seckill/service/RateLimitExceededException.java | 429 与 Retry-After 响应头 |
| 新增 | src/main/java/com/ddk/seckill/service/ProtectedSeckillService.java | 原子幂等预扣、重试查询及首次消息发布 |
| 新增 | src/main/resources/lua/token-bucket.lua | 原子检查/扣减多个桶 |
| 新增 | src/main/resources/lua/idempotent-reserve.lua | 原子建立幂等标记和扣库存 |
| 新增 | src/main/resources/application-stage4.properties | 阶段四启用开关和限流配置 |
| 新增 | src/test/java/com/ddk/seckill/ProtectedSeckillIntegrationTest.java | 并发幂等、拒绝、补充令牌及故障测试 |
| 新增 | perf/stage4.jmx、perf/run_stage4.py、perf/report_stage4.py | 相同环境下有/无保护的 JMeter 对比 |
| 新增 | docs/stage4-source.md、stage4-report.md、stage4-test-results.txt、baselines/stage4-* | 完整源码、测试与压测归档 |
| 更新 | README.md、docs/experiments.md、.gitattributes | 文档入口与实验索引 |

MySQL 表结构、阶段三消费者和事务写单逻辑沿用；无需新增 SQL。包名与 controller/service/entity/mapper 主目录保持原样。全部文件完整代码见 [stage4-source.md](stage4-source.md)。

## 4. 启动方法
前提：阶段三本地 MySQL、Redis、RabbitMQ 已准备好；凭据仍保存在 Git 忽略的 application-local.properties。
```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-redis.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-rabbitmq.ps1
.\mvnw.cmd -DskipTests "-Dseckill.build-name=seckill-system-stage4" package
```
首次阶段四构建使用独立 JAR；如果阶段四 JAR 已在运行，须先停止它再重新构建同名文件。

停止自己原来的 8081 阶段三应用，再启动：
```powershell
java -jar target/seckill-system-stage4.jar --spring.profiles.active=stage4
```
当前商品沿用 Redis 库存，不要重新从 MySQL 基线覆盖。阶段四沿用 seckill.orders.v3 队列，可继续消费阶段三已入队消息。切回 stage3 会关闭 HTTP 限流和请求幂等，不要在运行中的售卖活动中混用不同入口版本。

## 5. 手工验收
每次“新的购买操作”生成一个键，重试时保存并复用。不要把 Markdown 链接括号复制到 PowerShell。
```powershell
$before = Invoke-RestMethod 'http://localhost:8081/api/product/1'
$headers = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$first = Invoke-RestMethod -Method Post -Headers $headers 'http://localhost:8081/api/seckill/1?userId=1001'
$first
Start-Sleep -Seconds 1
$again = Invoke-RestMethod -Method Post -Headers $headers 'http://localhost:8081/api/seckill/1?userId=1001'
$again
Invoke-RestMethod "http://localhost:8081/api/seckill/result/$($first.requestId)"
$after = Invoke-RestMethod 'http://localhost:8081/api/product/1'
$before.stock - $after.stock
```
预期：两次 requestId 相同；首次通常 QUEUED/202，重复请求可能 PENDING/202 或 SUCCESS/200；最终只有一个 orderId；无其他用户购买时库存差为 1。不要为了重试重新赋值 $headers。

错误行为：
- 缺少或不是标准 UUID 的 Idempotency-Key：400，库存不变。
- 复用同一键但换 productId：409，库存不变。
- 桶无令牌：429，读取 Retry-After 秒数等待后复用同一键重试；等待期间额度可能被其他请求消费，因此不能保证下一次必过。
- 售罄：409；发布不确定：503 + UNKNOWN + requestId；需查询或人工核查，不要换新键反复提交。
- Redis 限流不可用：503，停止进入业务，不能降级为无限放行。

观察单用户限流（使用已成功的同一键，避免新增多笔购买）：
```powershell
1..10 | ForEach-Object {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Method Post -Headers $headers 'http://localhost:8081/api/seckill/1?userId=1001'
        "HTTP $($r.StatusCode)"
    } catch {
        $response = $_.Exception.Response
        if ($null -ne $response) { "HTTP $([int]$response.StatusCode), Retry-After=$($response.Headers['Retry-After'])" }
        else { throw }
    }
}
```
循环不是性能测试；网络速度很慢时令牌可能已补回，不保证出现固定数量的 429。精确并发验证看自动化测试与 JMeter 记录。

## 6. 自动化测试与压测
```powershell
$env:SECKILL_MYSQL_TEST='true'
$env:SECKILL_REDIS_TEST='true'
$env:SECKILL_MQ_TEST='true'
$env:SECKILL_PROTECTION_TEST='true'
.\mvnw.cmd test
python perf/run_stage4.py --requests 10000
```
真实集成测试使用独立商品、seckill.test.orders.v4 队列和 seckill:test:stage4 限流键。42 项测试覆盖前三阶段回归及本阶段 14 项测试；具体结果见 [stage4-test-results.txt](stage4-test-results.txt)。

压测使用 18088、独立商品与 seckill.benchmark.v4 队列，顺序启动阶段三/四应用。每组预热 200 次；正常组 10 并发、500 请求、每线程间隔 100ms；过载组 100 并发、10000 请求、无额外间隔。

同机 JMeter 都来自一个 IP，因此本次压测明确将 IP 配额提升到全局相同的 200/s、突发 100，以观察全局限制；生产演示默认 IP 配额仍为 50/s、突发 20。每条压测请求用不同 userId 和 UUID，不测试同一业务操作重试（该项由集成测试覆盖）。

记录 QPS、受理 QPS、平均/最大响应、成功请求、429/其他失败、最终订单、库存、队列观察峰值、耗尽等待、MySQL 状态和系统/应用/MySQL CPU。报告见 [stage4-report.md](stage4-report.md)。

## 7. 当前问题
1. 限流后 Redis 仍需处理每次有效请求；不能抵挡打满带宽、连接或 Redis 的大规模攻击，仍需边缘/网关保护。
2. 阈值需按下游持续消费能力、突发容量和 SLA 调优，不是越大越好。每个应用实例必须共享同一 Redis 与限流 namespace，不能各自配一套键。
3. userId 没有认证、请求结果接口尚未校验归属，不能直接公网开放。IP 限制会影响 NAT 后的正常用户，也不能独自抵挡多 IP 攻击。
4. 永久幂等 marker 占用内存，没有自动归档清理。Redis AOF everysec 仍有宕机数据丢失窗口；手工删 marker/库存键会破坏正常幂等保证。此保证不是跨任意数据丢失场景的 exactly-once。
5. Redis 预扣与 MQ 发布没有跨系统事务，UNKNOWN/PENDING/死信仍需对账；阶段四没有解决阶段三所有一致性问题。
6. Lua 多 key 当前针对单节点 Redis，不能直接用于跨槽 Redis Cluster。限流拒绝没有公平排队，突发请求可能占用其他人的额度。
7. 限流前后短时压测能证明本轮拒绝与库存行为，不是长时间稳定性或最大容量认证。

## 8. 与阶段三比较
| 方面 | 阶段三 | 阶段四 |
|---|---|---|
| 入口请求 | 只要有库存就尝试预扣发布 | 先验证请求键与全局/IP/用户配额 |
| 同一 POST 重试 | 可能再次预扣、产生新订单 | 相同 userId+键返回原请求 |
| 消息重复 | MySQL 流水去重 | 继续沿用 |
| 查询压力 | 无入口速率限制 | 单独全局/IP 查询桶 |
| 高峰行为 | 请求在 MQ 中积压 | 主动返回 429，限制进入 MQ 的流量 |
| 开销 | Redis预扣+MQ确认 | 新增限流脚本、marker检查；正常请求可能更慢 |

本阶段追求过载下可控的受理速度，不追求把所有请求都返回成功。不能用“含大量 429 的总 QPS”宣称成交能力提升。

## 9. 需要掌握的知识点
令牌桶 rate 与 capacity；Redis Lua 原子读判写与多实例共享状态；429/503 与 Retry-After；幂等键作用域、参数冲突与保留期；HTTP 请求幂等与 MQ 消费去重的区别；可信来源 IP 与真实用户身份的区别；压测中主动拒绝与系统失败的分类。

参考：[Redis 限流原理](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)、[Lua 原子执行](https://redis.io/docs/latest/develop/programmability/eval-intro/)。
