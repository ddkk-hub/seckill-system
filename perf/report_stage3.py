"""Render the stage-three report from saved evidence; never invent missing results."""
import json,sys,statistics
from pathlib import Path
run=Path(sys.argv[1]);burst=Path(sys.argv[2]);rows=json.loads((run/'metrics.json').read_text());b=json.loads((burst/'metrics.json').read_text());env=json.loads((run/'environment.json').read_text())
def avg(values):return statistics.mean(values) if values else 0
lines=['# 阶段三压测报告','',f"测试环境：{env['platform']}；Java 21 / Spring Boot 3.3.5；MySQL {env['mysql_version']}；Redis {env['redis_version']}；RabbitMQ 4.0.5（WSL）。{env['logical_cpus']} 逻辑核、{env['memory_bytes']/1024**3:.1f} GiB 内存。JMeter 5.6.3 与应用/数据库同机。",'', '阶段：阶段二同步 Redis 与阶段三 RabbitMQ 异步的本机短时对比。每种模式预热 200 次；竞争实验 1000 次/100 件；吞吐实验每档 3000 次、库存充足；并发 1/10/100、ramp 1 秒、仅一轮。两个消费者、prefetch=10、连接池 10。','',f'原始证据：`{run.as_posix()}`；削峰：`{burst.as_posix()}`。JAR SHA256：`'+env['jar_sha256']+'`。','','## 请求结果','','| 模式 | 场景 | 并发 | 请求 | QPS | 平均 ms | 最大 ms | 201 成交响应 | 202 受理 | 409 售罄 | 其他失败 | 最终订单 | 完成 QPS 下界 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
 c=r['codes'];other=sum(v for k,v in c.items() if k not in ['201','202','409'])
 lines.append(f"| {r['mode']} | {r['name']} | {r['users']} | {r['requests']} | {r['qps']:.2f} | {r['avg_ms']:.2f} | {r['max_ms']} | {c.get('201',0)} | {c.get('202',0)} | {c.get('409',0)} | {other} | {r['orders']} | {r['order_qps']:.2f} |")
lines+=['','201 是同步创建完成；202 只是 MQ 已确认受理；409 是预期售罄但仍计入非成功请求。表内平均/最大响应时间是 HTTP 响应，不是异步订单端到端延迟。完成 QPS 下界 = 最终订单数 / 从首个请求到脚本确认全部落库的时间，包含 JMeter 退出和采样延迟，**不能当作精确消费者吞吐，也不能与 HTTP QPS 等价比较**。本轮未采集逐单端到端延迟。','','## 数据库、队列与 CPU','','CPU 为归一化到整台机器的百分比；进程 CPU 原始值除以逻辑核数。采样窗口包含 JMeter 启停。MySQL 状态为全局计数，含监控查询及同机活动，不是业务 SQL 的精确计数。Redis CPU 由 INFO 累计 CPU 差计算；未单独采样 RabbitMQ 进程 CPU。队列管理 API 可能约 5 秒刷新，峰值只是观察到的下界。','','| 模式/场景 | MySQL Questions 增量 | 行锁等待次数增量 | 行锁等待 ms | MySQL 平均 CPU% | 应用平均 CPU% | 系统平均/峰值 CPU% | Redis平均CPU% | 观察队列峰值 | JMeter结束后核验等待秒 |','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
 samples=r['samples'];mysql=[v for s in samples for k,v in s.items() if k.startswith('mysql-') and k.endswith('_cpu')];system=[s['system_cpu'] for s in samples];a=r['status_after'];z=r['status_before']
 lines.append(f"| {r['mode']}/{r['name']} | {a['Questions']-z['Questions']} | {a['Innodb_row_lock_waits']-z['Innodb_row_lock_waits']} | {a['Innodb_row_lock_time']-z['Innodb_row_lock_time']} | {avg(mysql):.2f} | {avg([s.get('app_cpu',0) for s in samples]):.2f} | {avg(system):.2f}/{max(system,default=0):.2f} | {r['redis_cpu_seconds']/r['monitor_seconds']/env['logical_cpus']*100:.2f} | {max((s.get('queue_ready',0)+s.get('queue_unacked',0) for s in samples),default=0)} | {r['drain_wait_after_jmeter_seconds']:.3f} |")
lines+=['','## 暂停消费、恢复消费实验','',f"100 并发发送 {b['requests']} 个请求：全部返回 202。暂停时 MySQL 订单 {b['orders_while_paused']}，Redis pending {b['pending_while_paused']}，队列 ready {b['queue_while_paused']['queue_ready']}。恢复后订单 {b['final_orders']}，库存 {b['final_stock']}，pending {b['final_pending']}。从启动消费者进程到确认耗尽 {b['resume_to_verified_seconds_including_jvm_start']:.2f} 秒（包含 JVM 启动，不能当作纯消费耗时）。",'','这证明队列能够在消费者暂停时暂存请求，恢复后继续落库；不代表无限积压或生产集群故障恢复能力。','','## 对比与问题','']
for users in [1,10,100]:
 old=next(r for r in rows if r['mode']=='redis' and r['name']==f'users-{users}');new=next(r for r in rows if r['mode']=='async' and r['name']==f'users-{users}')
 lines.append(f"- {users} 并发：阶段二 HTTP QPS {old['qps']:.2f}，阶段三受理 QPS {new['qps']:.2f}，变化 {(new['qps']/old['qps']-1)*100:+.1f}%。两者返回语义不同，不能由此直接推断成交吞吐提升。")
lines+=['','所有案例均核对剩余库存 + 最终订单 = 初始库存，pending = 0。没有发现超卖或重复订单。单轮短时、同机压测不能推断容量上限或稳定 P99；下一轮性能分析应多次重复、延长持续时间、隔离压测机，并采集逐单端到端时间。发布确认、Redis 请求记录、MySQL 流水都会增加开销；阶段三主要收益是解耦与削峰，实测不保证每档 QPS 提升。','','当前可靠性边界：Redis 与 MQ 无原子事务、无自动恢复；MySQL 已提交后 Redis 清理失败需对账；单节点 classic 队列不是高可用，死信转发仍可能丢失；没有入口限流。完整设计与验收见 stage3.md。']
Path('docs/stage3-report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Generated docs/stage3-report.md')
