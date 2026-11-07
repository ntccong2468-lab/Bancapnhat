import os,json,urllib.request,urllib.error,urllib.parse,time,hashlib
from pathlib import Path
from datetime import datetime,timezone,timedelta
os.umask(0o077)
import argparse
parser=argparse.ArgumentParser(description='Read-only Catalog/WB collection into an existing private /tmp directory')
parser.add_argument('--directory',type=Path,required=True)
root=parser.parse_args().directory.resolve()
if not root.is_relative_to(Path('/tmp')) or not root.is_dir() or root.stat().st_mode & 0o077:
 parser.error('Use the owner-only /tmp directory from read-access.py; never a checkout or installed app profile.')
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
opener=urllib.request.build_opener(NoRedirect())
summary=json.loads((root/'access-summary.json').read_text());journal=[];last_catalog=0
catalog_origin='https://xn--80aqu.xn----7sbabas4ajkhfocclk9d3cvfsa.xn--p1ai'
allowed={('POST','https://content-api.wildberries.ru/content/v2/get/cards/list'),('GET',catalog_origin+'/v4/product-list'),('GET',catalog_origin+'/v3/feed-product')}
def call(method,url,headers,params,body,filename):
 global last_catalog
 if (method,url) not in allowed:raise RuntimeError('Read endpoint is not allowlisted')
 if url.startswith(catalog_origin):
  delay=1.05-(time.monotonic()-last_catalog)
  if delay>0:time.sleep(delay)
  last_catalog=time.monotonic()
 started=time.monotonic()
 request_url=url+('?' + urllib.parse.urlencode(params) if params else '')
 if body is not None:headers={**headers,'Content-Type':'application/json'}
 req=urllib.request.Request(request_url,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 try:
  with opener.open(req,timeout=30) as response:status=response.status;raw=response.read()
 except urllib.error.HTTPError as error:status=error.code;raw=error.read()
 (root/filename).write_bytes(raw)
 journal.append({'method':method,'path':urllib.parse.urlparse(url).path,'status':status,'elapsedMs':round((time.monotonic()-started)*1000),'bodySha256':hashlib.sha256(raw).hexdigest(),'file':filename})
 (root/'private-http-journal.json').write_text(json.dumps(journal,indent=2))
 if status!=200:raise RuntimeError('Read request stopped at HTTP '+str(status))
 return json.loads(raw)
try:
 wb=json.loads((root/'wb_first_page.json').read_text());cards=wb['cards'][:];pages=1
 while len(wb['cards'])==100:
  cursor={k:wb['cursor'][k] for k in ['updatedAt','nmID']};cursor['limit']=100
  wb=call('POST','https://content-api.wildberries.ru/content/v2/get/cards/list',{'Authorization':os.environ['VNCODE_WB_TEST_API_KEY']},{'locale':'ru'},{'settings':{'sort':{'ascending':True},'filter':{'withPhoto':-1},'cursor':cursor}},'wb-page-'+str(pages)+'.json')
  cards.extend(wb['cards']);pages+=1
 (root/'private-wb-cards.json').write_text(json.dumps(cards,ensure_ascii=False))
 print('WB all pages complete:',pages,'cards:',len(cards),'variants:',sum(len(c.get('sizes',[])) for c in cards),flush=True)
 first=json.loads((root/'catalog_first_page.json').read_text());goods=first['result']['goods'][:];total=first['result']['total'];lists=1
 cutoff=datetime.fromisoformat(summary['startedAt']).astimezone(timezone(timedelta(hours=3))).strftime('%Y-%m-%d %H:%M:%S')
 for offset in range(1000,total,1000):
  if offset+1000>10000:raise RuntimeError('Catalog needs version-index continuation')
  page=call('GET',catalog_origin+'/v4/product-list',{},dict(apikey=os.environ['VNCODE_CATALOG_TEST_API_KEY'],from_date='2000-01-01 00:00:00',to_date=cutoff,limit=1000,offset=offset),None,'catalog-list-'+str(offset)+'.json')
  if page['result']['total']!=total:raise RuntimeError('Catalog total changed during read')
  goods.extend(page['result']['goods']);lists+=1
 gtins=list(dict.fromkeys(g['gtin'] for g in goods))
 if len(gtins)!=total:raise RuntimeError('Catalog list incomplete')
 (root/'private-catalog-goods.json').write_text(json.dumps(goods,ensure_ascii=False))
 all_cards=[];batches=[]
 for start in range(0,len(gtins),25):
  selected=gtins[start:start+25];filename='catalog-detail-'+str(start//25)+'.json'
  response=call('GET',catalog_origin+'/v3/feed-product',{},dict(apikey=os.environ['VNCODE_CATALOG_TEST_API_KEY'],gtins=';'.join(selected)),None,filename)
  batches.append({'gtins':selected,'file':filename});all_cards.extend(response.get('result',[]))
  if start%250==0:print('Catalog real detail read:',min(start+25,len(gtins)),'/',len(gtins),flush=True)
 (root/'private-catalog-batches.json').write_text(json.dumps(batches,ensure_ascii=False));(root/'private-catalog-cards.json').write_text(json.dumps(all_cards,ensure_ascii=False))
 summary.update({'fullRead':{'wbPages':pages,'wbCards':len(cards),'wbVariants':sum(len(c.get('sizes',[])) for c in cards),'catalogListRequests':lists,'catalogListedGtins':len(gtins),'catalogDetailBatches':len(batches),'catalogRawRows':len(all_cards),'source':'FRESH_REAL_HTTP_RESPONSES','mockResponses':0,'testLicenseFixtures':0,'remoteMutations':0},'completedAt':datetime.now(timezone.utc).isoformat(),'outcome':'COMPLETE_LIVE_READ'})
except Exception as error:
 summary.update({'outcome':'BLOCKED_LIVE_READ','failureType':type(error).__name__})
 raise
finally:(root/'access-summary.json').write_text(json.dumps(summary,indent=2))
print(json.dumps(summary['fullRead']),flush=True)
