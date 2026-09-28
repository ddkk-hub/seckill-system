# 秒杀系统实验索引

## 阶段一：MySQL 基线（已实测）

- 实验时间：2026-09-26，目录时间戳 215046，时区 Asia/Shanghai。
- [报告](stage1-report.md)：环境、并发、请求数量、QPS、平均/最大响应、成功/拒绝/失败、数据库锁等待与 CPU、限制分析。
- 原始数据：`perf/results/20260926-215046/` 下 warmup、correctness、users-1、users-10、users-100 五组 `.jtl`。
- 监控数据：同目录 `metrics.json`，包括每组参数、商品 ID、库存与订单核对、MySQL 状态前后值及 CPU 样本。
- 工具：`perf/stage1.jmx`、`perf/run_stage1.py`、`perf/report.py`。
- [源码快照](baselines/stage1-source.zip)：归档时源码，数据库密码已替换为环境变量；不是历史 Git 提交。
- [完整源码文本](stage1-source.md)。
- [SHA-256 清单](baselines/stage1-manifest.json)：用于检测后续文件是否被意外改写，包含每组 JTL 条数和订单一致性复核结果。
- JTL 合计 31200 请求，其中成功 30300、售罄 900、其他失败 0。包含 200 次预热。

注意：日志文件有保存；重新核对确认 application.log 包含应用启动等日志，此前运行中读取时尚未写出，不能据此称其为空。日志不等同于完整请求审计。没有生成 JMeter HTML 仪表盘；原始 JTL 可供后续生成。每档只有一轮，JMeter CPU 缺少有效样本，短场景 CPU 样本不足；不能把未采集的数据解释为零。

## 阶段一：用户手动验收（2026-09-27）

依据用户提供的终端输出：订单 30312、30313 查询正确；两次购买使商品 1 库存从 100 减至 98；不存在商品返回 404；非法/缺失 userId 返回 400 且库存仍为 98。Maven 输出 8 项测试，失败 0、错误 0、跳过 0，BUILD SUCCESS。此为验收记录，不是新一轮性能压测。

## 阶段二：Redis + Lua（已实测）

- [阶段二报告](stage2-report.md)：同条件 MySQL/Redis 对照、CPU、数据库压力和限制。
- 首轮：`perf/results/stage2-20260927-100654/`，10 组 JTL，含两种模式的预热、售罄、1/10/100 并发。
- 复测：`perf/results/stage2-20260927-101413/`，3 组 JTL，独立复测 Redis 单并发。首轮该场景与商品初始化有重叠，保留并标注排除；其他场景不受该初始化重叠影响。
- 共 73600 次请求，70900 次成功、2700 次售罄、其他失败 0；库存/订单一致性校验通过。
- 各目录保存 metrics.json、environment.json、执行脚本快照、JMeter 日志、初始化日志和应用日志。environment.json 记录测试应用 JAR 的 SHA-256。
- [20 项测试结果](stage2-test-results.txt)、[完整代码](stage2-source.md)、[源码归档](baselines/stage2-source.zip)、[校验清单](baselines/stage2-manifest.json)。
- 原始阶段一文件未改写；跨阶段主要比较本次重测的 MySQL 对照组，避免把 TLS/WSL 环境变化都计入 Redis 收益。

## 保存范围

实验资料已纳入本次本地 Git 提交范围，尚未推送远端。源码快照已去掉数据库密码；当前密码保存在被忽略的 application-local.properties 中，仓库配置只保留环境变量引用。不要删除 perf/results 或用新结果覆盖旧报告。Maven clean 会删除 target 中的工具和构建产物，以上 docs 和 perf/results 不受影响。

## 阶段二：2026-09-28 恢复与 HTTP 核对

Redis 恢复后的全部库存基线及只读 HTTP 检查保存到 [stage2-restart-check.json](stage2-restart-check.json)。不新增购买，不重复生成压测数据，也不改变 2026-09-27 的性能测量结果。

## 阶段三：2026-09-28 RabbitMQ 异步下单

- [阶段三实测报告](stage3-report.md)、[完整代码](stage3-source.md)、[28 项测试结果](stage3-test-results.txt)。
- 同条件 Redis/异步对比：`perf/results/stage3-20260928-132022/`，共 20400 请求，最终 18600 单，售罄 1800，其他失败 0。
- 削峰实验：`perf/results/stage3-burst-20260928-132608/`，500 个 202，暂停时队列 500 / 订单 0，恢复后订单 500 / pending 0。
- 保留原始 JTL、应用/JMeter 日志、资源采样、环境和运行脚本快照。100 并发受理 QPS 1929.26，不能等同于订单完成 QPS。
- 阶段二 62 份历史证据文件 SHA256 校验一致；历史报告和源码归档未覆盖。
- [阶段三源码归档](baselines/stage3-source.zip)、[校验清单](baselines/stage3-manifest.json)。此次仅写入本地工作区，未自动提交或推送 GitHub。
