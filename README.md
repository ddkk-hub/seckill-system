# seckill-system

分阶段开发的 Java 秒杀系统。当前已完成阶段一：Spring Boot 3.3.5 + Java 21 + MyBatis + MySQL 8；阶段二 Redis + Lua 处于环境准备，尚未实现。

## 本地启动

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
