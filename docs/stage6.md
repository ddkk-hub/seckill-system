# 阶段六：登录认证与订单归属校验

## 1. 阶段目标
阶段五已验收并推送 GitHub，提交 `866bd0f`。用户确认新增阶段六：注册、登录、退出、登录态过期、服务端确定购买者身份，并防止跨用户查询订单和异步结果。

请求流程：注册账号 → 登录验证密码 → 返回 Bearer Token → Redis 查询登录身份 → 限流 → Redis 预扣 → MQ → MySQL 订单；查询时增加资源归属校验。

## 2. 设计原因
认证回答“你是谁”，授权回答“你是否可以访问这笔订单”。只要求请求带 Token 还不够，必须把令牌里的服务端身份与订单所有者比较。

密码使用 Spring Security Crypto 的 PBKDF2-HMAC-SHA256（600000 次迭代、16 字节随机盐），带版本前缀存储，不保存明文。此处仅引入密码编码库，没有引入完整 Spring Security 过滤器链。MVC 拦截器默认保护请求，只放行明确列出的注册/登录和 GET 商品/test/健康端点；新增接口也默认需要身份。未知账号也执行一次虚拟密码校验，登录失败使用相同响应，减少账号枚举信息。

随机令牌由 SecureRandom 生成 32 字节，再编码为 43 字符 URL-safe Base64。Redis 键只保存令牌的 SHA256 摘要，值为用户 ID，固定 TTL 1800 秒，不滑动续期；退出删除当前令牌。密码哈希只在注册、登录使用，下单只查 Redis。依赖不可用返回 503，不降级成匿名下单。

选择服务器登录态便于立即撤销当前会话，代价是每个受保护请求增加一次 Redis 查询。Token 是持有者凭据，需要保密；本机演示走回环 HTTP，正式网络应使用 HTTPS。没有把 Token 存在 Cookie，也不接受 URL 中的 Token。

新用户 ID 由服务端随机分配，保持 JavaScript 安全整数范围，并检查已有用户/订单/异步记录，避免把历史客户端传入的 ID 自动认领为新账号。用户名规范为小写、3～32 个 ASCII 字母/数字/下划线；密码长度 12～128 字符。

注册/登录共用全局、IP、账号三层令牌桶，在密码计算前检查。默认全局 5/s、突发 10；IP 和账号各 1/s、突发 5。下单仍沿用阶段四的限流和请求幂等。

异步请求还未落库时，读取服务端保存的 Redis reservation payload 校验 userId；落库后从 MySQL 去重账本核对。即使 Redis 请求缓存过期，自己的已完成订单仍能查询。不存在和不属于本人均返回 404。

## 3. 新增及修改文件
新增（完整路径以项目根目录为起点）：
- `src/main/java/com/ddk/seckill/entity/AuthUser.java`：账号数据，密码字段不序列化。
- `src/main/java/com/ddk/seckill/mapper/AuthUserMapper.java`：账号查询、唯一约束插入、历史 ID 冲突检查。
- `src/main/java/com/ddk/seckill/service/AuthService.java`：密码校验、会话签发/解析/撤销、登录限流。
- `src/main/java/com/ddk/seckill/service/OwnedOrderService.java`：订单和异步请求归属校验。
- `src/main/java/com/ddk/seckill/controller/AuthWebConfiguration.java`：默认保护的 MVC 拦截器，身份只保存在当前请求属性。
- `src/main/java/com/ddk/seckill/controller/AuthController.java`：注册、登录、当前身份、退出接口。
- `src/main/java/com/ddk/seckill/controller/AuthenticatedSeckillController.java`：认证后的商品、订单、下单、结果接口。
- `src/main/resources/application-stage6.properties`、`src/main/resources/db/stage6.sql`：阶段配置与增量用户表。
- `src/test/java/com/ddk/seckill/AuthenticationIntegrationTest.java`：13 项认证集成测试。
- `Dockerfile.stage6`、`Dockerfile.stage6.dockerignore`、`compose.stage6.yaml`、`deploy/mysql-stage6/`：独立 18082 演示部署。
- `scripts/smoke-stage6.py`：真实 HTTP 验收，凭据只保存在忽略的 target 目录。
- `perf/run_stage6.py`、`perf/stage6.jmx`、`perf/stage6-baseline.jmx`、`perf/report_stage6.py`：同条件认证开销实验。
- `docs/stage6*`、`docs/baselines/stage6*`：讲解、完整代码、测试、部署验收、压测、源码与摘要。

修改：`.dockerignore` 增加阶段六 Dockerfile/JAR 允许项，兼容本机 legacy builder（其未采用专属忽略文件）；`pom.xml` 仅增加受 Boot 版本管理的 spring-security-crypto；`controller/ProtectedSeckillController.java` 在启用认证时停用，由认证版本替代；README、实验索引、.gitattributes 增加阶段六入口。包名与 controller/service/entity/mapper 主目录不变。

## 4. 完整代码
[所有完整文件及用途](stage6-source.md)，[可复现源码 ZIP](baselines/stage6-source.zip)，[SHA256 清单](baselines/stage6-manifest.json)。不会把本机配置、真实口令或运行中的访问令牌收录到归档。

## 5. 启动与接口测试
Docker 使用独立项目 `seckill-auth-demo`、独立命名卷，应用仅映射 `127.0.0.1:18082`。与阶段五的 18081 数据互不相通。

```powershell
.\mvnw.cmd "-Dseckill.build-name=seckill-system-stage6" -DskipTests package
python scripts/init-docker-env.py
wsl -d Ubuntu -u root --cd /mnt/c/seckill-system --exec /usr/bin/env DOCKER_BUILDKIT=0 /usr/bin/docker build --pull=false -f Dockerfile.stage6 -t seckill-system:stage6 .
wsl -d Ubuntu -u root --cd /mnt/c/seckill-system --exec /usr/bin/docker compose -f compose.stage6.yaml up -d --pull never --wait --wait-timeout 240
```
通用 Docker 环境去掉 wsl 前缀，使用 `docker build -f Dockerfile.stage6 -t seckill-system:stage6 .`。本机 legacy builder 只是已缓存镜像的兼容构建方式。

本机 Java 运行前，在 MySQL 客户端执行 `source C:/seckill-system/src/main/resources/db/stage6.sql;`；然后使用 `java -jar target/seckill-system-stage6.jar --spring.profiles.active=stage6 --server.port=18083`。原有阶段一至三的结构/库存初始化仍需事先完成。迁移只增加用户表，不迁移旧订单所有权。不要同时把旧的无认证配置向业务用户开放；旧配置仅用于本机教学基线。

### 手工验收（纯 PowerShell 地址）
以下创建你自己的新账号。密码只保留在当前终端变量中，不要发到聊天或提交 Git。
```powershell
$base = 'http://localhost:18082/api'
Invoke-RestMethod "$base/actuator/health"
$username = 'learner_' + [guid]::NewGuid().ToString('N').Substring(0,10)
$password = [guid]::NewGuid().ToString('N')
$body = @{ username = $username; password = $password } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/auth/register" -ContentType 'application/json' -Body $body
$login = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType 'application/json' -Body $body
$headers = @{ Authorization = "Bearer $($login.accessToken)"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
Invoke-RestMethod -Uri "$base/auth/me" -Headers $headers
$receipt = Invoke-RestMethod -Method Post -Uri "$base/seckill/1" -Headers $headers
Start-Sleep -Seconds 1
Invoke-RestMethod -Uri "$base/seckill/result/$($receipt.requestId)" -Headers $headers
Invoke-RestMethod -Method Post -Uri "$base/seckill/1" -Headers $headers
Invoke-RestMethod -Method Post -Uri "$base/auth/logout" -Headers $headers
```
预期：注册返回新 userId；登录产生会话；me 对应自己的 ID；首次下单 202，查询最终 SUCCESS；同一幂等键重试返回相同订单，不再次扣库存；退出 204。此手工流程会购买一件演示商品。之后再调用 me 应返回 401，PowerShell 将显示 HTTP 错误，这是预期行为。若立即查询仍 PENDING，稍后继续查询，不能更换幂等键重复购买。

| 场景 | 预期 |
|---|---|
| 无 Authorization 下单或查订单 | 401 + WWW-Authenticate: Bearer |
| 正确令牌但传 userId | 400 |
| 用户 B 查询用户 A 的 requestId/orderId | 404 |
| 令牌过期或退出后再用 | 401 |
| 登录突发超过配额 | 429 + Retry-After |
| 认证依赖不可用 | 503，库存不动 |

完整回归：设置 `SECKILL_MYSQL_TEST`、`SECKILL_REDIS_TEST`、`SECKILL_MQ_TEST`、`SECKILL_PROTECTION_TEST`、`SECKILL_ENGINEERING_TEST`、`SECKILL_AUTH_TEST` 为 `true`，执行 Maven package。最终 66 项全部通过。常规默认 Maven 测试会跳过需要外部依赖的集成测试，不能把默认结果当作完整验收。

## 6. 压测
使用 JMeter 5.6.3，对同一最终 JAR 分别开启 stage5、stage6；每种模式预热 200、正常 500、过载 10000 请求。正常 10 并发/100ms 间隔，过载 100 并发无额外间隔。

100 个独立测试账号预建短期登录态；同一身份池用于两组，阶段五通过 userId 传入、阶段六通过 Authorization。测试账号不允许密码登录，令牌只写在 target 临时 CSV，实验结束撤销令牌并删除测试账号/CSV。原始 JTL 不保存请求头和响应体。密码登录开销不计入该实验。

全局/实验 IP 配额均 200/s、突发 100；实验用户配额也临时提高至 200/s、突发 100，以隔离全局限流和认证开销，日常用户配额仍为 2/s、突发 3。不要把实验覆盖值当作上线建议。

运行 `python perf/run_stage6.py --requests 10000`。报告记录总 QPS、受理 QPS、平均/最大响应、202/429/其他失败、最终订单、库存、MySQL 指标和 CPU。详见 [实测报告](stage6-report.md)。总 QPS 包含快速拒绝，不能代表实际成交能力。

## 7. 当前问题
- 仍无密码找回、密码修改、账号封禁、管理员、MFA、登录设备管理或所有会话统一撤销；注册不能保证一人一账号。
- 本次只有当前 Token 撤销，注销与已通过校验的并发请求之间存在窗口，不能撤销已经受理的订单。
- 登录态固定 30 分钟，无刷新令牌；Redis 故障会使受保护接口不可用。丢失登录态时重新登录，不绕过认证。
- MVC 拦截器适用于当前同步 MVC 控制器，不覆盖将来新增的独立 Servlet、WebSocket 或其他网络入口；扩展这些入口需重新设计认证边界。
- 旧阶段无认证接口仍可按旧 profile 启动，仅保留作本地教学；正式使用只能开放阶段六入口，不能并行暴露旧应用访问同一数据库。
- 历史匿名订单不能自动归属；Redis/MQ 跨系统一致性、自动对账与高可用问题仍未解决。
- 单轮同机短时压测未覆盖登录风暴、长期容量、故障恢复或端到端 P99。

## 8. 与阶段五比较
| 项目 | 阶段五 | 阶段六 |
|---|---|---|
| 用户身份 | 客户端传 userId | 服务端账号 + Redis 登录态 |
| 订单/结果查询 | 知道 ID 即可查询 | 核验本人归属 |
| 密码 | 无 | 带盐 PBKDF2 哈希 |
| 退出/过期 | 无 | 当前会话撤销、固定 TTL |
| 热路径成本 | 原有预扣/限流 | 额外 Redis 登录态查询；查订单增加归属读取 |
| 性能结论 | 基线 | 增加身份边界，不预设吞吐提升，依据报告比较 |

## 9. 核心知识点
- 哈希不是加密：不能“解密密码”；登录时对输入执行同一带盐算法并比较。
- 随机盐防止相同密码产生相同存储值，慢哈希提高离线猜测成本；会话校验不应反复执行慢哈希。
- Bearer Token 的持有者可代表账号，随机性、保密、TTL 和撤销缺一不可。
- 认证与资源级授权必须同时存在；仅在页面隐藏别人的订单不构成后端保护。
- 请求属性随请求结束，不使用未清理的线程局部变量保存身份，避免线程复用导致串号。
- 幂等范围仍为“用户 + 幂等键”，同键不同用户互不影响；幂等不等于一人限购一件。
- 异步业务要覆盖 PENDING 与 SUCCESS 两种归属存储位置，不能只保护最终订单。

参考：[Spring 6.3.4 PBKDF2 API](https://docs.spring.io/spring-security/site/docs/6.3.4/api/org/springframework/security/crypto/password/Pbkdf2PasswordEncoder.html)、[OWASP 会话管理](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)。

## 2026-09-30 收尾验收

最终测试和压测执行于 2026-09-29，原始证据日期不变。独立 Docker 环境已实际验证注册/登录、匿名拒绝、伪造 userId 拒绝、跨用户订单/结果查询拒绝、同键幂等和退出撤销；初次演示购买后库存 99。保留数据卷重启后，于次日重新登录核对原请求，仍返回相同订单且不再次扣减库存。

需要复核已有演示请求时，执行 `python scripts/smoke-stage6.py --mode replay`。该命令依赖本机 target 中未提交的测试凭据；换机器应在新演示库运行 `--mode capture`，不能期望私有凭据包含在源码归档中。
