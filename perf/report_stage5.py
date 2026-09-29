"""Generate the engineering-stage report from saved measurements."""
import json,statistics,sys
from pathlib import Path
run=Path(sys.argv[1]);rows=json.loads((run/'metrics.json').read_text());env=json.loads((run/'environment.json').read_text());assert len(rows)==6
def avg(values):return statistics.mean(values) if values else 0
lines=['# 阶段五压测报告','', '阶段：工程化配置的本机短时对比。两组均使用同一阶段五 JAR、阶段四限流和幂等；stage5 额外开启 HTTP 访问日志、滚动文件、统一异常和健康配置。共同的业务日志代码在两组都存在。','',f"测试环境：{env['platform']}，{env['logical_cpus']} 逻辑核，{env['memory_bytes']/1024**3:.1f} GiB 内存；Java 21、Spring Boot 3.3.5、MySQL {env['mysql_version']}、Redis {env['redis_version']}、RabbitMQ {env['rabbitmq_version']}；JMeter 5.6.3 与应用/数据库同机。Docker 演示环境在压测期间停止，以减少干扰。",'',f"并发/请求数量：每组预热 10 并发 200 请求；正常组 10 并发 500 请求、每线程间隔 100ms；过载组 100 并发 {env['requests']} 请求、无额外间隔。全局和本次实验 IP 限制均为 200/s、突发 100；日常 IP 默认仍为 50/s、突发 20。消费者 2、prefetch 10、连接池 10、ramp 1 秒，仅一轮。",'',f"证据目录：`{run.as_posix()}`。运行 JAR SHA256：`{env['jar_sha256']}`。",'','## 请求与订单','', '| 配置/场景 | 请求 | 总 QPS | 受理 QPS | 平均 ms | 最大 ms | 202 成功受理 | 429 拒绝 | 其他失败 | 最终订单 | 剩余库存 | 核验额外等待秒 |','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
 c=r['codes'];other=sum(v for k,v in c.items() if k not in ['202','429'])
 lines.append(f"| {r['mode']}/{r['case']} | {r['requests']} | {r['http_qps']:.2f} | {r['accepted_qps']:.2f} | {r['avg_ms']:.2f} | {r['max_ms']} | {c.get('202',0)} | {c.get('429',0)} | {other} | {r['orders']} | {r['remaining_stock']} | {r['drain_wait_after_jmeter_seconds']:.2f} |")
lines+=['','成功受理仍需等待数据库下单；所有案例已验证最终订单 = 202 数量，剩余库存 + 订单 = 初始库存，pending=0。429 属于主动拒绝，在 JMeter 中也是非成功请求；不能把它从总失败/拒绝数量中隐藏。总 QPS 含快速 429，不能代表成交能力。响应均值/最大值是 HTTP 延迟，未采集逐单端到端延迟。','','## 数据库与 CPU','', '| 配置/场景 | Questions 增量 | 行锁等待次数/ms | MySQL 平均 CPU% | 应用平均 CPU% | 系统平均/峰值 CPU% | Redis平均CPU% | 观察队列峰值 | 完成 QPS 下界 |','|---|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
 samples=r['samples'];a=r['status_after'];b=r['status_before'];system=[s['system_cpu'] for s in samples]
 mysql=[v for s in samples for k,v in s.items() if k.startswith('mysql-') and k.endswith('_cpu')]
 lines.append(f"| {r['mode']}/{r['case']} | {a['Questions']-b['Questions']} | {a['Innodb_row_lock_waits']-b['Innodb_row_lock_waits']}/{a['Innodb_row_lock_time']-b['Innodb_row_lock_time']} | {avg(mysql):.2f} | {avg([s.get('app_cpu',0) for s in samples]):.2f} | {avg(system):.2f}/{max(system):.2f} | {r['redis_cpu_seconds']/r['monitor_seconds']/env['logical_cpus']*100:.2f} | {max(s['queue_ready']+s['queue_unacked'] for s in samples)} | {r['verified_completion_qps_lower_bound']:.2f} |")
lines+=['','进程 CPU 除以逻辑核数，表示整机归一化占用。MySQL 为全局状态，包含采样查询与同机其他活动。采样窗口包含 JMeter 启停及耗尽核验。RabbitMQ 进程 CPU 未单独采样；队列管理统计可能延迟约五秒，峰值为观察下界，0 不等于从未积压。完成 QPS 下界包含退出/轮询延迟，不是精确消费者吞吐。','','## 与上一阶段配置比较','']
a=next(r for r in rows if r['mode']=='stage4' and r['case']=='normal');b=next(r for r in rows if r['mode']=='stage5' and r['case']=='normal')
lines += [f"正常组两种配置均受理 500 次且没有限流拒绝。平均响应 {a['avg_ms']:.2f}ms → {b['avg_ms']:.2f}ms，差值 {b['avg_ms']-a['avg_ms']:+.2f}ms；HTTP QPS {a['http_qps']:.2f} → {b['http_qps']:.2f}。",'', '工程化增加日志与异常响应处理成本，本轮差异很小，不能据一轮样本量化长期开销，也不能声称工程化提升性能。过载组的受理数量还受闭环发送节奏、窗口长度及桶初始突发影响；拒绝更快会缩短总发送时间，未必有更多请求获得令牌。','','## Docker 与功能验证','', '53 项全量测试通过。独立 Docker 环境验证健康 UP、非 root UID 10001、统一 400 错误携带 traceId、下单与同键重试只创建订单 1、库存 100 → 99。保留命名卷删除并重建整套容器后，原请求仍返回同一订单，库存保持 99。详见 stage5-docker-check.json。容器部署验证不作为本表的性能数据。','','## 问题与测量限制','', '本轮未做长时间持续过载、故障注入集群恢复、跨机器压测或逐单 P99/SLA 分析；没有对业务流量做公平性保证。工程化前后使用相同新 JAR，仅比较配置差异，公共新增业务日志并未从基线中移除。历史阶段四原始报告仍单独保留，不覆盖或混用。', '', '业务限制仍包括 userId 未认证、无自动对账/幂等归档、Redis/MQ 跨系统事务窗口、单节点高可用不足。访问日志和文件滚动帮助定位问题，不能保证消除上述风险。']
Path('docs/stage5-report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Generated docs/stage5-report.md')
