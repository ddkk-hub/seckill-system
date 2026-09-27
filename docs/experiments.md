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

## 阶段二：Redis + Lua（进行中）

尚无阶段二实测结果。阶段二使用独立目录和报告，不改写阶段一原始实验。

## 保存范围

实验资料已纳入本次本地 Git 提交范围，尚未推送远端。源码快照已去掉数据库密码；当前密码保存在被忽略的 application-local.properties 中，仓库配置只保留环境变量引用。不要删除 perf/results 或用新结果覆盖旧报告。Maven clean 会删除 target 中的工具和构建产物，以上 docs 和 perf/results 不受影响。
