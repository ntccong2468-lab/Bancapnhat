// Loopback-only cross-runtime test bridge. Never imported by the deployed Worker.
import http from 'node:http';
import {envSetup,req} from './fixtures.js';
import worker from '../src/index.js';
const pair=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
const env=envSetup({LICENSE_KEY_ID:'fixture-issuer',LICENSE_SIGNING_KEY:Buffer.from(await crypto.subtle.exportKey('pkcs8',pair.privateKey)).toString('base64url')});
const owner={email:'bridge-owner@gmail.com',password:'Fixture_Password_Only_12345',confirm:'Fixture_Password_Only_12345'};
await req(env,'/api/register','POST',owner);env.sqlite.prepare("UPDATE users SET role='admin',status='approved'").run();
const login=await req(env,'/api/login','POST',owner),cookie=login.cookie.split(';')[0];
let corruptNextActivation=false,dropNextActivation=false;
const server=http.createServer(async(incoming,outgoing)=>{
 try {
  let body='';for await(const chunk of incoming){body+=chunk;if(body.length>65536)throw new Error();}
  let response;
  if(incoming.url==='/__fixture/approve') {
   const user=env.sqlite.prepare("SELECT id FROM users WHERE email='bridge-client@gmail.com'").get();
   response=new Response(JSON.stringify((await req(env,`/api/admin/desktop/users/${user.id}/approve-issue`,'POST',{days:30,max_devices:2,max_shops:10,offline_hours:72},cookie)).data),{headers:{'content-type':'application/json'}});
  }else if(incoming.url==='/__fixture/metrics') {
   const user=env.sqlite.prepare("SELECT license_expires_at FROM users WHERE email='bridge-client@gmail.com'").get();
   response=new Response(JSON.stringify({state:env.sqlite.prepare('SELECT state FROM license_codes').get()?.state,devices:env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,redemptions:env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,expires:user?.license_expires_at,usedChallenges:env.sqlite.prepare('SELECT COUNT(*) n FROM desktop_challenges WHERE used=1').get().n}),{headers:{'content-type':'application/json'}});
  }else if(incoming.url==='/__fixture/drop-next') {
   dropNextActivation=true;response=new Response('{}',{headers:{'content-type':'application/json'}});
  }else if(incoming.url==='/__fixture/corrupt-next') {
   corruptNextActivation=true;response=new Response('{}',{headers:{'content-type':'application/json'}});
  }else {
   const request=new Request('https://vncode.example'+incoming.url,{method:incoming.method,headers:incoming.headers,body:['GET','HEAD'].includes(incoming.method)?undefined:body});
   response=await worker.fetch(request,env);
   if(dropNextActivation&&incoming.url==='/api/client/license/activate'&&response.status===200){dropNextActivation=false;outgoing.destroy();return;}
   if(corruptNextActivation&&incoming.url==='/api/client/license/activate'&&response.status===200){corruptNextActivation=false;response=new Response('{"invalid_fixture":true}',{headers:{'content-type':'application/json'}});}
  }
  outgoing.writeHead(response.status,Object.fromEntries(response.headers));outgoing.end(await response.text());
 }catch{outgoing.writeHead(500,{'content-type':'application/json'});outgoing.end('{"error":"FIXTURE_FAILED"}');}
});
server.listen(0,'127.0.0.1',async()=>console.log(JSON.stringify({port:server.address().port,keyId:env.LICENSE_KEY_ID,publicKey:Buffer.from(await crypto.subtle.exportKey('raw',pair.publicKey)).toString('base64url')})));
