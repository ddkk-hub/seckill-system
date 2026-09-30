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

## 阶段四：2026-09-28 限流与请求幂等

- [设计与验收](stage4.md)、[完整代码](stage4-source.md)、[实测报告](stage4-report.md)、[42 项测试](stage4-test-results.txt)。
- 原始证据：`perf/results/stage4-20260928-192252/`。同一 JAR 顺序运行 stage3 无保护和 stage4 有保护模式。
- 共 21400 请求，受理/最终订单 12219，主动拒绝 429 共 9181，其他失败 0；包含预热。
- 过载组各 10000 请求：无保护受理 10000、完成核验额外等待 38.19s；有保护受理 826、拒绝 9174、额外等待 0.33s。正常组各 500 请求均受理。
- 观察队列峰值 8508 → 0；管理 API 统计约有 5 秒延迟，0 只代表采样未捕获积压，不能宣称从未有瞬时积压。
- 单机 JMeter 闭环、仅一轮，主动拒绝会改变发送节奏；不作为长期稳定性或最大容量结论。
- [源码 ZIP](baselines/stage4-source.zip)、[SHA256 清单](baselines/stage4-manifest.json)。本阶段尚未自动提交或推送 GitHub。

## 阶段五：2026-09-29 工程化验收

- 阶段四已推送 GitHub，提交 `9e1e8cb`。阶段五当前为本地交付，等待用户验收。
- [设计与验收](stage5.md)、[完整代码](stage5-source.md)、[53 项测试](stage5-test-results.txt)、[压测报告](stage5-report.md)、[Docker 证据](stage5-docker-check.json)。
- 最终构建实验：`perf/results/stage5-20260929-151306/`。21400 请求，3085 最终订单，18315 次 429，其他失败 0；库存与订单守恒、pending=0。
- HTTP 406 修复前实验 `perf/results/stage5-20260929-144850/` 原样保留，含当时源码快照；不与最终结果混合。
- Docker 独立环境验证非 root、健康、幂等和保留卷重建；库存 100 → 99，与本机商品库存分开。
- 阶段二、三、四历史归档分别核对 62、57、34 份文件 SHA256 一致。
- [源码归档](baselines/stage5-source.zip)、[校验清单](baselines/stage5-manifest.json)。

## 阶段六：2026-09-29 登录认证与订单归属

- 阶段五已验收并推送 `866bd0f`。阶段六由用户选择扩展目标，当前为本地交付等待验收。
- [设计/九项讲解](stage6.md)、[完整代码](stage6-source.md)、[66 项测试](stage6-test-results.txt)、[压测报告](stage6-report.md)、[Docker 验收](stage6-docker-check.json)。
- 原始实验 `perf/results/stage6-20260929-161625/`：21400 请求，3256 最终订单，18144 次 429，其他失败 0，库存守恒且 pending=0。
- 相同最终 JAR、100 个预建登录身份，对比 stage5 与 stage6。正常组平均响应 15.95ms → 20.32ms；阶段六过载成功受理 QPS 214.83。不能据此推导密码登录吞吐或长期容量。
- 归档保留 JTL、应用/JMeter 日志、资源采样、环境配置和脚本；访问令牌 CSV 不归档，实验结束已撤销临时登录态。
- [源码 ZIP](baselines/stage6-source.zip)、[SHA256 清单](baselines/stage6-manifest.json)。
