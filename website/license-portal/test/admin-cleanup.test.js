import {test} from 'node:test';
import assert from 'node:assert/strict';
import {envSetup,req} from './fixtures.js';

const creds=email=>({email,password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'});
async function setup() {
 const keys=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const env=envSetup({LICENSE_KEY_ID:'cleanup-test',LICENSE_SIGNING_KEY:Buffer.from(await crypto.subtle.exportKey('pkcs8',keys.privateKey)).toString('base64url')});
 await req(env,'/api/register','POST',creds('owner@example.test'));
 env.sqlite.prepare("UPDATE users SET role='admin',status='approved' WHERE email='owner@example.test'").run();
 const login=await req(env,'/api/login','POST',creds('owner@example.test'));
 return {env,cookie:login.cookie.split(';')[0],admin:login.data.user};
}
async function register(ctx,email='customer@gmail.com') {
 const out=await req(ctx.env,'/api/client/registration/register','POST',{email});
 assert.equal(out.status,201,JSON.stringify(out.data));
 const user=ctx.env.sqlite.prepare('SELECT * FROM users WHERE email=?').get(email);
 return {user,auth:{authorization:'Bearer '+out.data.registration_token},registration:out.data};
}
async function issue(ctx,user) {
 const out=await req(ctx.env,`/api/admin/desktop/users/${user.id}/approve-issue`,'POST',{days:30,max_devices:2,max_shops:10,offline_hours:72},ctx.cookie);
 assert.equal(out.status,200,JSON.stringify(out.data));
 return out.data.code;
}
async function activate(ctx,reg,code) {
 const keys=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const public_key=Buffer.from(await crypto.subtle.exportKey('raw',keys.publicKey)).toString('base64url');
 const challenge=await req(ctx.env,'/api/client/license/challenge','POST',{action:'activate',public_key},'',reg.auth);
 assert.equal(challenge.status,200);
 const signature=Buffer.from(await crypto.subtle.sign('Ed25519',keys.privateKey,new TextEncoder().encode(challenge.data.message))).toString('base64url');
 const out=await req(ctx.env,'/api/client/license/activate','POST',{challenge_id:challenge.data.challenge_id,public_key,signature,code},'',reg.auth);
 assert.equal(out.status,200,JSON.stringify(out.data));return out.data;
}
const remove=(ctx,user,body={confirm_email:user.email},cookie=ctx.cookie)=>req(ctx.env,`/api/admin/users/${user.id}/delete`,'POST',body,cookie);

test('registered Gmail is visible to admin; deletion removes old rights and re-registration starts pending',async()=>{
 const ctx=await setup();
 await req(ctx.env,'/api/register','POST',creds('customer@gmail.com'));
 const reg=await register(ctx),other=await register(ctx,'other@gmail.com');
 const list=await req(ctx.env,'/api/admin/desktop/requests?status=pending','GET',null,ctx.cookie);
 assert.ok(list.data.users.some(u=>u.id===reg.user.id&&u.email===reg.user.email));
 const code=await issue(ctx,reg.user),grant=await activate(ctx,reg,code);
 await issue(ctx,reg.user);const otherCode=await issue(ctx,other.user);
 const login=await req(ctx.env,'/api/login','POST',creds(reg.user.email));const webCookie=login.cookie.split(';')[0];
 ctx.env.sqlite.prepare("INSERT INTO action_tokens VALUES('action',?,'reset',9999999999,1)").run(reg.user.id);
 const out=await remove(ctx,reg.user);assert.equal(out.status,200,JSON.stringify(out.data));
 assert.equal(out.data.registration_reset,true);assert.ok(out.data.last_offline_expires_at);
 for(const table of ['desktop_registrations','license_devices','license_policy','license_redemptions','sessions','action_tokens'])
  assert.equal(ctx.env.sqlite.prepare(`SELECT COUNT(*) n FROM ${table} WHERE user_id=?`).get(reg.user.id).n,0,table);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_codes WHERE assigned_user_id=?').get(reg.user.id).n,0);
 assert.equal((await req(ctx.env,'/api/client/registration/status','GET',null,'',reg.auth)).status,401);
 assert.equal((await req(ctx.env,'/api/me','GET',null,webCookie)).status,401);
 assert.equal((await req(ctx.env,'/api/client/license/challenge','POST',{action:'renew',public_key:'A'.repeat(43)},'',{authorization:'Bearer '+grant.access_token})).status,401);
 assert.equal((await req(ctx.env,'/api/client/registration/license','POST',{code})).status,403);
 const fresh=await register(ctx);assert.notEqual(fresh.user.id,reg.user.id);
 assert.equal(fresh.user.status,'pending');assert.equal(fresh.user.license_expires_at,null);assert.equal(fresh.user.email_verified,0);
 const next=await req(ctx.env,'/api/admin/desktop/requests?status=pending','GET',null,ctx.cookie);
 assert.ok(next.data.users.some(u=>u.id===fresh.user.id&&u.email===fresh.user.email));
 assert.equal((await req(ctx.env,'/api/client/registration/license','POST',{code:otherCode})).status,201);
 assert.deepEqual(ctx.env.sqlite.prepare('PRAGMA foreign_key_check').all(),[]);
 const audit=ctx.env.sqlite.prepare("SELECT * FROM audit_log WHERE action='account_deleted'").all();
 assert.equal(audit.length,1);assert.equal(audit[0].target_id,reg.user.id);assert.ok(!audit[0].detail.includes(reg.user.email));
 ctx.env.sqlite.close();
});

test('only canceled, never-redeemed licenses can be deleted, with explicit confirmation and audit',async()=>{
 const ctx=await setup(),reg=await register(ctx);const unused=await issue(ctx,reg.user);
 const code=ctx.env.sqlite.prepare('SELECT * FROM license_codes').get(),path=`/api/admin/license-codes/${code.id}/delete`;
 assert.equal((await req(ctx.env,path,'POST',{},ctx.cookie)).data.error,'DELETE_CONFIRMATION_REQUIRED');
 assert.equal((await req(ctx.env,path,'POST',{confirm:true},ctx.cookie)).status,409);
 await activate(ctx,reg,unused);
 assert.equal((await req(ctx.env,path,'POST',{confirm:true},ctx.cookie)).status,409);
 await issue(ctx,reg.user);const canceled=ctx.env.sqlite.prepare("SELECT id FROM license_codes WHERE state='unused'").get();
 assert.equal((await req(ctx.env,`/api/admin/license-codes/${canceled.id}/void`,'POST',{},ctx.cookie)).status,200);
 const deleted=await req(ctx.env,`/api/admin/license-codes/${canceled.id}/delete`,'POST',{confirm:true},ctx.cookie);assert.equal(deleted.status,200);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_codes WHERE id=?').get(canceled.id).n,0);
 assert.equal(ctx.env.sqlite.prepare('SELECT state FROM license_codes WHERE id=?').get(code.id).state,'redeemed');
 assert.equal(ctx.env.sqlite.prepare("SELECT COUNT(*) n FROM audit_log WHERE action='license_code_deleted'").get().n,1);
 assert.equal((await req(ctx.env,`/api/admin/license-codes/${canceled.id}/delete`,'POST',{confirm:true},ctx.cookie)).status,409);
 ctx.env.sqlite.close();
});

test('account deletion requires admin authority and exact email; admin and cross-origin deletion refused',async()=>{
 const ctx=await setup(),reg=await register(ctx);
 assert.equal((await remove(ctx,reg.user,{},'')).status,401);
 assert.equal((await req(ctx.env,`/api/admin/users/${reg.user.id}/delete`,'POST',{confirm_email:reg.user.email},'',reg.auth)).status,401);
 await req(ctx.env,'/api/register','POST',creds('webcustomer@gmail.com'));
 const login=await req(ctx.env,'/api/login','POST',creds('webcustomer@gmail.com'));
 assert.equal((await remove(ctx,reg.user,{confirm_email:reg.user.email},login.cookie.split(';')[0])).status,403);
 assert.equal((await remove(ctx,reg.user,{confirm_email:'wrong@gmail.com'})).data.error,'EMAIL_CONFIRMATION_REQUIRED');
 assert.equal((await remove(ctx,ctx.admin)).data.error,'ADMIN_ACCOUNT_CANNOT_BE_DELETED');
 assert.equal((await req(ctx.env,`/api/admin/users/${reg.user.id}/delete`,'POST',{confirm_email:reg.user.email},ctx.cookie,{origin:'https://evil.example'})).status,403);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM users').get().n,3);
 ctx.env.sqlite.close();
});

test('deleting Gmail resets its registration quota but preserves shared IP quota',async()=>{
 const ctx=await setup();const reg=await register(ctx);await register(ctx);await register(ctx);
 assert.equal((await req(ctx.env,'/api/client/registration/register','POST',{email:reg.user.email})).status,429);
 const before=ctx.env.sqlite.prepare("SELECT count FROM rate_limits WHERE key='desktop-register-ip:local'").get().count;
 assert.equal((await remove(ctx,reg.user)).status,200);
 assert.equal(ctx.env.sqlite.prepare("SELECT count FROM rate_limits WHERE key='desktop-register-ip:local'").get().count,before);
 const fresh=await register(ctx);assert.notEqual(fresh.user.id,reg.user.id);assert.equal(fresh.user.status,'pending');
 ctx.env.sqlite.close();
});

test('failure of final account deletion rolls back all credential, license and audit removal',async()=>{
 const ctx=await setup(),reg=await register(ctx);const code=await issue(ctx,reg.user);await activate(ctx,reg,code);
 ctx.env.sqlite.exec("CREATE TRIGGER reject_delete BEFORE DELETE ON users BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END");
 assert.equal((await remove(ctx,reg.user)).status,500);
 assert.ok(ctx.env.sqlite.prepare('SELECT id FROM users WHERE id=?').get(reg.user.id));
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM desktop_sessions').get().n,1);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_redemptions').get().n,1);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM desktop_challenges').get().n,1);
 assert.equal(ctx.env.sqlite.prepare("SELECT COUNT(*) n FROM audit_log WHERE action='account_deleted'").get().n,0);
 assert.equal((await req(ctx.env,'/api/client/registration/status','GET',null,'',reg.auth)).status,200);
 ctx.env.sqlite.close();
});

test('overlapping deletes cannot duplicate audit or affect another account',async()=>{
 const ctx=await setup(),reg=await register(ctx),other=await register(ctx,'other@gmail.com');
 const out=await Promise.all([remove(ctx,reg.user),remove(ctx,reg.user)]);
 assert.equal(out.filter(r=>r.status===200).length,1);
 assert.ok(out.some(r=>[404,409].includes(r.status)));
 assert.equal(ctx.env.sqlite.prepare("SELECT COUNT(*) n FROM audit_log WHERE action='account_deleted'").get().n,1);
 assert.ok(ctx.env.sqlite.prepare('SELECT id FROM users WHERE id=?').get(other.user.id));
 ctx.env.sqlite.close();
});

test('role change during deletion preserves the newly promoted admin and all its records',async()=>{
 const ctx=await setup(),reg=await register(ctx);await issue(ctx,reg.user);
 const batch=ctx.env.DB.batch;let once=true;
 ctx.env.DB.batch=prepared=>{
  if(once){once=false;ctx.env.sqlite.prepare("UPDATE users SET role='admin' WHERE id=?").run(reg.user.id);}
  return batch(prepared);
 };
 assert.equal((await remove(ctx,reg.user)).status,409);
 assert.equal(ctx.env.sqlite.prepare('SELECT role FROM users WHERE id=?').get(reg.user.id).role,'admin');
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_codes').get().n,1);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM desktop_registrations').get().n,1);
 assert.equal(ctx.env.sqlite.prepare("SELECT COUNT(*) n FROM audit_log WHERE action='account_deleted'").get().n,0);
 ctx.env.sqlite.close();
});

test('foreign license issuance history prevents deleting another customer entitlement',async()=>{
 const ctx=await setup(),reg=await register(ctx),other=await register(ctx,'other@gmail.com');await issue(ctx,other.user);
 ctx.env.sqlite.prepare('UPDATE license_codes SET created_by=? WHERE assigned_user_id=?').run(reg.user.id,other.user.id);
 assert.equal((await remove(ctx,reg.user)).data.error,'ACCOUNT_HAS_FOREIGN_LICENSE_HISTORY');
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM users WHERE id=?').get(reg.user.id).n,1);
 assert.equal(ctx.env.sqlite.prepare('SELECT COUNT(*) n FROM license_codes').get().n,1);
 ctx.env.sqlite.close();
});

test('admin can filter canceled licenses beyond the first page without hiding active codes from total counts',async()=>{
 const ctx=await setup(),reg=await register(ctx);
 const insert=ctx.env.sqlite.prepare("INSERT INTO license_codes(id,code_hash,code_last4,days,assigned_user_id,created_by,state,created_at,updated_at) VALUES(?,?,?,30,?,?,'void',1,1)");
 for(let i=0;i<55;i++)insert.run(crypto.randomUUID(),'hash-'+i,'0000',reg.user.id,ctx.admin.id);
 await issue(ctx,reg.user);
 const filtered=await req(ctx.env,'/api/admin/license-codes?state=void&page=1','GET',null,ctx.cookie);
 assert.equal(filtered.status,200);assert.equal(filtered.data.total,55);assert.equal(filtered.data.codes.length,5);
 assert.ok(filtered.data.codes.every(code=>code.state==='void'));
 const all=await req(ctx.env,'/api/admin/license-codes','GET',null,ctx.cookie);assert.equal(all.data.total,56);
 assert.equal((await req(ctx.env,'/api/admin/license-codes?state=untrusted','GET',null,ctx.cookie)).status,400);
 ctx.env.sqlite.close();
});
