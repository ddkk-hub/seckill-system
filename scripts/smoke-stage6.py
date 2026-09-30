"""Exercise the isolated stage 6 demo; secrets stay in ignored target/."""
import argparse,json,secrets,time,uuid,urllib.request,urllib.error
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--mode',choices=['capture','replay'],required=True);args=parser.parse_args()
base='http://localhost:18082/api';evidence=Path('target/stage6-docker-smoke-private.json')
def call(path,method='GET',token=None,body=None,key=None):
    headers={}
    if token:headers['Authorization']='Bearer '+token
    if key:headers['Idempotency-Key']=key
    data=None
    if body is not None:data=json.dumps(body).encode();headers['Content-Type']='application/json'
    if method=='POST' and data is None:data=b''
    request=urllib.request.Request(base+path,data=data,headers=headers,method=method)
    try:r=urllib.request.urlopen(request,timeout=15)
    except urllib.error.HTTPError as e:r=e
    with r:
        raw=r.read();return r.status,json.loads(raw) if raw else {},dict(r.headers)
assert call('/actuator/health')[1]['status']=='UP'
if args.mode=='capture':
    if evidence.exists():raise RuntimeError('Use replay to preserve existing purchase evidence')
    username='demo_'+uuid.uuid4().hex[:14];password=secrets.token_urlsafe(24)
    a={'username':username,'password':password};b={'username':username+'b','password':secrets.token_urlsafe(24)}
    assert call('/auth/register','POST',body=a)[0]==201
    assert call('/auth/register','POST',body=b)[0]==201
    code,login,_=call('/auth/login','POST',body=a);assert code==200;token=login['accessToken']
    code,other,_=call('/auth/login','POST',body=b);assert code==200;tokenB=other['accessToken']
    before=call('/product/1')[1]['stock'];key=str(uuid.uuid4())
    assert call('/seckill/1','POST',key=key)[0]==401
    assert call('/seckill/1?userId='+str(other['userId']),'POST',token,key=key)[0]==400
    code,receipt,_=call('/seckill/1','POST',token,key=key);assert code==202
    for _ in range(100):
        code,result,_=call('/seckill/result/'+receipt['requestId'],token=token)
        if result.get('status')=='SUCCESS':break
        time.sleep(.2)
    else:raise RuntimeError('Order timeout')
    assert call('/seckill/result/'+receipt['requestId'],token=tokenB)[0]==404
    assert call('/order/'+str(result['orderId']),token=tokenB)[0]==404
    assert call('/order/'+str(result['orderId']),token=token)[1]['userId']==login['userId']
    code,replay,_=call('/seckill/1','POST',token,key=key);assert code==200 and replay==result
    assert call('/auth/logout','POST',tokenB)[0]==204
    assert call('/auth/me',token=tokenB)[0]==401
    after=call('/product/1')[1]['stock'];assert after==before-1
    saved={'username':username,'password':password,'idempotency_key':key,'requestId':result['requestId'],'orderId':result['orderId'],'userId':login['userId'],'stock_before':before,'stock_after':after,'health':'UP','anonymous_status':401,'forged_user_status':400,'foreign_request_status':404,'foreign_order_status':404,'logout_status':401,'initial_passed':True}
else:
    saved=json.loads(evidence.read_text());time.sleep(1)
    code,login,_=call('/auth/login','POST',body={'username':saved['username'],'password':saved['password']});assert code==200
    code,result,_=call('/seckill/1','POST',login['accessToken'],key=saved['idempotency_key'])
    assert code==200 and result['status']=='SUCCESS' and result['orderId']==saved['orderId'] and result['requestId']==saved['requestId']
    assert call('/product/1')[1]['stock']==saved['stock_after'];saved['restart_replay_passed']=True
    assert call('/auth/logout','POST',login['accessToken'])[0]==204
# Persist credentials only locally; never add this private file to Git.
evidence.write_text(json.dumps(saved,indent=2),encoding='utf-8')
public={k:v for k,v in saved.items() if k not in ['password','idempotency_key']}
Path('target/stage6-docker-smoke-public.json').write_text(json.dumps(public,indent=2)+'\n',encoding='utf-8')
print(json.dumps(public,indent=2))
