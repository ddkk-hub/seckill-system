"""Sequential same-machine JMeter comparison: stage3 vs protected stage4.
Run from the repository root: python perf/run_stage4.py --requests 3000
Creates dedicated products, queue and limiter namespace; preserves every experiment.
"""
import argparse,base64,csv,hashlib,json,os,platform,socket,subprocess,time,urllib.request
from collections import Counter
from pathlib import Path
import psutil
from redis_client import Redis

parser=argparse.ArgumentParser()
parser.add_argument('--requests',type=int,default=3000)
parser.add_argument('--port',type=int,default=18088)
parser.add_argument('--jar',default='target/seckill-system-stage4.jar')
args=parser.parse_args()
assert args.requests>0 and args.requests%100==0
out=Path('perf/results')/('stage4-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True)
config={}
for filename in ['src/main/resources/application.properties','application-local.properties']:
    p=Path(filename)
    if p.exists():config.update(dict(line.split('=',1) for line in p.read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#')))
env=os.environ.copy();env['MYSQL_PWD']=os.getenv('SPRING_DATASOURCE_PASSWORD',config.get('spring.datasource.password',''))
mysql=os.getenv('MYSQL_EXE',r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
def sql(query):
    r=subprocess.run([mysql,'-h','127.0.0.1','-u',config['spring.datasource.username'],'-N','-B','seckill','-e',query],env=env,capture_output=True,text=True,encoding='utf-8',timeout=15)
    if r.returncode:raise RuntimeError(r.stderr)
    return r.stdout.strip()
def status():
    return {a:int(b) for a,b in (line.split('\t') for line in sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running','Threads_connected')").splitlines())}
def redis_info():
    with Redis() as redis:return redis.info()
def launch(command,log):return subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
queue='seckill.benchmark.v4'
def depth():
    token=base64.b64encode((config['spring.rabbitmq.username']+':'+config['spring.rabbitmq.password']).encode()).decode()
    request=urllib.request.Request('http://127.0.0.1:15672/api/queues/seckill/'+queue,headers={'Authorization':'Basic '+token})
    with urllib.request.urlopen(request,timeout=3) as response:data=json.load(response)
    return {'queue_ready':data.get('messages_ready',0),'queue_unacked':data.get('messages_unacknowledged',0)}
jar=Path(args.jar)
jmeter=Path('target/tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar').resolve()
assert jar.exists() and jmeter.exists()
with socket.socket() as probe:probe.bind(('127.0.0.1',args.port))
metadata={'time':out.name,'platform':platform.platform(),'logical_cpus':psutil.cpu_count(),'memory_bytes':psutil.virtual_memory().total,'mysql_version':sql('SELECT VERSION()'),'redis_version':redis_info()['redis_version'],'rabbitmq_version':'4.0.5','jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'requests':args.requests,'repetitions':1,'queue':queue,'consumers':2,'prefetch':10,'pool_size':10,'ramp_seconds':1,'write_global_rate':200,'write_global_capacity':100,'write_ip_rate_override':200,'write_ip_capacity_override':100,'notes':'All clients use one local IP. IP threshold is raised to the global threshold for this comparison; application defaults remain 50/s and burst 20. Queue management metrics can lag 5s. Verified completion QPS includes JMeter shutdown and polling delay, so it is a conservative lower bound.'}
(out/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
(out/'runner-source.py').write_bytes(Path(__file__).read_bytes());(out/'test-plan.jmx').write_bytes(Path('perf/stage4.jmx').read_bytes())
results=[]
for mode in ['unprotected','protected']:
    app=None;load=None;app_log=(out/(mode+'-application.log')).open('w',encoding='utf-8')
    try:
        command=['java','-jar',str(jar),f'--server.port={args.port}','--spring.profiles.active='+('stage4' if mode=='protected' else 'stage3'),f'--seckill.mq.queue={queue}','--debug=false','--logging.level.root=INFO','--logging.level.org.springframework=INFO']
        if mode=='protected':command += [f'--seckill.limits.namespace=seckill:benchmark:{out.name}','--seckill.limits.write-ip-rate=200','--seckill.limits.write-ip-capacity=100']
        app=launch(command,app_log)
        for attempt in range(60):
            if app.poll() is not None:raise RuntimeError('Application exited')
            try:urllib.request.urlopen(f'http://localhost:{args.port}/api/test',timeout=1).close();break
            except OSError:time.sleep(1)
        else:raise RuntimeError('Readiness timeout')
        for label,users,count,delay in [('warmup',10,200,0),('normal',10,500,100),('overload',100,args.requests,0)]:
            name=mode+'-'+label
            product=int(sql(f"INSERT INTO product(name,stock,price) VALUES ('{out.name}-{name}',{count},6999.00);SELECT LAST_INSERT_ID();"))
            with (out/(name+'-initialize.log')).open('w',encoding='utf-8') as log:
                init=launch(['java','-jar',str(jar),'--spring.profiles.active=stage2','--spring.main.web-application-type=none',f'--seckill.initialize-product={product}','--debug=false'],log)
                try:code=init.wait(timeout=60)
                except subprocess.TimeoutExpired:init.terminate();init.wait(timeout=10);raise
                assert code==0
            watched={'app':psutil.Process(app.pid)}
            for process in psutil.process_iter(['name']):
                if (process.info['name'] or '').lower()=='mysqld.exe':watched['mysql-'+str(process.pid)]=process
            errors={}
            for name_cpu,process in list(watched.items()):
                try:process.cpu_percent()
                except psutil.Error as e:errors[name_cpu]=type(e).__name__;del watched[name_cpu]
            psutil.cpu_percent();before=status();rb=redis_info();start=time.monotonic();samples=[]
            def sample(phase):
                point={'time':time.time(),'phase':phase,'system_cpu':psutil.cpu_percent(),**status(),**depth()}
                for name_cpu,process in watched.items():
                    try:point[name_cpu+'_cpu']=process.cpu_percent()/psutil.cpu_count()
                    except psutil.Error as e:errors[name_cpu]=type(e).__name__
                point['orders']=int(sql(f'SELECT COUNT(*) FROM seckill_order WHERE product_id={product}'))
                samples.append(point)
                return point['orders']
            jtl=out/(name+'.jtl')
            with (out/(name+'-load.log')).open('w',encoding='utf-8') as log:
                load=launch(['java','-Xms256m','-Xmx512m','-jar',str(jmeter),'-n','-t','perf/stage4.jmx',f'-Jusers={users}',f'-Jloops={count//users}','-Jramp=1',f'-Jdelay={delay}',f'-Jproduct={product}',f'-Jport={args.port}','-l',str(jtl),'-j',str(out/(name+'-jmeter.log'))],log)
                while load.poll() is None:time.sleep(.5);sample('load')
                if load.returncode:raise RuntimeError('JMeter failed')
            rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')));codes=Counter(row['responseCode'] for row in rows);accepted=codes['202']
            end_load=time.monotonic();deadline=end_load+120
            while True:
                orders=sample('drain')
                with Redis() as redis:pending=int(redis.call('HLEN',f'product_pending_{product}'));remaining=int(redis.call('GET',f'product_stock_{product}'))
                if orders==accepted and pending==0:break
                if time.monotonic()>deadline:raise RuntimeError('Consumer drain timeout')
                time.sleep(.5)
            verified=time.time();monitor_seconds=time.monotonic()-start;drain_wait=time.monotonic()-end_load
            after=status();ra=redis_info();first=min(int(row['timeStamp']) for row in rows)/1000
            seconds=(max(int(row['timeStamp'])+int(row['elapsed']) for row in rows)/1000)-first
            times=[int(row['elapsed']) for row in rows];accepted_times=[int(row['elapsed']) for row in rows if row['responseCode']=='202']
            result={'mode':mode,'case':label,'users':users,'requests':len(rows),'delay_ms':delay,'product':product,'initial_stock':count,'remaining_stock':remaining,'orders':orders,'pending':pending,'codes':dict(codes),'seconds':seconds,'http_qps':len(rows)/seconds,'accepted_qps':accepted/seconds,'avg_ms':sum(times)/len(times),'max_ms':max(times),'accepted_avg_ms':sum(accepted_times)/len(accepted_times) if accepted_times else None,'verified_completion_qps_lower_bound':orders/(verified-first),'drain_wait_after_jmeter_seconds':drain_wait,'samples':samples,'cpu_errors':errors,'status_before':before,'status_after':after,'monitor_seconds':monitor_seconds,'redis_cpu_seconds':sum(float(ra[k])-float(rb[k]) for k in ['used_cpu_sys','used_cpu_user']),'redis_commands_delta':int(ra['total_commands_processed'])-int(rb['total_commands_processed'])}
            results.append(result);(out/'metrics.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
            assert len(rows)==count and remaining+orders==count and pending==0
            assert accepted+codes['429']==count and (mode=='protected' or codes['429']==0)
            if label=='normal':assert codes['429']==0,'Normal traffic should not be throttled'
            if mode=='protected':assert accepted<=100+200*(seconds+.1),'Token bucket admission bound exceeded'
            print(f"{name}: HTTP QPS={result['http_qps']:.2f}, accepted QPS={result['accepted_qps']:.2f}, avg={result['avg_ms']:.2f}ms, codes={dict(codes)}, final orders={orders}, drain={drain_wait:.2f}s",flush=True)
    finally:
        for process in [load,app]:
            if process is not None and process.poll() is None:process.terminate();process.wait(timeout=20)
        app_log.close()
print('Evidence directory: '+str(out),flush=True)
