"""Matched exploratory benchmarks. Run from project root: python perf/run_stage2.py
Runs MySQL and Redis modes sequentially, retains products and all experiment evidence.
Requires psutil, built application JAR and JMeter 5.6.3 under target/tools.
"""
import base64
import argparse,csv,hashlib,json,os,platform,socket,subprocess,time,urllib.request
from collections import Counter
from pathlib import Path
import psutil
from redis_client import Redis

parser=argparse.ArgumentParser()
parser.add_argument('--requests',type=int,default=10000)
parser.add_argument('--port',type=int,default=18084)
parser.add_argument('--modes',nargs='+',choices=['redis','async'],default=['redis','async'])
parser.add_argument('--cases',nargs='+',choices=['warmup','correctness','users-1','users-10','users-100'],default=['warmup','correctness','users-1','users-10','users-100'])
args=parser.parse_args()
assert args.requests>0 and args.requests%100==0
root=Path.cwd();out=root/'perf/results'/('stage3-burst-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True)
config={}
for path in ['src/main/resources/application.properties','application-local.properties']:
    if Path(path).exists():config.update(dict(line.split('=',1) for line in Path(path).read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and '=' in line))
password=os.getenv('SPRING_DATASOURCE_PASSWORD',config.get('spring.datasource.password',''))
if password.startswith('${'):password=''
env=os.environ.copy();env['MYSQL_PWD']=password
mysql=os.getenv('MYSQL_EXE',r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
def sql(query):
    r=subprocess.run([mysql,'-h','127.0.0.1','-u',config['spring.datasource.username'],'-N','-B','seckill','-e',query],env=env,capture_output=True,text=True,encoding='utf-8',timeout=15)
    if r.returncode:raise RuntimeError(r.stderr)
    return r.stdout.strip()
def status():
    return {a:int(b) for a,b in (line.split('\t') for line in sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running','Threads_connected')").splitlines())}
def queue_depth():
    if mode!='async':return {}
    token=base64.b64encode((config['spring.rabbitmq.username']+':'+config['spring.rabbitmq.password']).encode()).decode()
    req=urllib.request.Request('http://127.0.0.1:15672/api/queues/seckill/seckill.benchmark.v3',headers={'Authorization':'Basic '+token})
    with urllib.request.urlopen(req,timeout=3) as response:data=json.load(response)
    return {'queue_ready':data.get('messages_ready',0),'queue_unacked':data.get('messages_unacknowledged',0)}

def redis_info():
    with Redis() as redis:return redis.info()
def redis_cpu(info):return float(info['used_cpu_sys'])+float(info['used_cpu_user'])
def launch(command,log):
    return subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
jar=Path('target/seckill-system-0.0.1-SNAPSHOT.jar')
jmeter=Path('target/tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar').resolve()
assert jar.exists() and jmeter.exists()
with socket.socket() as probe:probe.bind(('127.0.0.1',args.port))
metadata={'time':out.name,'platform':platform.platform(),'logical_cpus':psutil.cpu_count(),'memory_bytes':psutil.virtual_memory().total,'mysql_version':sql('SELECT VERSION()'),'redis_version':redis_info()['redis_version'],'requests_per_throughput_case':args.requests,'pool_size':10,'ramp_seconds':1,'mysql_tls':'REQUIRED','jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'modes_order':args.modes,'cases':args.cases,'repetitions':1,'notes':'Same machine; short exploratory samples; completion QPS is a conservative lower bound including JMeter shutdown and verification delay; broker metrics may lag 5s; SQL and CPU sampling windows include process startup/teardown.'}
(out/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')

mode='async'
processes=[];handles=[]
def start_app(port,enabled):
    log=(out/f'application-{port}.log').open('w',encoding='utf-8');handles.append(log)
    p=launch(['java','-jar',str(jar),'--spring.profiles.active=stage3',f'--server.port={port}','--seckill.mq.queue=seckill.benchmark.v3',f'--seckill.consumer-enabled={str(enabled).lower()}','--debug=false'],log);processes.append(p)
    for _ in range(60):
        if p.poll() is not None:raise RuntimeError('Application exited')
        try:
            urllib.request.urlopen(f'http://localhost:{port}/api/test',timeout=1).close();return p
        except OSError:time.sleep(1)
    raise RuntimeError('Startup timeout')
try:
    count=500
    product=int(sql(f"INSERT INTO product(name,stock,price) VALUES ('{out.name}',{count},6999.00); SELECT LAST_INSERT_ID();"))
    with (out/'initialize.log').open('w') as log:
        p=launch(['java','-jar',str(jar),'--spring.profiles.active=stage2','--spring.main.web-application-type=none',f'--seckill.initialize-product={product}','--debug=false'],log)
        processes.append(p);assert p.wait(timeout=60)==0
    start_app(args.port,False)
    jtl=out/'burst.jtl'
    with (out/'jmeter-output.log').open('w') as log:
        p=launch(['java','-Xms256m','-Xmx512m','-jar',str(jmeter),'-n','-t','perf/stage1.jmx','-Jusers=100','-Jloops=5','-Jramp=1',f'-Jproduct={product}',f'-Jport={args.port}','-l',str(jtl),'-j',str(out/'jmeter.log')],log)
        processes.append(p);assert p.wait(timeout=120)==0
    rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')));codes=Counter(r['responseCode'] for r in rows)
    assert codes=={'202':500}
    before_orders=int(sql(f'SELECT COUNT(*) FROM seckill_order WHERE product_id={product}'))
    with Redis() as redis:before_pending=redis.call('HLEN',f'product_pending_{product}')
    time.sleep(6);backlog=queue_depth()
    assert before_orders==0 and before_pending==500 and backlog['queue_ready']==500
    resume=time.monotonic();start_app(args.port+1,True)
    deadline=time.monotonic()+60
    while True:
        orders=int(sql(f'SELECT COUNT(*) FROM seckill_order WHERE product_id={product}'))
        with Redis() as redis:pending=redis.call('HLEN',f'product_pending_{product}');remaining=int(redis.call('GET',f'product_stock_{product}'))
        if orders==count and pending==0:break
        if time.monotonic()>deadline:raise RuntimeError('Drain timeout')
        time.sleep(.1)
    result={'product':product,'requests':count,'users':100,'codes':dict(codes),'orders_while_paused':before_orders,'pending_while_paused':before_pending,'queue_while_paused':backlog,'final_orders':orders,'final_pending':pending,'final_stock':remaining,'resume_to_verified_seconds_including_jvm_start':time.monotonic()-resume}
    (out/'metrics.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result),flush=True)
finally:
    for p in reversed(processes):
        if p.poll() is None:p.terminate();p.wait(timeout=20)
    for h in handles:h.close()
print('Evidence directory: '+str(out))
