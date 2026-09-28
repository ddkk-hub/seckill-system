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
root=Path.cwd();out=root/'perf/results'/('stage3-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True)
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
results=[]
(out/'runner-source.py').write_bytes(Path(__file__).read_bytes())
for mode in args.modes:
    app=None;load=None
    app_log=(out/(mode+'-application.log')).open('w',encoding='utf-8')
    try:
        cmd=['java','-jar',str(jar),f'--server.port={args.port}','--debug=false','--logging.level.root=INFO','--logging.level.org.springframework=INFO',f'--seckill.mode={mode}']
        cmd+=['--spring.profiles.active='+('stage3' if mode=='async' else 'stage2')]
        if mode=='async':cmd+=['--seckill.mq.queue=seckill.benchmark.v3']
        app=launch(cmd,app_log)
        for attempt in range(60):
            if app.poll() is not None:raise RuntimeError('Application exited: '+mode)
            try:
                with urllib.request.urlopen(f'http://localhost:{args.port}/api/test',timeout=1) as r:assert r.status==200
                break
            except OSError:time.sleep(1)
        else:raise RuntimeError('Readiness timeout')
        for label,users,count,initial in [('warmup',10,200,200),('correctness',100,1000,100),('users-1',1,args.requests,args.requests),('users-10',10,args.requests,args.requests),('users-100',100,args.requests,args.requests)]:
            if label not in args.cases:continue
            name=mode+'-'+label
            product=int(sql(f"INSERT INTO product(name,stock,price) VALUES ('{out.name}-{name}',{initial},6999.00); SELECT LAST_INSERT_ID();"))
            if mode in ['redis','async']:
                with (out/(name+'-initialize.log')).open('w',encoding='utf-8') as init_log:
                    init=launch(['java','-jar',str(jar),'--spring.profiles.active=stage2','--spring.main.web-application-type=none',f'--seckill.initialize-product={product}','--debug=false'],init_log)
                    try:code=init.wait(timeout=60)
                    except subprocess.TimeoutExpired:init.terminate();init.wait(timeout=10);raise
                    if code:raise RuntimeError('Inventory initialization failed: '+name)
            # Initialize long-lived process samplers before starting JMeter.
            watched={'app':psutil.Process(app.pid)};errors={}
            for process in psutil.process_iter(['name']):
                if (process.info['name'] or '').lower()=='mysqld.exe':watched['mysql-'+str(process.pid)]=process
            for key,process in list(watched.items()):
                try:process.cpu_percent()
                except psutil.Error as e:errors[key]=type(e).__name__;del watched[key]
            psutil.cpu_percent()
            before=status();rb=redis_info();start=time.monotonic()
            jtl=out/(name+'.jtl')
            with (out/(name+'.log')).open('w',encoding='utf-8') as load_log:
                command=['java','-Xms256m','-Xmx512m','-jar',str(jmeter),'-n','-t','perf/stage1.jmx',f'-Jusers={users}',f'-Jloops={count//users}','-Jramp=1',f'-Jproduct={product}',f'-Jport={args.port}','-l',str(jtl),'-j',str(out/(name+'-jmeter.log'))]
                load=launch(command,load_log)
                jp=psutil.Process(load.pid)
                try:jp.cpu_percent();watched['jmeter']=jp
                except psutil.Error as e:errors['jmeter']=type(e).__name__
                samples=[]
                while load.poll() is None:
                    time.sleep(1)
                    sample={'time':time.time(),'system_cpu':psutil.cpu_percent(),**status(),**queue_depth()}
                    for key,process in watched.items():
                        try:sample[key+'_cpu']=process.cpu_percent()/psutil.cpu_count()
                        except psutil.Error as e:errors[key]=type(e).__name__
                    samples.append(sample)
                if load.returncode:raise RuntimeError('JMeter failed: '+name)
            # Wait for committed orders and Redis completion, not just HTTP acceptance.
            drain_start=time.monotonic()
            if mode=='async':
                deadline=time.monotonic()+120
                while True:
                    committed=int(sql(f'SELECT COUNT(*) FROM seckill_order WHERE product_id={product}'))
                    with Redis() as redis:pending_now=int(redis.call('HLEN',f'product_pending_{product}'))
                    if committed==min(count,initial) and pending_now==0:break
                    if time.monotonic()>deadline:raise RuntimeError('Consumer drain timed out')
                    time.sleep(.1)
            verified_at=time.time()
            drain_wait=time.monotonic()-drain_start
            duration_monitor=time.monotonic()-start;after=status();ra=redis_info()
            rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')));codes=Counter(r['responseCode'] for r in rows)
            seconds=(max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)-min(int(r['timeStamp']) for r in rows))/1000
            db_stock,orders=map(int,sql(f'SELECT stock,(SELECT COUNT(*) FROM seckill_order WHERE product_id={product}) FROM product WHERE id={product}').split('\t'))
            pending=0;remaining=db_stock
            if mode in ['redis','async']:
                with Redis() as redis:
                    remaining=int(redis.call('GET',f'product_stock_{product}'));pending=redis.call('HLEN',f'product_pending_{product}')
            result={'mode':mode,'name':label,'users':users,'requests':len(rows),'product':product,'initial_stock':initial,'remaining_stock':remaining,'mysql_stock':db_stock,'orders':orders,'pending':pending,'seconds':seconds,'qps':len(rows)/seconds,'order_qps':orders/(verified_at-min(int(r['timeStamp']) for r in rows)/1000),'accepted_qps':codes['202']/seconds,'drain_wait_after_jmeter_seconds':drain_wait,'verified_completion_window_seconds':verified_at-min(int(r['timeStamp']) for r in rows)/1000,'avg_ms':sum(int(r['elapsed']) for r in rows)/len(rows),'max_ms':max(int(r['elapsed']) for r in rows),'codes':dict(codes),'status_before':before,'status_after':after,'samples':samples,'cpu_errors':errors,'monitor_seconds':duration_monitor,'redis_cpu_seconds':redis_cpu(ra)-redis_cpu(rb),'redis_commands_delta':int(ra['total_commands_processed'])-int(rb['total_commands_processed'])}
            results.append(result);(out/'metrics.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
            assert len(rows)==count and remaining+orders==initial and remaining>=0 and pending==0
            assert orders==codes['202' if mode=='async' else '201']==min(count,initial) and codes['409']==max(0,count-initial)
            print(f"{name}: QPS={result['qps']:.2f}, order QPS={result['order_qps']:.2f}, avg={result['avg_ms']:.2f}ms, max={result['max_ms']}ms, codes={dict(codes)}",flush=True)
    finally:
        for process in [load,app]:
            if process is not None and process.poll() is None:process.terminate();process.wait(timeout=20)
        app_log.close()
print('Evidence directory: '+str(out),flush=True)
