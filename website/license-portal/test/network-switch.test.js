import {test} from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import {envSetup,req} from './fixtures.js';

const b64=bytes=>Buffer.from(bytes).toString('base64url');
// Synthetic metadata exercises Portal policy; it does not test real carrier routing/WAF.
const networks=[
 {country:'VN',ip:'192.0.2.10',asn:64500,label:'VN Wi-Fi'},
 {country:'VN',ip:'2001:db8:1::10',asn:64501,label:'VN mobile hotspot'},
 {country:'RU',ip:'198.51.100.20',asn:64502,label:'RU Wi-Fi'},
 {country:'RU',ip:'2001:db8:2::20',asn:64503,label:'RU mobile hotspot'}
];
async function send(env,network,path,body=null,token='') {
 const request=new Request('https://vncode.example/api/client/'+path,{
  method:body?'POST':'GET',headers:{'CF-Connecting-IP':network.ip,
   ...(body?{'Content-Type':'application/json'}:{}),...(token?{Authorization:'Bearer '+token}:{})},
  body:body?JSON.stringify(body):undefined
 });
 Object.defineProperty(request,'cf',{value:{country:network.country,asn:network.asn}});
 const response=await worker.fetch(request,env);
 return {status:response.status,data:await response.json()};
}
async function device() {
 const keys=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 return {keys,public_key:b64(await crypto.subtle.exportKey('raw',keys.publicKey))};
}
async function proof(env,token,d,action,network) {
 const challenge=await send(env,network,'license/challenge',{action,public_key:d.public_key},token);
 assert.equal(challenge.status,200,JSON.stringify(challenge.data));
 return {challenge_id:challenge.data.challenge_id,public_key:d.public_key,
  signature:b64(await crypto.subtle.sign('Ed25519',d.keys.privateKey,new TextEncoder().encode(challenge.data.message)))};
}

for(const initial of [networks[0],networks[2]])test(`same license survives Wi-Fi/hotspot, IPv4/IPv6 and VN/RU changes after ${initial.label} activation`,async()=>{
 const authority=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const env=envSetup({LICENSE_KEY_ID:'network-test',LICENSE_SIGNING_KEY:b64(await crypto.subtle.exportKey('pkcs8',authority.privateKey))});
 try {
  const credentials={email:'owner@example.test',password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'};
  await req(env,'/api/register','POST',credentials);
  env.sqlite.prepare("UPDATE users SET role='admin',status='approved' WHERE email=?").run(credentials.email);
  const admin=await req(env,'/api/login','POST',credentials),cookie=admin.cookie.split(';')[0];
  const registered=await send(env,initial,'registration/register',{email:'seller@gmail.com'});
  assert.equal(registered.status,201);const token=registered.data.registration_token;
  const user=env.sqlite.prepare('SELECT id FROM users WHERE email=?').get('seller@gmail.com');
  const visible=await req(env,'/api/admin/desktop/requests?status=pending','GET',null,cookie);
  assert.ok(visible.data.users.some(u=>u.id===user.id));
  const issued=await req(env,`/api/admin/desktop/users/${user.id}/approve-issue`,'POST',
   {days:30,max_devices:1,max_shops:10,offline_hours:72},cookie);
  assert.equal(issued.status,200);const code=issued.data.code,d=await device();
  const activation=await send(env,initial,'license/activate',{...await proof(env,token,d,'activate',initial),code},token);
  assert.equal(activation.status,200,JSON.stringify(activation.data));
  const expiry=activation.data.user.expires_at,id=activation.data.device_id;
  const auth=activation.data.access_token;
  for(const network of networks) {
   const status=await send(env,network,'registration/status',null,token);
   assert.equal(status.data.status,'approved',network.label);
   // Challenge on one route, proof on another: credentials are device-bound, not IP-bound.
   const signed=await proof(env,auth,d,'renew',initial);
   const renewal=await send(env,network,'license/renew',signed,auth);
   assert.equal(renewal.status,200,network.label+JSON.stringify(renewal.data));
   assert.equal(renewal.data.device_id,id);assert.equal(renewal.data.user.expires_at,expiry);
   const payload=Buffer.from(renewal.data.lease.payload,'base64url');
   assert.equal(await crypto.subtle.verify('Ed25519',authority.publicKey,
    Buffer.from(renewal.data.lease.signature,'base64url'),payload),true);
   assert.equal(JSON.parse(payload).maxDevices,1);
   const enrolled=await send(env,network,'registration/license',{code});assert.equal(enrolled.status,201);
   const reg=enrolled.data.registration_token;
   const again=await send(env,network,'license/activate',{...await proof(env,reg,d,'activate',initial),code},reg);
   assert.equal(again.status,200,network.label);assert.equal(again.data.device_id,id);
   assert.equal(again.data.user.expires_at,expiry);
  }
  assert.equal(env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,1);
  assert.equal(env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
  const second=await device(),network=networks[3];
  const capacity=await send(env,network,'license/activate',{...await proof(env,token,second,'activate',initial),code},token);
  assert.equal(capacity.status,409);assert.equal(capacity.data.error,'DEVICE_LIMIT_OR_LICENSE_CHANGED');
  assert.equal((await req(env,`/api/admin/desktop/devices/${id}/revoke`,'POST',{},cookie)).status,200);
  const revoked=await send(env,networks[1],'license/challenge',{action:'renew',public_key:d.public_key},auth);
  assert.equal(revoked.status,403);assert.equal(revoked.data.error,'DEVICE_REVOKED');
 }finally{env.sqlite.close();}
});
