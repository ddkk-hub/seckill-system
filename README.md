# seckill-system

分阶段开发的 Java 秒杀系统。当前已实现阶段一 MySQL、阶段二 Redis + Lua、阶段三 RabbitMQ 异步下单，以及阶段四限流与请求幂等。阶段四下单必须携带 Idempotency-Key；请先阅读 [阶段四运行与验收说明](docs/stage4.md)。

## 阶段一历史启动方式（已迁移 Redis 的商品不要用此模式售卖）

创建 MySQL 数据库 seckill 和商品表后，执行 `src/main/resources/db/stage1.sql` 创建订单表。现有开发数据库已完成此步骤。商品表要求 InnoDB、id 主键、name、stock、price 字段，具体说明见阶段一文档。

在项目根目录创建 `application-local.properties`，填写自己的数据库密码：

```properties
spring.datasource.password=你的本机数据库密码
```

该文件已被 Git 忽略。也可使用环境变量 `SPRING_DATASOURCE_PASSWORD`；如需更改数据库地址或用户名，可使用对应的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`。

```powershell
.\mvnw.cmd spring-boot:run
```

- 商品查询：`GET http://localhost:8081/api/product/1`
- 购买一件：`POST http://localhost:8081/api/seckill/1?userId=1001`
- 订单查询：`GET http://localhost:8081/api/order/{id}`

## 测试

```powershell
.\mvnw.cmd test
$env:SECKILL_MYSQL_TEST='true'
.\mvnw.cmd test
Remove-Item Env:SECKILL_MYSQL_TEST
```

默认只跑单元测试；启用环境开关后运行真实 MySQL 测试，需要已建好表。

## 开发与实验记录

- [阶段一教学说明](docs/stage1.md)
- [阶段一完整源码快照文本](docs/stage1-source.md)（历史版本；当前配置以源码和本 README 为准）
- [实验索引](docs/experiments.md)
- [阶段一压测报告](docs/stage1-report.md)
- [阶段二设计](docs/stage2-plan.md)
- [阶段二环境准备](docs/stage2-environment.md)

`perf/results/` 保存原始实测证据。`target/` 是可删除的构建和工具目录，不纳入 Git。历史源码快照中的数据库密码已移除。

当前是教学阶段：尚无认证、请求幂等和限流；初始压测不是生产容量结论。

## 阶段二启动（已迁移商品使用此模式）

本机 Redis 与商品 1 的 98 件库存已准备好。新环境先执行 `src/main/resources/db/stage2.sql`，并按 [阶段二说明](docs/stage2.md) 停止商品写入后初始化库存。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-redis.ps1
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=stage2'
```

默认不带 profile 的命令仍为 MySQL 模式，不得用于已迁移到 Redis 的同一商品。阶段二实时库存以 Redis 为准，MySQL product.stock 是导入基线。

- [阶段二完整说明](docs/stage2.md)
- [完整代码](docs/stage2-source.md)
- [对比压测报告](docs/stage2-report.md)

运行全部 20 项测试需同时设置 `SECKILL_MYSQL_TEST=true` 和 `SECKILL_REDIS_TEST=true`。源码密码仍在被 Git 忽略的本地配置里。

## 阶段三（RabbitMQ 异步下单）

运行与验收：[docs/stage3.md](docs/stage3.md)。完整代码：[docs/stage3-source.md](docs/stage3-source.md)。实测报告：[docs/stage3-report.md](docs/stage3-report.md)。

启用 stage3 后下单返回 202 和 requestId，请查询 `/api/seckill/result/{requestId}` 确认最终订单。阶段一、二历史报告保留，不能把 202 直接当作成交成功。

## 阶段四：限流与请求幂等

[设计与验收](docs/stage4.md) · [完整代码](docs/stage4-source.md) · [压测报告](docs/stage4-report.md) · [42 项测试](docs/stage4-test-results.txt)

```powershell
.\mvnw.cmd -DskipTests "-Dseckill.build-name=seckill-system-stage4" package
java -jar target/seckill-system-stage4.jar --spring.profiles.active=stage4
```

先停止占用 8081 的旧应用，再启动新 JAR。同一购买操作重试必须复用同一个 UUID 请求头 `Idempotency-Key`；超限返回 429，遵守 Retry-After。换一个新键会被视为新购买操作。
