"""Generate a report from retained evidence. No network or database writes.
python perf/report_stage2.py PRIMARY_DIR --rerun RERUN_DIR
"""
import argparse,json,hashlib
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('primary',type=Path)
parser.add_argument('--rerun',type=Path)
args=parser.parse_args()
primary=json.loads((args.primary/'metrics.json').read_text(encoding='utf-8'))
env=json.loads((args.primary/'environment.json').read_text(encoding='utf-8'))
selected={(r['mode'],r['name']):r for r in primary}
all_rows=list(primary)
if args.rerun:
    rerun=json.loads((args.rerun/'metrics.json').read_text(encoding='utf-8'))
    replacement=next(r for r in rerun if r['mode']=='redis' and r['name']=='users-1')
    selected[('redis','users-1')]=replacement
    all_rows+=rerun

def cpu(row,prefix):
    values=[]
    for sample in row['samples']:
        if prefix=='mysql':
            matches=[v for k,v in sample.items() if k.startswith('mysql-') and k.endswith('_cpu')]
            if matches:values.append(sum(matches))
        elif prefix+'_cpu' in sample:values.append(sample[prefix+'_cpu'])
    return f'{sum(values)/len(values):.2f}/{max(values):.2f}' if values else '未测'

lines=['# 阶段二压测报告','','阶段：Redis + Lua 预扣库存、同步 MySQL 下单；同时重测 MySQL 对照组。','',f"测试环境：{env['platform']}；32 逻辑核，约 {env['memory_bytes']/1024**3:.1f} GiB 内存；Java 21、Boot 3.3.5、MySQL {env['mysql_version']}、WSL 2 Ubuntu Redis {env['redis_version']}、JMeter 5.6.3。",'', '同机顺序施压，连接池 10，TLS REQUIRED，ramp-up 1 秒。每组预热 200 次；正确性场景 100 并发、1000 请求、100 库存；吞吐场景分别 1/10/100 并发、各 10000 请求与库存。模式顺序 MySQL → Redis；每档单轮。', '', '原始目录：`'+args.primary.as_posix()+'`。']
if args.rerun:
    lines+=['','复测目录：`'+args.rerun.as_posix()+'`。首轮 Redis 单并发与商品 1 初始化重叠，被排除出主要比较；其他场景结束后重新预热并单独复测。旧数据保留在原目录和 experiment-notes.txt，未覆盖或删除。复测还有一组正确性测试，主要表格仅替换 users-1，其他复测结果保留在原始数据。']
lines+=['','## 请求结果','','|模式|场景|请求数|时长 s|HTTP QPS|下单 QPS|平均 ms|最大 ms|成功 201|售罄 409|其他失败|','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
rows=[]
for label in ['correctness','users-1','users-10','users-100']:
    for mode in ['mysql','redis']:
        r=selected[(mode,label)];rows.append(r);codes=r['codes'];other=r['requests']-codes.get('201',0)-codes.get('409',0)
        lines.append(f"|{mode}|{label}|{r['requests']}|{r['seconds']:.2f}|{r['qps']:.2f}|{r['order_qps']:.2f}|{r['avg_ms']:.2f}|{r['max_ms']}|{codes.get('201',0)}|{codes.get('409',0)}|{other}|")
lines+=['','QPS 测量窗口为首个样本开始到最后一个样本结束；与 JMeter 控制台含额外收尾时间的 summary 数值可能略有差异。409 单列为业务拒绝，不能用其吞吐代替成功下单吞吐。','','## 数据库压力与 CPU','','|模式/场景|锁等待次数增量|锁等待累计 ms 增量|Questions 增量|Threads_running 峰值|系统 CPU 均/峰 %|应用 CPU 均/峰 %|MySQL CPU 均/峰 %|Redis CPU 均 %|','|---|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:
    a=r['status_after'];b=r['status_before'];redis_pct=r['redis_cpu_seconds']/r['monitor_seconds']/env['logical_cpus']*100
    lines.append(f"|{r['mode']}/{r['name']}|{a['Innodb_row_lock_waits']-b['Innodb_row_lock_waits']}|{a['Innodb_row_lock_time']-b['Innodb_row_lock_time']}|{a['Questions']-b['Questions']}|{max(s['Threads_running'] for s in r['samples'])}|{cpu(r,'system')}|{cpu(r,'app')}|{cpu(r,'mysql')}|{redis_pct:.3f}|")
lines+=['','CPU 按整机 32 逻辑核归一化，Windows 进程与系统每秒附近采样，实际间隔含采样查询开销。Redis CPU 来自 INFO 中用户态/内核态累计秒差除以监控墙钟窗口与整机逻辑核数；没有采 Redis 瞬时峰值。MySQL 是本机 mysqld 进程合计，计数器是全局值，包含监控查询和可能的其他连接。监控窗口包括 JMeter 启动/收尾，不与 HTTP 样本窗口严格一致；JMeter 进程缺样/退出信息记录于 cpu_errors，不以零替代。','','## 同条件比较']
for n in [1,10,100]:
    m=selected[('mysql',f'users-{n}')];r=selected[('redis',f'users-{n}')]
    lines+=['',f"- {n} 并发：Redis 下单 QPS 为 MySQL 的 {r['order_qps']/m['order_qps']:.2f} 倍（变化 {(r['order_qps']/m['order_qps']-1)*100:+.2f}%）；平均响应 {m['avg_ms']:.2f} → {r['avg_ms']:.2f} ms。"]
lines+=['','Redis 模式不再更新 product 热点库存行，售罄请求在 Redis 返回；数据库商品行锁竞争减少，订单仍同步写入。单并发增加了 Redis 网络往返、脚本和补偿标记维护，可能更慢。不是所有场景都会因为引入 Redis 而更快。100 并发下 Questions 总量约从 8 万降到 5 万、行锁等待从 9999 降到 0，但 MySQL CPU 平均占比从 2.65% 升到 5.45%；因为单位时间处理的订单更多，不能宣称所有数据库压力指标都下降。','','历史阶段一（2026-09-26）下单 QPS 为 256.61 / 375.21 / 313.11，对应 1/10/100 并发。旧实验没有本次 TLS/WSL 环境，保留作历史背景，主要结论使用本次匹配对照组。','','## 一致性、失败与证据']
lines+=['',f"包含预热和复测的全部留存请求：{sum(r['requests'] for r in all_rows)}；201：{sum(r['codes'].get('201',0) for r in all_rows)}；409：{sum(r['codes'].get('409',0) for r in all_rows)}；其他失败：{sum(r['requests']-r['codes'].get('201',0)-r['codes'].get('409',0) for r in all_rows)}。"]
for r in all_rows:
    assert r['remaining_stock']+r['orders']==r['initial_stock'] and r['pending']==0
lines+=['','每组校验初始库存=剩余库存+订单数，成功响应数=订单数，Redis 组末尾 pending=0，库存非负。压力测试全部使用新商品，商品 1 只导入 98 件库存，没有被用于压测。20 项功能/故障/回归测试全部通过；详见 stage2-test-results.txt。','','## 限制与问题','','- 单轮短时实验；Redis 高并发场景持续时间尤其短，不能把观察到的 QPS 当作稳定容量。需要 >=60 秒、多轮、交替顺序和独立施压机进一步验证。','- 固定先 MySQL 后 Redis，存在机器温度、缓存和时间顺序影响。预热仅 200 次，不代表充分达到稳态。','- JMeter、应用、MySQL 和 WSL Redis 同机，数据不是生产部署容量。','- Redis 与 MySQL 没有分布式事务；未知提交、补偿失败、进程崩溃需要停单对账。AOF everysec 仍可能在故障时丢失最近写入，不能据本次无超卖推断所有故障下都安全。','- 本阶段无 MQ 削峰、认证、限流或客户端幂等；下一阶段需用户确认。','','## 复现','','```powershell','python perf/run_stage2.py --requests 10000','python perf/run_stage2.py --requests 10000 --modes redis --cases warmup correctness users-1',f'python perf/report_stage2.py {args.primary.as_posix()}'+(f' --rerun {args.rerun.as_posix()}' if args.rerun else ''),'```','','仅在该环境下用以上命令复现；软件版本、资源和请求数须记录，结果不会逐次完全相同。']
Path('docs/stage2-report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Generated docs/stage2-report.md')
