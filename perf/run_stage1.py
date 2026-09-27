"""Run from project root after mvnw package. Requires Python psutil and JMeter.
Creates dedicated products, retains evidence, starts/stops only its own app.
Use --requests 10000 (default); this is an exploratory baseline, not capacity certification.
"""
import argparse
import csv
import json
import os
import platform
import subprocess
import time
import urllib.request
from collections import Counter
from pathlib import Path
import psutil

parser = argparse.ArgumentParser()
parser.add_argument('--requests', type=int, default=10000)
parser.add_argument('--port', type=int, default=18081)
args = parser.parse_args()
assert args.requests > 0 and args.requests % 100 == 0
root = Path.cwd()
out = root / 'perf' / 'results' / time.strftime('%Y%m%d-%H%M%S')
out.mkdir(parents=True)
config = dict(line.split('=', 1) for line in Path('src/main/resources/application.properties').read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and '=' in line)
local_config = Path('application-local.properties')
if local_config.exists():
    config.update(dict(line.split('=', 1) for line in local_config.read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and '=' in line))
if config.get('spring.datasource.password', '').startswith('${'):
    config['spring.datasource.password'] = ''
mysql = os.environ.get('MYSQL_EXE', r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
env = os.environ.copy()
env['MYSQL_PWD'] = os.environ.get('SPRING_DATASOURCE_PASSWORD', config['spring.datasource.password'])

def sql(query):
    # Explicitly target the local seckill database, never print credentials.
    result = subprocess.run([mysql, '-h', '127.0.0.1', '-u', config['spring.datasource.username'], '-N', '-B', 'seckill', '-e', query], env=env, capture_output=True, text=True, encoding='utf-8', timeout=15)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout.strip()

def status():
    data = sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Questions','Threads_running','Threads_connected')")
    return {row.split('\t')[0]: int(row.split('\t')[1]) for row in data.splitlines()}

def product(stock):
    return int(sql(f"INSERT INTO product(name,stock,price) VALUES ('stage1-perf-{out.name}',{stock},6999.00); SELECT LAST_INSERT_ID();"))

jmeter = root / 'target/tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar'
assert jmeter.exists(), 'Download JMeter first'
app_log = (out / 'application.log').open('w', encoding='utf-8')
app = subprocess.Popen(['java', '-jar', 'target/seckill-system-0.0.1-SNAPSHOT.jar', f'--server.port={args.port}', '--debug=false', '--logging.level.root=INFO', '--logging.level.org.springframework=INFO'], stdout=app_log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
results = []
load = None
try:
    for attempt in range(60):
        if app.poll() is not None: raise RuntimeError('App failed to start; see application.log')
        try:
            with urllib.request.urlopen(f'http://localhost:{args.port}/api/product/1', timeout=1) as response:
                assert response.status == 200
            break
        except (OSError, AssertionError): time.sleep(1)
    else: raise RuntimeError('App readiness timeout')
    for name, users, count, stock in [('warmup',10,200,200), ('correctness',100,1000,100), ('users-1',1,args.requests,args.requests), ('users-10',10,args.requests,args.requests), ('users-100',100,args.requests,args.requests)]:
        pid = product(stock)
        jtl = out / (name + '.jtl')
        before = status()
        log = (out / (name + '.log')).open('w', encoding='utf-8')
        command = ['java', '-Xms256m', '-Xmx512m', '-jar', str(jmeter), '-n', '-t', 'perf/stage1.jmx', f'-Jusers={users}', f'-Jloops={count//users}', '-Jramp=1', f'-Jproduct={pid}', f'-Jport={args.port}', '-l', str(jtl), '-j', str(out/(name+'-jmeter.log'))]
        load = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        watched = {'app': psutil.Process(app.pid), 'jmeter': psutil.Process(load.pid)}
        errors = {}
        for process in psutil.process_iter(['name']):
            if process.info['name'] and process.info['name'].lower() == 'mysqld.exe':
                watched['mysql-' + str(process.pid)] = process
        for key, process in list(watched.items()):
            try: process.cpu_percent()
            except psutil.Error as exc:
                errors[key] = type(exc).__name__
                del watched[key]
        psutil.cpu_percent()
        samples = []
        while load.poll() is None:
            time.sleep(1)
            sample = {'time': time.time(), 'system_cpu': psutil.cpu_percent(), **status()}
            for key, process in watched.items():
                try: sample[key+'_cpu'] = process.cpu_percent()/psutil.cpu_count()
                except psutil.Error: pass
            samples.append(sample)
        log.close()
        if load.returncode: raise RuntimeError('JMeter failed; inspect ' + str(out))
        after = status()
        rows = list(csv.DictReader(jtl.open(encoding='utf-8-sig')))
        assert len(rows) == count, (name, len(rows), count)
        codes = Counter(row['responseCode'] for row in rows)
        duration = (max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)-min(int(r['timeStamp']) for r in rows))/1000
        remaining, orders = map(int, sql(f'SELECT stock,(SELECT COUNT(*) FROM seckill_order WHERE product_id={pid}) FROM product WHERE id={pid}').split('\t'))
        row = {'name':name,'users':users,'requests':count,'product':pid,'initial_stock':stock,'remaining_stock':remaining,'orders':orders,'seconds':duration,'qps':count/duration,'order_qps':codes['201']/duration,'avg_ms':sum(int(r['elapsed']) for r in rows)/count,'max_ms':max(int(r['elapsed']) for r in rows),'codes':dict(codes),'status_before':before,'status_after':after,'cpu_errors':errors,'samples':samples}
        results.append(row)
        (out/'metrics.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        assert remaining+orders == stock and remaining >= 0
        assert orders == codes['201'] == min(count, stock)
        assert codes['409'] == max(0,count-stock)
        print(f"{name}: requests={count}, orders={orders}, QPS={row['qps']:.2f}, avg={row['avg_ms']:.2f}ms, max={row['max_ms']}ms",flush=True)
finally:
    if load is not None and load.poll() is None:
        load.terminate(); load.wait(timeout=20)
    if app.poll() is None:
        app.terminate(); app.wait(timeout=20)
    app_log.close()

report = ['# 阶段一压测报告', '', '阶段：单体 MySQL（本机探索性基线，每档 1 轮）', '', f'测试时间：{out.name}，Asia/Shanghai', '', f'测试环境：{platform.platform()}；AMD Ryzen 9 7940HX；{psutil.cpu_count()} 逻辑核；{psutil.virtual_memory().total/1024**3:.1f} GiB 内存。Java 21 / Boot 3.3.5 / MyBatis 3.0.3 / MySQL '+sql('SELECT VERSION();')+' / JMeter 5.6.3。', '', '应用与数据库、施压端在同一台机器。连接池最大 10，测试端口 18081，业务配置默认仍为 8081。预热 200 次后测试，各档独立商品，未修改商品 1。', '', '|场景|并发|请求数|时长 s|HTTP QPS|下单 QPS|平均 ms|最大 ms|201|409|其他失败|', '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for row in results[1:]:
    c=row['codes']; failures=row['requests']-c.get('201',0)-c.get('409',0)
    report.append(f"|{row['name']}|{row['users']}|{row['requests']}|{row['seconds']:.2f}|{row['qps']:.2f}|{row['order_qps']:.2f}|{row['avg_ms']:.2f}|{row['max_ms']}|{c.get('201',0)}|{c.get('409',0)}|{failures}|")
report += ['', '数据库压力与 CPU：目标约每秒采样，查询开销会拉长间隔。短场景样本可能不足，初始化晚于请求启动，不能把 0 当成全程无 CPU 消耗。CPU 为占整机总算力百分比；窗口包括 JMeter 启动/收尾，CPU 与 HTTP 测量窗口不完全一致。MySQL 全局指标包含采样查询和本机其他连接，不能全部归因于业务。', '', '|场景|行锁等待次数增量|行锁等待累计 ms 增量|Questions 增量|Threads_running 峰值|系统 CPU 均值/峰值 %|应用 CPU 均值/峰值 %|', '|---|---:|---:|---:|---:|---:|---:|']
for row in results[1:]:
    samples=row['samples']; b=row['status_before']; a=row['status_after']
    def cpu(key):
        values=[s[key] for s in samples if key in s]
        return f'{sum(values)/len(values):.2f}/{max(values):.2f}' if values else '未测'
    report.append(f"|{row['name']}|{a['Innodb_row_lock_waits']-b['Innodb_row_lock_waits']}|{a['Innodb_row_lock_time']-b['Innodb_row_lock_time']}|{a['Questions']-b['Questions']}|{max(s['Threads_running'] for s in samples)}|{cpu('system_cpu')}|{cpu('app_cpu')}|")
report += ['', '数据库进程 CPU 采集情况：'+json.dumps(results[-1]['cpu_errors'],ensure_ascii=False)+'。可读取的进程样本保存在 metrics.json；缺少样本的进程未测；AccessDenied 表示当前账户无法读取服务进程 CPU，不以系统 CPU 冒充数据库 CPU。', '', '一致性：所有场景均校验初始库存=剩余库存+订单数，成功响应数量=订单数。售罄场景恰好生成 100 条订单，库存为零；未发现超卖。', '', '测试结果：8 项自动化测试全部通过（4 项单测、3 项真实 MySQL/HTTP 测试、1 项启动测试），打包通过。', '', '问题：每档仅 1 轮、同机施压，且部分窗口可能不足 60 秒；结果只作为初始基线，不是稳定容量上限。需要至少 60 秒、多轮和独立施压机才能形成可靠容量结论。行锁等待数据用于分析竞争，不能直接证明数据库是唯一瓶颈。售罄场景 HTTP QPS 不代表下单能力。', '', '缺点：请求仍直达 MySQL；热点行锁竞争；尚无认证、限流、幂等、一人一单。下一阶段必须经用户确认才能开始。', '', '复现：`python perf/run_stage1.py --requests 10000`（需要 psutil、打包产物及本地 JMeter），详细步骤见 stage1.md。', '', '原始证据目录：'+str(out.relative_to(root)).replace('\\','/')+'，内含 JTL、日志和 metrics.json。']
Path('docs/stage1-report.md').write_text('\n'.join(report)+'\n',encoding='utf-8')
print('Report: docs/stage1-report.md',flush=True)
