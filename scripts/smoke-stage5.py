"""Purchase exactly one operation on the isolated Docker demo product, then verify replay.
Run --capture before a container recreation and --replay after it. Never targets host port 8081.
"""
import argparse,json,time,uuid,urllib.request,urllib.error
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--mode',choices=['capture','replay'],required=True);args=p.parse_args()
base='http://localhost:18081/api';evidence=Path('target/stage5-docker-smoke.json')
def call(path,method='GET',key=None):
 headers={'Idempotency-Key':key} if key else {}
 request=urllib.request.Request(base+path,method=method,headers=headers)
 try:
  with urllib.request.urlopen(request,timeout=10) as r:return r.status,json.load(r),dict(r.headers)
 except urllib.error.HTTPError as e:return e.code,json.load(e),dict(e.headers)
assert call('/actuator/health')[1]['status']=='UP'
if args.mode=='capture':
 if evidence.exists():raise RuntimeError('Existing smoke evidence; use --mode replay to avoid a new purchase')
 before=call('/product/1')[1]['stock'];key=str(uuid.uuid4())
 code,receipt,headers=call('/seckill/1?userId=5001','POST',key);assert code==202,(code,receipt)
 assert headers.get('X-Trace-Id') or headers.get('X-trace-id')
 for _ in range(50):
  result=call('/seckill/result/'+receipt['requestId'])[1]
  if result.get('status')=='SUCCESS':break
  time.sleep(.3)
 else:raise RuntimeError('Order did not complete')
 time.sleep(1)
 code,replay,_=call('/seckill/1?userId=5001','POST',key);assert code==200 and replay==result
 stock=call('/product/1')[1]['stock'];assert stock==before-1
 error_code,error,error_headers=call('/seckill/1?userId=5001','POST')
 assert error_code==400 and error['code']=='INVALID_REQUEST' and error['traceId']
 env_code,_,_=call('/actuator/env');assert env_code==404
 saved={'base':base,'idempotency_key':key,'requestId':receipt['requestId'],'orderId':result['orderId'],'stock_before':before,'stock_after':stock,'initial_passed':True,'error_status':error_code,'error_code':error['code'],'health':'UP'}
 evidence.write_text(json.dumps(saved,indent=2),encoding='utf-8')
else:
 saved=json.loads(evidence.read_text())
 code,result,_=call('/seckill/1?userId=5001','POST',saved['idempotency_key'])
 assert code==200 and result['status']=='SUCCESS' and result['orderId']==saved['orderId'] and result['requestId']==saved['requestId']
 assert call('/product/1')[1]['stock']==saved['stock_after']
 saved['recreation_replay_passed']=True;evidence.write_text(json.dumps(saved,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in saved.items() if k!='idempotency_key'},indent=2))
