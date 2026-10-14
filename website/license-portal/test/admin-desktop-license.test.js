import {test} from 'node:test';
import assert from 'node:assert/strict';
import {envSetup,req} from './fixtures.js';
const encode=bytes=>Buffer.from(bytes).toString('base64url');
async function setup(email='owner@gmail.com') {
 const keys=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const env=envSetup({LICENSE_KEY_ID:'admin-license-test',LICENSE_SIGNING_KEY:encode(await crypto.subtle.exportKey('pkcs8',keys.privateKey))});
 const credentials={email,password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'};
 await req(env,'/api/register','POST',credentials);
 env.sqlite.prepare("UPDATE users SET role='admin',status='approved' WHERE email=?").run(email);
 const login=await req(env,'/api/login','POST',credentials);
 return {env,keys,credentials,user:login.data.user,cookie:login.cookie.split(';')[0]};
}
async function register(ctx,email=ctx.user.email) {
 const r=await req(ctx.env,'/api/client/registration/register','POST',{email});assert.equal(r.status,201,JSON.stringify(r.data));
 return {data:r.data,auth:{authorization:'Bearer '+r.data.registration_token}};
}
async function issue(ctx) {
 const r=await req(ctx.env,`/api/admin/desktop/users/${ctx.user.id}/approve-issue`,'POST',{days:30,max_devices:2,max_shops:10,offline_hours:72},ctx.cookie);
 assert.equal(r.status,200,JSON.stringify(r.data));return r.data.code;
}
async function device() {
 const keys=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 return {keys,public_key:encode(await crypto.subtle.exportKey('raw',keys.publicKey))};
}
async function proof(ctx,auth,d,action='activate') {
 const r=await req(ctx.env,'/api/client/license/challenge','POST',{action,public_key:d.public_key},'',auth);assert.equal(r.status,200,JSON.stringify(r.data));
 return {challenge_id:r.data.challenge_id,public_key:d.public_key,signature:encode(await crypto.subtle.sign('Ed25519',d.keys.privateKey,new TextEncoder().encode(r.data.message)))};
}
async function activate(ctx,reg,d,code) {
 return req(ctx.env,'/api/client/license/activate','POST',{...await proof(ctx,reg.auth,d),code},'',reg.auth);
}

test('admin Gmail receives a bounded desktop license while preserving website role, password and sessions',async()=>{
 const ctx=await setup();
 const before=ctx.env.sqlite.prepare('SELECT * FROM users WHERE id=?').get(ctx.user.id);
 const reg=await register(ctx);assert.equal(reg.data.status,'approved');
 const after=ctx.env.sqlite.prepare('SELECT * FROM users WHERE id=?').get(ctx.user.id);
 assert.equal(after.id,before.id);assert.equal(after.role,'admin');assert.equal(after.password_hash,before.password_hash);
 assert.equal(after.email_verified,before.email_verified);assert.equal(after.license_expires_at,null);
 const visible=await req(ctx.env,'/api/admin/desktop/requests?status=approved&search=owner%40gmail.com','GET',null,ctx.cookie);
 assert.ok(visible.data.users.some(u=>u.id===ctx.user.id&&u.role==='admin'));
 const denied=await activate(ctx,reg,await device(),'VNC'+'-0000'.repeat(8));assert.equal(denied.status,403);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,0);
 const code=await issue(ctx),d1=await device();
 const grant=await activate(ctx,reg,d1,code);assert.equal(grant.status,200,JSON.stringify(grant.data));
 assert.equal(await crypto.subtle.verify('Ed25519',ctx.keys.publicKey,Buffer.from(grant.data.lease.signature,'base64url'),Buffer.from(grant.data.lease.payload,'base64url')),true);
 const payload=JSON.parse(Buffer.from(grant.data.lease.payload,'base64url'));assert.equal(payload.maxShops,10);assert.equal(payload.maxDevices,2);
 assert.ok(!payload.permissions.includes('admin'));
 const enrolled=await req(ctx.env,'/api/client/registration/license','POST',{code});assert.equal(enrolled.status,201);
 const reg2={auth:{authorization:'Bearer '+enrolled.data.registration_token}},d2=await device();
 const second=await activate(ctx,reg2,d2,code);assert.equal(second.status,200);assert.equal(second.data.user.expires_at,grant.data.user.expires_at);
 const retry=await activate(ctx,reg,d1,code);assert.equal(retry.status,200);assert.equal(retry.data.user.expires_at,grant.data.user.expires_at);
 const third=await activate(ctx,reg2,await device(),code);assert.equal(third.status,409);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,2);
 const auth={authorization:'Bearer '+grant.data.access_token};
 const renewal=await req(ctx.env,'/api/client/license/renew','POST',await proof(ctx,auth,d1,'renew'),'',auth);assert.equal(renewal.status,200);
 assert.equal((await req(ctx.env,'/api/admin/users','GET',null,ctx.cookie)).status,200);
 assert.equal((await req(ctx.env,'/api/login','POST',ctx.credentials)).data.user.role,'admin');
 assert.equal((await req(ctx.env,'/api/admin/users','GET',null,'',reg.auth)).status,401);
 assert.equal((await req(ctx.env,'/api/admin/users','GET',null,'',auth)).status,401);
 assert.equal((await req(ctx.env,'/api/admin/desktop/requests','GET',null,'',auth)).status,403);
 assert.equal((await req(ctx.env,`/api/admin/users/${ctx.user.id}/delete`,'POST',{confirm_email:ctx.user.email},ctx.cookie)).status,403);
 assert.deepEqual(ctx.env.sqlite.prepare('PRAGMA foreign_key_check').all(),[]);ctx.env.sqlite.close();
});

test('website admin authority does not bypass desktop subscription expiry or code ownership',async()=>{
 const ctx=await setup(),reg=await register(ctx),seller=await register(ctx,'seller@gmail.com'),code=await issue(ctx),d=await device();
 assert.equal((await activate(ctx,seller,d,code)).status,403);
 const grant=await activate(ctx,reg,d,code);assert.equal(grant.status,200);
 ctx.env.sqlite.prepare('UPDATE users SET license_expires_at=1 WHERE id=?').run(ctx.user.id);
 const auth={authorization:'Bearer '+grant.data.access_token};
 assert.equal((await req(ctx.env,'/api/client/license/renew','POST',await proof(ctx,auth,d,'renew'),'',auth)).status,403);
 assert.equal((await req(ctx.env,'/api/admin/users','GET',null,ctx.cookie)).status,200,'Desktop expiry must not remove website administration');
 assert.equal((await req(ctx.env,'/api/client/registration/license','POST',{code})).status,403);
 ctx.env.sqlite.close();
});

test('traditional admin code issuance also supports the admin Gmail without changing its role',async()=>{
 const ctx=await setup(),reg=await register(ctx);
 const issued=await req(ctx.env,'/api/admin/license-codes','POST',{user_id:ctx.user.id,days:10},ctx.cookie);
 assert.equal(issued.status,201,JSON.stringify(issued.data));
 assert.equal((await activate(ctx,reg,await device(),issued.data.code)).status,200);
 assert.equal(ctx.env.sqlite.prepare('SELECT role FROM users WHERE id=?').get(ctx.user.id).role,'admin');ctx.env.sqlite.close();
});

test('desktop license issuance rejects a non-Gmail admin without changing website credentials',async()=>{
 const ctx=await setup('owner@example.test');
 const r=await req(ctx.env,`/api/admin/desktop/users/${ctx.user.id}/approve-issue`,'POST',{days:30,max_devices:1,max_shops:1,offline_hours:72},ctx.cookie);
 assert.equal(r.status,400);assert.equal(r.data.error,'GMAIL_REQUIRED');
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_codes').get().n,0);
 assert.equal((await req(ctx.env,'/api/admin/users','GET',null,ctx.cookie)).status,200);ctx.env.sqlite.close();
});

test('admin device revocation remains effective and a desktop credential cannot issue another code',async()=>{
 const ctx=await setup(),reg=await register(ctx),code=await issue(ctx),d=await device();
 const grant=await activate(ctx,reg,d,code);assert.equal(grant.status,200);
 const auth={authorization:'Bearer '+grant.data.access_token};
 assert.equal((await req(ctx.env,`/api/admin/desktop/users/${ctx.user.id}/approve-issue`,'POST',{days:30,max_devices:2,max_shops:10,offline_hours:72},'',auth)).status,403);
 assert.equal((await req(ctx.env,`/api/admin/desktop/devices/${grant.data.device_id}/revoke`,'POST',{},ctx.cookie)).status,200);
 assert.equal((await req(ctx.env,'/api/client/license/challenge','POST',{action:'renew',public_key:d.public_key},'',auth)).status,403);
 assert.equal((await activate(ctx,reg,d,code)).status,403);ctx.env.sqlite.close();
});
