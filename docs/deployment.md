# 部署与排错手册

## 本机 Java 模式
需要 Java 21、现有 MySQL 8.0.46、Redis、RabbitMQ，以及 application-local.properties 中的本机凭据。先完成阶段一至三建表/库存初始化。

PowerShell：
```powershell
.\mvnw.cmd -DskipTests "-Dseckill.build-name=seckill-system-stage5" package
java -jar target/seckill-system-stage5.jar --spring.profiles.active=stage5
```
停止原来占用 8081 的应用后再启动；如果同名 JAR 正在运行，先停止再重新构建，避免 Windows 文件锁。商品沿用现有 Redis 库存，不要重新初始化为 100。

## Docker 快速启动（通用环境）
前提：JDK 21、Python 3、可用的 Docker Engine/Compose。Windows 构建命令如上；Linux/macOS 使用 `sh ./mvnw -DskipTests -Dseckill.build-name=seckill-system-stage5 package`。
```text
python scripts/init-docker-env.py
docker compose config --quiet
docker build -t seckill-system:stage5 .
docker compose up -d --wait --wait-timeout 240
```
.env 生成随机数据库/root/MQ 密码且不覆盖已有文件。它和 application-local.properties 均被 Git 忽略；.dockerignore 只允许构建产物 JAR 和 Dockerfile 进入上下文，不包含本机凭据。

首次启动时 MySQL 空数据卷执行 deploy/mysql/001-schema.sql，创建完整表结构和 Docker 演示商品 1（库存 100）。bootstrap 离线初始化 Redis；成功后 app 才启动。MySQL、Redis、RabbitMQ 不映射宿主机端口；应用仅映射 127.0.0.1:18081。

示例环境使用固定教学版本；Java 21 基础镜像的实际摘要记录在验收报告中。Compose 中密码通过环境变量注入，可被有 Docker 管理权限的人读取；此方式用于本地演示，正式部署需改用组织的密钥管理和备份策略。

## 本机 Windows + WSL 的实际命令
已在 Ubuntu 中安装 Docker Engine/Compose，数据目录属于 WSL。PowerShell 在 C:\seckill-system 执行：
```powershell
wsl -d Ubuntu -u root --exec /usr/sbin/service docker start
python scripts/init-docker-env.py
wsl -d Ubuntu -u root --cd /mnt/c/seckill-system --exec /usr/bin/docker compose config --quiet
wsl -d Ubuntu -u root --cd /mnt/c/seckill-system --exec /usr/bin/docker compose up -d --pull never --wait --wait-timeout 240
```
本次官方镜像直连超时，经现有 Windows 本机代理下载、逐层校验摘要并导入了 Docker；本机所需镜像已在缓存中，所以以上启动命令使用 --pull never。镜像来源与摘要见 stage5-docker-check.json。没有修改系统代理监听范围。

本机已成功构建应用镜像。需要重新构建时先生成新 JAR，然后：
```powershell
wsl -d Ubuntu -u root --cd /mnt/c/seckill-system --exec /usr/bin/env DOCKER_BUILDKIT=0 /usr/bin/docker build --pull=false -t seckill-system:stage5 .
wsl -d Ubuntu -u root --cd /mnt/c/seckill-system --exec /usr/bin/docker compose up -d --pull never --wait --wait-timeout 240
```
这里的 legacy builder 是本次离线本地镜像构建兼容方式，Docker 已提示弃用；网络和 BuildKit 正常的环境优先使用前面的通用构建命令。`--pull never` 不能用于缺失基础服务镜像的新机器。

## 健康检查和手工验收
```powershell
Invoke-RestMethod 'http://localhost:18081/api/actuator/health'
Invoke-RestMethod 'http://localhost:18081/api/product/1'
$headers = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$r = Invoke-RestMethod -Method Post -Headers $headers 'http://localhost:18081/api/seckill/1?userId=1001'
Start-Sleep -Seconds 1
Invoke-RestMethod "http://localhost:18081/api/seckill/result/$($r.requestId)"
Invoke-RestMethod -Method Post -Headers $headers 'http://localhost:18081/api/seckill/1?userId=1001'
```
健康应为 UP，查询最终订单应为 SUCCESS，同一键重试不再次扣库存。18081 使用独立 Docker 数据，不是原来 8081 的商品数据。

`/api/actuator/health/liveness` 表示应用进程存活；`/api/actuator/health/readiness` 包含数据库、Redis、RabbitMQ 可用性。只有 health 被公开，不公开 env、配置或指标端点。健康响应不含依赖连接明细；健康不代表消息积压已清空，也不代表库存已完成对账。

自动容器验收：先 `python scripts/smoke-stage5.py --mode capture`；保留数据卷重建容器后运行 `--mode replay`。脚本固定 18081，并把重试键保存在 target，已有记录时拒绝再次 capture，以免意外增加购买。

## 日志与异常
```powershell
wsl -d Ubuntu -u root --cd /mnt/c/seckill-system --exec /usr/bin/docker compose logs --tail 100 app
wsl -d Ubuntu -u root --cd /mnt/c/seckill-system --exec /usr/bin/docker compose logs --tail 100 bootstrap
```
本机文件 logs/seckill.log；容器文件 /app/logs/seckill.log，保存于 app-logs 命名卷。单文件 20MB、历史七天、归档总量 200MB（当前活动文件另计）。日志滚动配置已启用，本轮短时测试不验证七天保留行为。

错误体包含 timestamp、status、code、message、traceId；用 X-Trace-Id 对应服务端日志定位异常。400 表示参数/请求头问题，409 表示售罄或幂等键冲突，429 遵守 Retry-After，503 表示依赖不可用或操作不确定。业务 UNKNOWN 响应仍保留 requestId/status/orderId，便于后续查询，不包装成丢失业务编号的错误体。

排错顺序：先确认 profile 和端口 → health → app/依赖日志 → 用 traceId 找 HTTP 记录 → 用 requestId 找消息和订单流水。不要把“正在排队”和“请求失败”混为一谈。

## 停止、重启与数据
```text
docker compose stop
docker compose start --wait
docker compose down
docker compose up -d --wait
```
普通 down 删除该项目容器/网络，保留命名卷。**不要加 -v**，否则会删除演示数据库、库存和日志。首次数据库初始化脚本不会在已有数据卷上再次执行；后续表结构升级需单独设计迁移。本次只验证保留卷的容器重建，没有模拟断电、存储损坏或跨机器恢复。

如 Redis 卷丢失但 MySQL 基线仍在，bootstrap 会拒绝自动补库存；必须停写并根据订单及未决请求离线对账。不要删除基线表绕过检查。

## 运行边界
单节点部署、无登录认证、Redis/MQ 跨系统事务窗口和人工对账问题仍存在；Redis 未设密码但不对宿主机暴露端口，仅用于隔离的 Compose 网络。用于公开展示和本机学习，不能直接当作公网生产部署。

参考：[Compose 启动依赖条件](https://docs.docker.com/compose/how-tos/startup-order/)、[Compose 服务定义](https://docs.docker.com/reference/compose-file/services/)。
