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
 return {env,mail,keys,cookie:login.cookie.split(';')[0]};
}
async function register(ctx,email='client@gmail.com'){
 const r=await req(ctx.env,'/api/client/registration/start','POST',{email});assert.equal(r.status,201,JSON.stringify(r.data));
 const otp=ctx.mail.at(-1).text.match(/\b\d{6}\b/)[0],auth={authorization:'Bearer '+r.data.registration_token};
 const v=await req(ctx.env,'/api/client/registration/verify','POST',{code:otp},'',auth);assert.equal(v.status,200,JSON.stringify(v.data));
 return {auth,email,id:ctx.env.sqlite.prepare('SELECT id FROM users WHERE email=?').get(email).id,token:r.data.registration_token};
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

test('desktop Gmail verification, owner approval, one-use key, signed lease and renewal',async()=>{
 const ctx=await setup(),reg=await register(ctx),d=await device();
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
 const before=ctx.env.sqlite.prepare('SELECT * FROM users WHERE email=?').get(c.email),reg=await register(ctx,c.email);
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
