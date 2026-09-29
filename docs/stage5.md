# 阶段五：项目工程化

## 1. 阶段目标
在阶段四可运行、可限流、可幂等的业务基础上，补齐定位故障和复现部署需要的工程能力。交付轻量日志关联、统一异常、健康检查、Docker Compose、README、架构图和完整实验资料。

## 2. 设计原因
HTTP 每次请求有独立 traceId，MQ 使用业务 requestId 关联订单，可从错误响应追到服务端日志。统一错误体保留 HTTP 状态和 Retry-After，不暴露 SQL/堆栈；业务 UNKNOWN 继续保留请求编号，避免破坏异步查询语义。

日志记录路由模板而非原始查询串，控制敏感数据和日志注入风险；文件按大小/时间滚动，避免无限增长。文件日志与访问日志有 CPU/IO 成本，必须实测。

健康检查区分进程存活与依赖就绪，只公开 health。Compose 按“依赖健康 → 离线初始化成功 → 应用健康”启动，避免只启动进程却尚不能访问数据库。应用以 UID 10001 运行；独立命名卷持久化数据与日志。

## 3. 文件修改
新增：
- src/main/java/com/ddk/seckill/entity/ApiError.java：结构化错误响应。
- src/main/java/com/ddk/seckill/controller/ApiExceptionHandler.java：状态映射、错误隐藏、保留 Retry-After/Allow。
- src/main/java/com/ddk/seckill/controller/RequestTraceFilter.java：X-Trace-Id、MDC 生命周期和 HTTP 访问日志。
- src/main/resources/application-stage5.properties：日志、健康和优雅停机配置，沿用阶段四保护。
- src/main/resources/application-docker.properties：容器内服务名和数据源配置。
- src/test/java/com/ddk/seckill/EngineeringIntegrationTest.java：新增十一项异常、日志和健康验证。
- Dockerfile、.dockerignore、compose.yaml、.env.example、deploy/mysql/001-schema.sql：镜像、隔离环境和空卷初始化。
- scripts/init-docker-env.py、scripts/smoke-stage5.py：本地随机凭据及容器验收。
- perf/run_stage5.py、perf/report_stage5.py：同条件工程化配置对比。
- docs/architecture.md、docs/deployment.md、docs/stage5-source.md、docs/stage5-report.md、docs/stage5-test-results.txt、docs/stage5-docker-check.json、docs/baselines/stage5-*：教学、运维、源码和证据。

修改：pom.xml 增加 Actuator；service/AsyncOrderConsumer.java 与 service/ProtectedSeckillService.java 增加业务日志关联；README.md 重整项目入口；.gitignore 排除运行日志；.gitattributes 和 docs/experiments.md 维护证据规则/索引。未改变包名和四个 Java 主子目录。

## 4. 完整代码
所有当前主代码、工程化测试、配置与部署文件的完整内容见 [stage5-source.md](stage5-source.md)。本机凭据不收录。可复现源码 ZIP 和 SHA256 清单位于 docs/baselines。

## 5. 启动与接口测试
[部署手册](deployment.md) 提供本机 Java、通用 Docker 与本机 WSL 的实际命令。本机 Java profile=stage5 使用 8081；Docker profile=stage5,docker 使用 18081，数据库完全独立。

Docker 验收：
```powershell
Invoke-RestMethod 'http://localhost:18081/api/actuator/health'
Invoke-RestMethod 'http://localhost:18081/api/product/1'
```
预期 health.status=UP；商品为 Docker demo iPhone。已经执行的容器验收购买一件后，演示库存为 99；这与本机原商品 95 没有关系。

重试验证、错误响应验证和重建容器后的验证命令见部署手册。完成标准：53 项测试通过；非 root 容器健康；首次购买与重试只成交一单；缺少请求头返回统一 400 且带 traceId；保留卷重建后订单/库存/幂等状态仍一致；README 能指向完整源码和原始压测资料。

## 6. 压测设计与报告
使用 JMeter 5.6.3，同一阶段五 JAR 分别以 stage4 与 stage5 配置运行；共同的消费者/发布日志代码在两组中均存在，因此测量的是配置层面的增量开销，不能视为全部阶段四到五代码改动的独立因果实验。

每组预热 200 请求；正常组 10 并发 500 请求、每线程间隔 100ms；过载组 100 并发 10000 请求。两组都开启阶段四限流，全局/实验 IP 配额均为 200/s、突发 100。样本只有一轮，闭环 JMeter 发送节奏受响应时间影响。

记录 QPS、受理 QPS、平均/最大响应、成功/拒绝/其他失败、最终订单、库存、数据库压力、CPU、队列和结束后的耗尽等待。原始记录与结论见 [stage5-report.md](stage5-report.md)。Docker 环境另做部署/重建验收，本轮性能对比针对本机 JAR，不把两种运行环境的结果混在一起。

## 7. 当前问题
- 日志和健康检查帮助排错，不会自动修复业务一致性问题；UNKNOWN、PENDING、死信仍需人工核查。
- 仍无登录认证、用户归属校验、自动对账、幂等归档、Redis/MQ 高可用与备份恢复流程。
- 日志含内部异常堆栈，需要保护读取权限；当前按时间/大小滚动，没有日志平台或报警。
- Docker 的环境变量凭据是本地演示方式；Redis 仅靠隔离网络而无认证，正式部署需额外设计。
- 镜像版本/运行时更新需维护；Java 基础镜像使用 tag，实际已验证摘要保存在报告中。
- MySQL 初始化脚本只对空卷执行，没有引入数据库版本迁移工具。容器重建通过不等于断电恢复、零数据丢失或生产 SLA。
- 本机网络对 Docker Hub 直连受阻，使用已有代理导入官方镜像并校验摘要；构建时使用 legacy builder 的本地兼容方式，需留意 Docker 后续弃用。

## 8. 与阶段四比较
| 方面 | 阶段四 | 阶段五 |
|---|---|---|
| 错误响应 | 框架默认错误体 | 统一 code/message/traceId，保留状态与响应头 |
| 故障定位 | 有限业务错误日志 | HTTP traceId、业务 requestId、orderId 关联 |
| 文件日志 | 无阶段专属滚动配置 | 单文件 20MB、历史七天、归档总量 200MB |
| 就绪检查 | 简单 test 接口 | health/liveness/readiness |
| 部署 | 手工本机服务 | 独立 Compose 数据卷、健康依赖、一次性初始化 |
| 展示与复现 | 分阶段材料 | README、架构、部署手册、完整证据索引 |

工程化的收益是可维护性和可复现性；访问/文件日志通常增加开销，不承诺 QPS 提升。

## 9. 核心知识点
Servlet Filter 与 ControllerAdvice 的职责；MDC 线程上下文必须清理；HTTP traceId 与异步业务 requestId 的区别；业务预期错误与内部异常的映射；健康、存活、就绪的区别；镜像与容器、命名卷、Compose 依赖条件、空卷初始化；压测的环境控制与证据保留。

参考：[Spring Boot 健康端点](https://docs.spring.io/spring-boot/3.4/reference/actuator/endpoints.html)、[Docker Compose 启动顺序](https://docs.docker.com/compose/how-tos/startup-order/)。本项目实际 API 行为以 Spring Boot 3.3.5 的本地编译与测试结果为准。
