#!/usr/bin/env python3
"""Read-only first-page access check. Raw results stay in a NEW owner-only directory."""
import argparse,json,os,tempfile,time,urllib.request,urllib.error,urllib.parse
from datetime import datetime,timezone,timedelta
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--directory',type=Path)
a=p.parse_args();os.umask(0o077)
root=a.directory or Path(tempfile.mkdtemp(prefix='vncode-kiz-mapping-live-'))
if not root.resolve().is_relative_to(Path('/tmp')):p.error('Use a fresh private /tmp directory, never a checkout or installed app profile.')
root.mkdir(mode=0o700,parents=True,exist_ok=True)
if root.stat().st_mode & 0o077 or (root/'access-summary.json').exists():p.error('Use a new private directory (0700), never the installed app profile.')
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
opener=urllib.request.build_opener(NoRedirect())
out={'startedAt':datetime.now(timezone.utc).isoformat(),'mode':'LIVE_API_READ_ONLY_NO_FIXTURES','sellerMutations':0,'paidPurchases':0,'checks':{}}
checks=[('ozon_roles','POST','https://api-seller.ozon.ru/v1/roles',{'Client-Id':os.environ['VNCODE_OZON_TEST_CLIENT_ID'],'Api-Key':os.environ['VNCODE_OZON_TEST_API_KEY']},{},None),('wb_first_page','POST','https://content-api.wildberries.ru/content/v2/get/cards/list',{'Authorization':os.environ['VNCODE_WB_TEST_API_KEY']},{'settings':{'sort':{'ascending':True},'filter':{'withPhoto':-1},'cursor':{'limit':100}}},{'locale':'ru'}),('catalog_first_page','GET','https://xn--80aqu.xn----7sbabas4ajkhfocclk9d3cvfsa.xn--p1ai/v4/product-list',{},None,{'apikey':os.environ['VNCODE_CATALOG_TEST_API_KEY'],'from_date':'2000-01-01 00:00:00','to_date':datetime.now(timezone(timedelta(hours=3))).strftime('%Y-%m-%d %H:%M:%S'),'limit':1000,'offset':0})]
for name,method,url,headers,body,params in checks:
 started=time.monotonic()
 try:
  if params:url+='?'+urllib.parse.urlencode(params)
  if body is not None:headers={**headers,'Content-Type':'application/json'}
  req=urllib.request.Request(url,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
  try:
   with opener.open(req,timeout=30) as r:status=r.status;content=r.read()
  except urllib.error.HTTPError as e:status=e.code;content=e.read()
  (root/(name+'.json')).write_bytes(content)
  data=json.loads(content);item={'httpStatus':status,'elapsedMs':round((time.monotonic()-started)*1000)}
  if name=='ozon_roles':item.update({'code':data.get('code'),'apiKeyDisabled':'deactivat' in str(data.get('message','')).lower()})
  if name=='wb_first_page':item['cards']=len(data.get('cards',[]))
  if name=='catalog_first_page':item.update({'total':data.get('result',{}).get('total'),'goods':len(data.get('result',{}).get('goods',[]))})
  out['checks'][name]=item
 except Exception as e:out['checks'][name]={'failureType':type(e).__name__}
 (root/'access-summary.json').write_text(json.dumps(out,indent=2))
 print(name,json.dumps(out['checks'][name]),flush=True)
print('Private directory:',root)
