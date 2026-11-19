import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {DatabaseSync} from 'node:sqlite';
import {envSetup,req} from './fixtures.js';
const encode=b=>Buffer.from(b).toString('base64url');
async function setup(){
 const mail=[],keys=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const env=envSetup({EMAIL:{send:async msg=>mail.push(msg)},LICENSE_KEY_ID:'test-1',LICENSE_SIGNING_KEY:encode(await crypto.subtle.exportKey('pkcs8',keys.privateKey))});
 const owner={email:'owner@example.test',password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'};
 await req(env,'/api/register','POST',owner);env.sqlite.prepare("UPDATE users SET role='admin',status='approved' WHERE email=?").run(owner.email);
 const login=await req(env,'/api/login','POST',owner);
 mail.length=0;return {env,mail,keys,cookie:login.cookie.split(';')[0]};
}
async function registerLegacy(ctx,email='client@gmail.com'){
 const r=await req(ctx.env,'/api/client/registration/start','POST',{email});assert.equal(r.status,201,JSON.stringify(r.data));
 const otp=ctx.mail.at(-1).text.match(/\b\d{6}\b/)[0],auth={authorization:'Bearer '+r.data.registration_token};
 const v=await req(ctx.env,'/api/client/registration/verify','POST',{code:otp},'',auth);assert.equal(v.status,200,JSON.stringify(v.data));
 return {auth,email,id:ctx.env.sqlite.prepare('SELECT id FROM users WHERE email=?').get(email).id,token:r.data.registration_token};
}
async function register(ctx,email='client@gmail.com'){
 const r=await req(ctx.env,'/api/client/registration/register','POST',{email});assert.equal(r.status,201,JSON.stringify(r.data));
 assert.equal(r.data.email,email);assert.equal(r.data.expires_in,30*86400);
 return {auth:{authorization:'Bearer '+r.data.registration_token},email,id:ctx.env.sqlite.prepare('SELECT id FROM users WHERE email=?').get(email).id,token:r.data.registration_token};
}
async function approve(ctx,reg,opts={}){
 const r=await req(ctx.env,`/api/admin/desktop/users/${reg.id}/approve-issue`,'POST',{days:30,max_devices:1,max_shops:2,offline_hours:72,...opts},ctx.cookie);
 assert.equal(r.status,200,JSON.stringify(r.data));return r.data.code;
}
async function device(){const keys=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);return {keys,public_key:encode(await crypto.subtle.exportKey('raw',keys.publicKey))};}
async function proof(ctx,auth,d,action){
 const r=await req(ctx.env,'/api/client/license/challenge','POST',{action,public_key:d.public_key},'',auth);assert.equal(r.status,200,JSON.stringify(r.data));
 return {challenge_id:r.data.challenge_id,public_key:d.public_key,signature:encode(await crypto.subtle.sign('Ed25519',d.keys.privateKey,new TextEncoder().encode(r.data.message)))};
}
async function activate(ctx,reg,d,code){return req(ctx.env,'/api/client/license/activate','POST',{...await proof(ctx,reg.auth,d,'activate'),code,device_name:'Test Windows'},'',reg.auth);}

test('issuer rotation renews the same device and code without adding days or losing accounts',async()=>{
 const ctx=await setup();ctx.env.LICENSE_KEY_ID='vncode-1';
 try {
  const reg=await register(ctx),d=await device(),code=await approve(ctx,reg);
  const before=await activate(ctx,reg,d,code);assert.equal(before.status,200);assert.equal(before.data.lease.key_id,'vncode-1');
  const expiry=before.data.user.expires_at,deviceId=JSON.parse(Buffer.from(before.data.lease.payload,'base64url').toString()).deviceId;
  const rotated=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
  ctx.env.LICENSE_SIGNING_KEY=encode(await crypto.subtle.exportKey('pkcs8',rotated.privateKey));ctx.env.LICENSE_KEY_ID='vncode-2';
  const auth={authorization:'Bearer '+before.data.access_token};
  const renewal=await req(ctx.env,'/api/client/license/renew','POST',await proof(ctx,auth,d,'renew'),'',auth);
  assert.equal(renewal.status,200);assert.equal(renewal.data.lease.key_id,'vncode-2');assert.equal(renewal.data.user.expires_at,expiry);
  const payload=Buffer.from(renewal.data.lease.payload,'base64url'),signature=Buffer.from(renewal.data.lease.signature,'base64url');
  assert.equal(JSON.parse(payload.toString()).deviceId,deviceId);
  assert.equal(await crypto.subtle.verify('Ed25519',rotated.publicKey,signature,payload),true);
  assert.equal(await crypto.subtle.verify('Ed25519',ctx.keys.publicKey,signature,payload),false);
  const retry=await activate(ctx,reg,d,code);assert.equal(retry.status,200);assert.equal(retry.data.lease.key_id,'vncode-2');assert.equal(retry.data.user.expires_at,expiry);
  assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,1);
  assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
  assert.equal(ctx.env.sqlite.prepare('SELECT state FROM license_codes').get().state,'redeemed');
  assert.equal(ctx.env.sqlite.prepare('SELECT email FROM users WHERE id=?').get(reg.id).email,reg.email);
 }finally{ctx.env.sqlite.close();}
});

test('direct Gmail registration, owner approval, one-use key, signed lease and renewal',async()=>{
 const ctx=await setup(),reg=await register(ctx),d=await device();
 assert.equal(ctx.mail.length,0,'Registration must not send OTP');
 assert.equal(ctx.env.sqlite.prepare('SELECT email_verified FROM users WHERE id=?').get(reg.id).email_verified,0,'Entering Gmail is not mailbox verification');
 const pending=await req(ctx.env,'/api/admin/desktop/requests?status=pending','GET',null,ctx.cookie);
 assert.ok(pending.data.users.some(u=>u.id===reg.id&&u.email===reg.email));
 const status=await req(ctx.env,'/api/client/registration/status','GET',null,'',reg.auth);assert.equal(status.data.status,'pending');
 assert.equal((await activate(ctx,reg,d,'VNC-0000-0000-0000-0000-0000-0000-0000-0000')).status,403);
 const code=await approve(ctx,reg,{send_email:true});assert.ok(ctx.mail.at(-1).text.includes(code));
 const a=await activate(ctx,reg,d,code);assert.equal(a.status,200,JSON.stringify(a.data));assert.equal(a.data.user.email,reg.email);
 const grant=a.data.lease;assert.equal(await crypto.subtle.verify('Ed25519',ctx.keys.publicKey,Buffer.from(grant.signature,'base64url'),Buffer.from(grant.payload,'base64url')),true);
 const payload=JSON.parse(Buffer.from(grant.payload,'base64url'));assert.equal(payload.maxShops,2);assert.equal(payload.expiresAt-payload.issuedAt,72*3600);
 const expiry=a.data.user.expires_at;
 const retry=await activate(ctx,reg,d,code);assert.equal(retry.status,200);assert.equal(retry.data.user.expires_at,expiry);assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
 const auth={authorization:'Bearer '+a.data.access_token},body=await proof(ctx,auth,d,'renew');
 const renewal=await req(ctx.env,'/api/client/license/renew','POST',body,'',auth);assert.equal(renewal.status,200);
 assert.equal((await req(ctx.env,'/api/client/license/renew','POST',body,'',auth)).status,409);
 ctx.env.sqlite.close();
});

test('an actively renewed device session extends only near expiry without changing the subscription or device',async()=>{
 const ctx=await setup(),reg=await register(ctx),d=await device(),code=await approve(ctx,reg,{days:180});
 try {
  const activated=await activate(ctx,reg,d,code);assert.equal(activated.status,200);
  const auth={authorization:'Bearer '+activated.data.access_token},deviceId=activated.data.device_id;
  const readSession=()=>ctx.env.sqlite.prepare('SELECT expires_at FROM desktop_sessions WHERE device_id=?').get(deviceId).expires_at;
  const originalExpiration=activated.data.user.expires_at,originalSession=readSession();
  let writes=0;const prepare=ctx.env.DB.prepare.bind(ctx.env.DB);
  ctx.env.DB.prepare=sql=>{
   const statement=prepare(sql);
   if(sql.startsWith('UPDATE desktop_sessions SET expires_at=MAX')){
    const run=statement.run.bind(statement);statement.run=()=>{writes++;return run();};
   }
   return statement;
  };
  const first=await req(ctx.env,'/api/client/license/renew','POST',await proof(ctx,auth,d,'renew'),'',auth);
  assert.equal(first.status,200);assert.equal(writes,0,'No session write while more than seven days remain');
  assert.equal(readSession(),originalSession);
  for(let cycle=0;cycle<2;cycle++){
   const now=Math.floor(Date.now()/1000),near=now+2*86400;
   ctx.env.sqlite.prepare('UPDATE desktop_sessions SET expires_at=? WHERE device_id=?').run(near,deviceId);
   const renewed=await req(ctx.env,'/api/client/license/renew','POST',await proof(ctx,auth,d,'renew'),'',auth);
   assert.equal(renewed.status,200,JSON.stringify(renewed.data));
   assert.ok(readSession()>=now+29*86400,'The same active session gets approximately 30 days');
   assert.equal(renewed.data.access_token,undefined,'Renewal must not issue or rotate a bearer token');
   assert.equal(renewed.data.device_id,deviceId);assert.equal(renewed.data.user.expires_at,originalExpiration);
   assert.equal(await crypto.subtle.verify('Ed25519',ctx.keys.publicKey,Buffer.from(renewed.data.lease.signature,'base64url'),Buffer.from(renewed.data.lease.payload,'base64url')),true);
  }
  assert.equal(writes,2);
  const stable=readSession(),again=await req(ctx.env,'/api/client/license/renew','POST',await proof(ctx,auth,d,'renew'),'',auth);
  assert.equal(again.status,200);assert.equal(readSession(),stable);assert.equal(writes,2,'The next frequent poll does not write the session again');
  assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,1);
  assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
  assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM desktop_sessions').get().n,1);
 }finally{ctx.env.sqlite.close();}
});

test('simultaneous valid renewals near expiry keep one device and one session',async()=>{
 const ctx=await setup(),reg=await register(ctx),d=await device(),code=await approve(ctx,reg,{days:180});
 try {
  const activated=await activate(ctx,reg,d,code);assert.equal(activated.status,200);
  const auth={authorization:'Bearer '+activated.data.access_token},deviceId=activated.data.device_id,now=Math.floor(Date.now()/1000);
  ctx.env.sqlite.prepare('UPDATE desktop_sessions SET expires_at=? WHERE device_id=?').run(now+2*86400,deviceId);
  const first=await proof(ctx,auth,d,'renew'),second=await proof(ctx,auth,d,'renew');
  const replies=await Promise.all([first,second].map(body=>req(ctx.env,'/api/client/license/renew','POST',body,'',auth)));
  assert.deepEqual(replies.map(x=>x.status),[200,200]);
  for(const reply of replies){assert.equal(reply.data.device_id,deviceId);assert.equal(reply.data.user.expires_at,activated.data.user.expires_at);}
  assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM desktop_sessions').get().n,1);
  assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,1);
  assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
  assert.ok(ctx.env.sqlite.prepare('SELECT expires_at FROM desktop_sessions WHERE device_id=?').get(deviceId).expires_at>=now+29*86400);
 }finally{ctx.env.sqlite.close();}
});

test('session expiry or deletion during signing blocks a grant even when extension was not due',async()=>{
 for(const kind of ['expired','deleted']){
  const ctx=await setup(),reg=await register(ctx),d=await device(),code=await approve(ctx,reg,{days:180});
  try {
   const activated=await activate(ctx,reg,d,code);assert.equal(activated.status,200);
   const auth={authorization:'Bearer '+activated.data.access_token},deviceId=activated.data.device_id;
   const body=await proof(ctx,auth,d,'renew'),prepare=ctx.env.DB.prepare.bind(ctx.env.DB);let extensions=0;
   ctx.env.DB.prepare=sql=>{
    const statement=prepare(sql);
    if(sql.startsWith('UPDATE desktop_sessions SET expires_at=MAX'))extensions++;
    if(sql.startsWith('UPDATE license_devices SET last_seen_at=')){
     const run=statement.run.bind(statement);statement.run=()=>{
      if(kind==='expired')ctx.env.sqlite.prepare('UPDATE desktop_sessions SET expires_at=1 WHERE device_id=?').run(deviceId);
      else ctx.env.sqlite.prepare('DELETE FROM desktop_sessions WHERE device_id=?').run(deviceId);
      return run();
     };
    }
    return statement;
   };
   const result=await req(ctx.env,'/api/client/license/renew','POST',body,'',auth);
   assert.equal(result.status,403,kind);assert.equal(result.data.error,'LICENSE_CHANGED',kind);
   assert.equal(result.data.lease,undefined,kind);assert.equal(extensions,0,kind);
   const row=ctx.env.sqlite.prepare('SELECT expires_at FROM desktop_sessions WHERE device_id=?').get(deviceId);
   if(kind==='expired')assert.equal(row.expires_at,1);else assert.equal(row,undefined);
  }finally{ctx.env.sqlite.close();}
 }
});

test('expired sessions, revoked devices and invalid renewal proof cannot be extended',async()=>{
 for(const kind of ['expired-before-challenge','expired-after-challenge','invalid-proof','device-revoked','subscription-expired','signer-missing']){
  const ctx=await setup(),reg=await register(ctx),d=await device(),code=await approve(ctx,reg,{days:180});
  try {
   const activated=await activate(ctx,reg,d,code);assert.equal(activated.status,200);
   const auth={authorization:'Bearer '+activated.data.access_token},deviceId=activated.data.device_id;
   const near=Math.floor(Date.now()/1000)+2*86400;
   ctx.env.sqlite.prepare('UPDATE desktop_sessions SET expires_at=? WHERE device_id=?').run(near,deviceId);
   if(kind==='expired-before-challenge'){
    ctx.env.sqlite.prepare('UPDATE desktop_sessions SET expires_at=1 WHERE device_id=?').run(deviceId);
    const challenge=await req(ctx.env,'/api/client/license/challenge','POST',{action:'renew',public_key:d.public_key},'',auth);
    assert.equal(challenge.status,401);assert.equal(ctx.env.sqlite.prepare('SELECT expires_at FROM desktop_sessions WHERE device_id=?').get(deviceId).expires_at,1);continue;
   }
   const body=await proof(ctx,auth,d,'renew');
   if(kind==='expired-after-challenge')ctx.env.sqlite.prepare('UPDATE desktop_sessions SET expires_at=1 WHERE device_id=?').run(deviceId);
   if(kind==='invalid-proof')body.signature=encode(new Uint8Array(64));
   if(kind==='device-revoked')ctx.env.sqlite.prepare('UPDATE license_devices SET revoked=1 WHERE id=?').run(deviceId);
   if(kind==='subscription-expired')ctx.env.sqlite.prepare('UPDATE users SET license_expires_at=1 WHERE id=?').run(reg.id);
   if(kind==='signer-missing')delete ctx.env.LICENSE_SIGNING_KEY;
   const result=await req(ctx.env,'/api/client/license/renew','POST',body,'',auth);
   assert.equal(result.status,kind==='expired-after-challenge'?401:kind==='signer-missing'?503:403,kind);
   assert.equal(result.data.lease,undefined,kind);
   assert.equal(ctx.env.sqlite.prepare('SELECT expires_at FROM desktop_sessions WHERE device_id=?').get(deviceId).expires_at,kind==='expired-after-challenge'?1:near,kind);
  }finally{ctx.env.sqlite.close();}
 }
});

test('revocation, expiry or policy change during session extension never returns a grant or revives a session',async()=>{
 for(const kind of ['device-revoked','subscription-expired','session-expired','policy-changed']){
  const ctx=await setup(),reg=await register(ctx),d=await device(),code=await approve(ctx,reg,{days:180});
  try {
   const activated=await activate(ctx,reg,d,code);assert.equal(activated.status,200);
   const auth={authorization:'Bearer '+activated.data.access_token},deviceId=activated.data.device_id;
   const near=Math.floor(Date.now()/1000)+2*86400;
   ctx.env.sqlite.prepare('UPDATE desktop_sessions SET expires_at=? WHERE device_id=?').run(near,deviceId);
   const body=await proof(ctx,auth,d,'renew'),prepare=ctx.env.DB.prepare.bind(ctx.env.DB);
   ctx.env.DB.prepare=sql=>{
    const statement=prepare(sql);
    if(sql.startsWith('UPDATE desktop_sessions SET expires_at=MAX')){
     const run=statement.run.bind(statement);statement.run=()=>{
      if(kind==='device-revoked')ctx.env.sqlite.prepare('UPDATE license_devices SET revoked=1 WHERE id=?').run(deviceId);
      if(kind==='subscription-expired')ctx.env.sqlite.prepare('UPDATE users SET license_expires_at=1 WHERE id=?').run(reg.id);
      if(kind==='session-expired')ctx.env.sqlite.prepare('UPDATE desktop_sessions SET expires_at=1 WHERE device_id=?').run(deviceId);
      if(kind==='policy-changed')ctx.env.sqlite.prepare('UPDATE license_policy SET revision=revision+1 WHERE user_id=?').run(reg.id);
      return run();
     };
    }
    return statement;
   };
   const result=await req(ctx.env,'/api/client/license/renew','POST',body,'',auth);
   assert.equal(result.status,403,kind);assert.equal(result.data.error,'LICENSE_CHANGED',kind);assert.equal(result.data.lease,undefined,kind);
   assert.equal(ctx.env.sqlite.prepare('SELECT expires_at FROM desktop_sessions WHERE device_id=?').get(deviceId).expires_at,kind==='session-expired'?1:near,kind);
  }finally{ctx.env.sqlite.close();}
 }
});

test('Gmail required, email unavailable, OTP attempts and expiry, no anonymous status lookup',async()=>{
 const ctx=await setup();assert.equal((await req(ctx.env,'/api/client/registration/start','POST',{email:'a@example.com'})).status,400);
 const mail=ctx.env.EMAIL;delete ctx.env.EMAIL;assert.equal((await req(ctx.env,'/api/client/registration/start','POST',{email:'a@gmail.com'})).status,503);ctx.env.EMAIL=mail;
 const r=await req(ctx.env,'/api/client/registration/start','POST',{email:'a@gmail.com'}),auth={authorization:'Bearer '+r.data.registration_token};
 const correct=ctx.mail.at(-1).text.match(/\b\d{6}\b/)[0],wrong=correct==='000000'?'000001':'000000';
 for(let i=0;i<5;i++)assert.equal((await req(ctx.env,'/api/client/registration/verify','POST',{code:wrong},'',auth)).status,400);
 assert.equal((await req(ctx.env,'/api/client/registration/verify','POST',{code:correct},'',auth)).status,401);
 assert.equal((await req(ctx.env,'/api/client/registration/status')).status,401);
 const fresh=await req(ctx.env,'/api/client/registration/start','POST',{email:'b@gmail.com'});
 ctx.env.sqlite.prepare('UPDATE desktop_registrations SET expires_at=1 WHERE id=?').run(fresh.data.registration_id);
 assert.equal((await req(ctx.env,'/api/client/registration/verify','POST',{code:ctx.mail.at(-1).text.match(/\b\d{6}\b/)[0]},'',{authorization:'Bearer '+fresh.data.registration_token})).status,401);
 ctx.env.sqlite.close();
});

test('capacity allocation leaves blocked key unused; revoke permits replacement and blocks old proof',async()=>{
 const ctx=await setup(),reg=await register(ctx),d1=await device(),d2=await device(),code=await approve(ctx,reg);
 const first=await activate(ctx,reg,d1,code);assert.equal(first.status,200);
 const newCode=await approve(ctx,reg);const blocked=await activate(ctx,reg,d2,newCode);assert.equal(blocked.status,409);
 assert.equal(ctx.env.sqlite.prepare("SELECT COUNT(*) n FROM license_codes WHERE state='unused'").get().n,1);
 const revoked=await req(ctx.env,`/api/admin/desktop/devices/${first.data.device_id}/revoke`,'POST',{},ctx.cookie);assert.equal(revoked.status,200);assert.ok(revoked.data.last_offline_expires_at);
 assert.equal((await req(ctx.env,'/api/client/license/challenge','POST',{action:'renew',public_key:d1.public_key},'',{authorization:'Bearer '+first.data.access_token})).status,403);
 assert.equal((await activate(ctx,reg,d1,code)).status,403);
 assert.equal((await activate(ctx,reg,d2,newCode)).status,200);
 ctx.env.sqlite.close();
});

test('wrong owner, invalid proof, suspension, expiry, signing config and admin isolation',async()=>{
 const ctx=await setup(),reg=await register(ctx),other=await register(ctx,'other@gmail.com'),d=await device(),code=await approve(ctx,reg);await approve(ctx,other);
 assert.equal((await activate(ctx,other,d,code)).status,403);
 const b=await proof(ctx,reg.auth,d,'activate');b.signature=encode(new Uint8Array(64));
 assert.equal((await req(ctx.env,'/api/client/license/activate','POST',{...b,code},'',reg.auth)).status,403);
 assert.equal((await req(ctx.env,'/api/admin/desktop/requests','GET',null,'',reg.auth)).status,403);
 const key=ctx.env.LICENSE_SIGNING_KEY;delete ctx.env.LICENSE_SIGNING_KEY;
 assert.equal((await activate(ctx,reg,d,code)).status,503);assert.equal(ctx.env.sqlite.prepare("SELECT COUNT(*) n FROM license_redemptions").get().n,0);ctx.env.LICENSE_SIGNING_KEY=key;
 const a=await activate(ctx,reg,d,code),auth={authorization:'Bearer '+a.data.access_token};
 ctx.env.sqlite.prepare('UPDATE users SET license_expires_at=1 WHERE id=?').run(reg.id);
 assert.equal((await req(ctx.env,'/api/client/license/renew','POST',await proof(ctx,auth,d,'renew'),'',auth)).status,403);
 ctx.env.sqlite.prepare("UPDATE users SET license_expires_at=?,status='approved' WHERE id=?").run(Math.floor(Date.now()/1000)+86400,reg.id);
 await req(ctx.env,`/api/admin/users/${reg.id}/status`,'POST',{status:'suspended'},ctx.cookie);
 assert.equal((await req(ctx.env,'/api/client/license/challenge','POST',{action:'renew',public_key:d.public_key},'',auth)).status,401);
 assert.equal((await activate(ctx,reg,d,code)).status,403);
 ctx.env.sqlite.close();
});

test('additive desktop migration preserves old users and can run twice',()=>{
 const db=new DatabaseSync(':memory:');const schema=readFileSync(new URL('../schema.sql',import.meta.url),'utf8');db.exec(schema.split('-- Additive and repeatable.')[0]);
 db.prepare("INSERT INTO users(id,email,password_salt,password_hash,role,status,email_verified,created_at,updated_at) VALUES('old','old@gmail.com','salt','hash','user','approved',1,1,1)").run();
 const migration=readFileSync(new URL('../migrations/0002_desktop_license.sql',import.meta.url),'utf8');db.exec(migration);db.exec(migration);
 assert.equal(db.prepare('SELECT password_hash FROM users WHERE id=?').get('old').password_hash,'hash');assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(),[]);db.close();
});

test('registration does not reset existing web password, approval, or license; OTP cannot replay',async()=>{
 const ctx=await setup();const c={email:'existing@gmail.com',password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'};await req(ctx.env,'/api/register','POST',c);
 const before=ctx.env.sqlite.prepare('SELECT * FROM users WHERE email=?').get(c.email),reg=await registerLegacy(ctx,c.email);
 const after=ctx.env.sqlite.prepare('SELECT * FROM users WHERE email=?').get(c.email);assert.equal(after.id,before.id);assert.equal(after.password_hash,before.password_hash);assert.equal(after.status,'pending');
 assert.equal((await req(ctx.env,'/api/client/registration/verify','POST',{code:ctx.mail.at(-1).text.match(/\b\d{6}\b/)[0]},'',reg.auth)).status,401);
 const user=await req(ctx.env,'/api/login','POST',c);assert.equal(user.status,200);ctx.env.sqlite.close();
});

test('concurrent activations cannot exceed device limit or extend the same code twice',async()=>{
 const ctx=await setup(),reg=await register(ctx),code=await approve(ctx,reg),d1=await device(),d2=await device();
 const b1=await proof(ctx,reg.auth,d1,'activate'),b2=await proof(ctx,reg.auth,d2,'activate');
 const results=await Promise.all([req(ctx.env,'/api/client/license/activate','POST',{...b1,code},'',reg.auth),req(ctx.env,'/api/client/license/activate','POST',{...b2,code},'',reg.auth)]);
 assert.deepEqual(results.map(r=>r.status).sort(),[200,409]);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices WHERE revoked=0').get().n,1);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
 assert.deepEqual(ctx.env.sqlite.prepare('PRAGMA foreign_key_check').all(),[]);ctx.env.sqlite.close();
});

test('policy limits validated; email delivery failure leaves no pending verification; cross-origin mutation refused',async()=>{
 const ctx=await setup(),reg=await register(ctx);
 assert.equal((await req(ctx.env,`/api/admin/desktop/users/${reg.id}/policy`,'POST',{max_devices:0,max_shops:1,offline_hours:72},ctx.cookie)).status,400);
 assert.equal((await req(ctx.env,'/api/client/registration/start','POST',{email:'a@gmail.com'},'',{origin:'https://evil.example'})).status,403);
 ctx.env.EMAIL={send:async()=>{throw new Error('simulated failure');}};
 assert.equal((await req(ctx.env,'/api/client/registration/start','POST',{email:'failed@gmail.com'})).status,503);
 assert.equal(ctx.env.sqlite.prepare("SELECT COUNT(*) n FROM desktop_registrations WHERE email='failed@gmail.com'").get().n,0);ctx.env.sqlite.close();
});


test('direct registration works without mail or signing config and requires Gmail',async()=>{
 const ctx=await setup();delete ctx.env.EMAIL;delete ctx.env.LICENSE_SIGNING_KEY;ctx.env.REQUIRE_EMAIL_VERIFICATION='1';
 for(const email of ['a@example.com','',null,42])assert.equal((await req(ctx.env,'/api/client/registration/register','POST',{email})).status,400);
 const r=await req(ctx.env,'/api/client/registration/register','POST',{email:'  Client.Name+Shop@Gmail.com  '});
 assert.equal(r.status,201);assert.equal(r.data.email,'client.name+shop@gmail.com');assert.equal(r.data.status,'pending');assert.equal(ctx.mail.length,0);
 assert.equal((await req(ctx.env,'/api/admin/desktop/settings','GET',null,ctx.cookie)).data.registration_requires_otp,false);
 assert.equal((await req(ctx.env,'/api/client/registration/register','POST',{email:'a@gmail.com'},'',{origin:'https://evil.example'})).status,403);
 ctx.env.sqlite.close();
});

test('repeat Gmail registration preserves approval, sessions and verification without granting rights',async()=>{
 const ctx=await setup(),reg=await register(ctx),d=await device(),code=await approve(ctx,reg);
 const first=await activate(ctx,reg,d,code);assert.equal(first.status,200);
 const before=ctx.env.sqlite.prepare('SELECT * FROM users WHERE id=?').get(reg.id);
 const again=await register(ctx);assert.equal(again.id,reg.id);assert.notEqual(again.token,reg.token);
 assert.deepEqual(ctx.env.sqlite.prepare('SELECT * FROM users WHERE id=?').get(reg.id),before,'Anonymous registration must not modify an existing account');
 assert.equal((await req(ctx.env,'/api/client/license/challenge','POST',{action:'renew',public_key:d.public_key},'',again.auth)).status,401);
 assert.equal((await req(ctx.env,'/api/me','GET',null,'',again.auth)).status,401);
 assert.equal((await req(ctx.env,'/api/admin/desktop/requests','GET',null,'',again.auth)).status,403);
 assert.equal((await activate(ctx,again,await device(),'VNC'+'-0000'.repeat(8))).status,403);
 const auth={authorization:'Bearer '+first.data.access_token};
 assert.equal((await req(ctx.env,'/api/client/license/renew','POST',await proof(ctx,auth,d,'renew'),'',auth)).status,200);
 const retry=await activate(ctx,again,d,code);assert.equal(retry.status,200);assert.equal(retry.data.user.expires_at,first.data.user.expires_at);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM users WHERE email=?').get(reg.email).n,1);
 assert.equal((await req(ctx.env,'/api/client/registration/verify','POST',{code:'123456'},'',again.auth)).status,401);
 ctx.env.sqlite.prepare('UPDATE desktop_registrations SET expires_at=1').run();
 assert.equal((await req(ctx.env,'/api/client/registration/status','GET',null,'',again.auth)).status,401);ctx.env.sqlite.close();
});

test('direct registration never changes an existing web password or verified flag',async()=>{
 const ctx=await setup(),web={email:'web@gmail.com',password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'};
 await req(ctx.env,'/api/register','POST',web);const before=ctx.env.sqlite.prepare('SELECT * FROM users WHERE email=?').get(web.email);
 await register(ctx,web.email);assert.deepEqual(ctx.env.sqlite.prepare('SELECT * FROM users WHERE email=?').get(web.email),before);
 assert.equal((await req(ctx.env,'/api/login','POST',web)).status,200);ctx.env.sqlite.close();
});


test('one license activates multiple independent machines up to the owner limit without Gmail entry',async()=>{
 const ctx=await setup(),reg=await register(ctx),code=await approve(ctx,reg,{max_devices:2,max_shops:10});
 const enroll=async()=>{
  const r=await req(ctx.env,'/api/client/registration/license','POST',{code});assert.equal(r.status,201,JSON.stringify(r.data));
  assert.equal(r.data.email,reg.email);assert.equal(r.data.status,'approved');
  return {auth:{authorization:'Bearer '+r.data.registration_token}};
 };
 const d1=await device(),d2=await device(),d3=await device();
 const first=await activate(ctx,await enroll(),d1,code),second=await activate(ctx,await enroll(),d2,code);
 assert.equal(first.status,200);assert.equal(second.status,200);assert.notEqual(first.data.device_id,second.data.device_id);
 assert.equal(second.data.user.expires_at,first.data.user.expires_at,'A shared key must not extend time twice');
 assert.equal(JSON.parse(Buffer.from(second.data.lease.payload,'base64url')).maxShops,10);
 assert.equal((await activate(ctx,await enroll(),d3,code)).status,409);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices WHERE revoked=0').get().n,2);
 assert.equal(ctx.mail.length,0);ctx.env.sqlite.close();
});

test('code enrollment rejects invalid, void, suspended or expired keys and never grants session rights',async()=>{
 const ctx=await setup(),reg=await register(ctx),code=await approve(ctx,reg);
 assert.equal((await req(ctx.env,'/api/client/registration/license','POST',{code:'VNC'+'-0000'.repeat(8)})).status,403);
 const r=await req(ctx.env,'/api/client/registration/license','POST',{code});assert.equal(r.status,201);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,0);
 assert.equal(ctx.env.sqlite.prepare("SELECT COUNT(*) n FROM license_codes WHERE state='unused'").get().n,1);
 const auth={authorization:'Bearer '+r.data.registration_token};
 assert.equal((await req(ctx.env,'/api/client/license/challenge','POST',{action:'renew',public_key:(await device()).public_key},'',auth)).status,401);
 ctx.env.sqlite.prepare("UPDATE users SET status='suspended' WHERE id=?").run(reg.id);
 assert.equal((await req(ctx.env,'/api/client/registration/license','POST',{code})).status,403);
 ctx.env.sqlite.prepare("UPDATE users SET status='approved' WHERE id=?").run(reg.id);
 ctx.env.sqlite.prepare("UPDATE license_codes SET state='void'").run();
 assert.equal((await req(ctx.env,'/api/client/registration/license','POST',{code})).status,403);
 ctx.env.sqlite.prepare("UPDATE license_codes SET state='redeemed',redeemed_by=?").run(reg.id);
 ctx.env.sqlite.prepare('UPDATE users SET license_expires_at=1 WHERE id=?').run(reg.id);
 assert.equal((await req(ctx.env,'/api/client/registration/license','POST',{code})).status,403);ctx.env.sqlite.close();
});
