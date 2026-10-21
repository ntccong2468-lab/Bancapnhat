import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {envSetup} from './fixtures.js';
import worker from '../src/worker.js';
import {cleanupExpired} from '../src/maintenance.js';

function fixture() {
 const env=envSetup(),time=1000;
 env.sqlite.prepare("INSERT INTO users(id,email,password_salt,password_hash,role,status,email_verified,license_expires_at,created_at,updated_at) VALUES('owner','owner@gmail.com','salt','hash','user','approved',1,9999,1,1)").run();
 env.sqlite.prepare("INSERT INTO license_policy(user_id,max_shops) VALUES('owner',10)").run();
 env.sqlite.prepare("INSERT INTO license_devices(id,user_id,public_key,key_hash,name,created_at,last_seen_at) VALUES('device','owner','public','hash','Fixture',1,1)").run();
 for(let i=0;i<8;i++) {
  const expires=i<7?time:time+1;
  env.sqlite.prepare('INSERT INTO sessions VALUES(?,?,?,?)').run('web-'+i,'owner',expires,1);
  env.sqlite.prepare('INSERT INTO action_tokens VALUES(?,?,?,?,?)').run('action-'+i,'owner','verify',expires,1);
  env.sqlite.prepare('INSERT INTO rate_limits VALUES(?,?,?)').run('rate-'+i,1,expires);
  env.sqlite.prepare('INSERT INTO desktop_registrations(id,email,otp_hash,token_hash,user_id,expires_at,created_at) VALUES(?,?,?,?,?,?,?)').run('reg-'+i,'fixture@gmail.com','otp-'+i,'token-'+i,'owner',expires,1);
  env.sqlite.prepare('INSERT INTO desktop_sessions VALUES(?,?,?)').run('desktop-'+i,'device',expires);
  env.sqlite.prepare('INSERT INTO desktop_challenges(id,credential_hash,key_hash,action,message,expires_at) VALUES(?,?,?,?,?,?)').run('challenge-'+i,'credential','key','renew','fixture',expires);
 }
 env.sqlite.prepare("INSERT INTO audit_log VALUES('audit','owner','owner','fixture','',1)").run();
 return {env,time};
}

test('expiry cleanup is bounded, repeatable and preserves active credentials and durable customer state',async()=>{
 const {env,time}=fixture();
 const first=await cleanupExpired(env,{at:time,limit:3});assert.equal(first.deleted,18);assert.equal(first.tables,6);
 for(const table of ['sessions','action_tokens','rate_limits','desktop_registrations','desktop_sessions','desktop_challenges']) {
  assert.equal(env.sqlite.prepare(`SELECT COUNT(*) n FROM ${table}`).get().n,5);
  assert.equal(env.sqlite.prepare(`SELECT COUNT(*) n FROM ${table} WHERE expires_at>?`).get(time).n,1);
 }
 assert.equal((await cleanupExpired(env,{at:time,limit:3})).deleted,18);
 assert.equal((await cleanupExpired(env,{at:time,limit:3})).deleted,6);
 assert.equal((await cleanupExpired(env,{at:time,limit:3})).deleted,0);
 assert.equal(env.sqlite.prepare('SELECT license_expires_at FROM users').get().license_expires_at,9999);
 assert.equal(env.sqlite.prepare('SELECT max_shops FROM license_policy').get().max_shops,10);
 assert.equal(env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices').get().n,1);
 assert.equal(env.sqlite.prepare('SELECT COUNT(*) n FROM audit_log').get().n,1);
 assert.deepEqual(env.sqlite.prepare('PRAGMA foreign_key_check').all(),[]);
 assert.equal(env.sqlite.prepare('PRAGMA integrity_check').get().integrity_check,'ok');env.sqlite.close();
});

test('scaling migration can run twice over existing records and expiry queries use indexes',()=>{
 const {env}=fixture(),migration=readFileSync(new URL('../migrations/0003_multi_customer_scaling.sql',import.meta.url),'utf8');
 env.sqlite.exec(migration);env.sqlite.exec(migration);
 assert.equal(env.sqlite.prepare('SELECT COUNT(*) n FROM sessions').get().n,8);
 assert.equal(env.sqlite.prepare('SELECT password_hash FROM users').get().password_hash,'hash');
 for(const [table,index] of [['sessions','idx_sessions_expiry'],['action_tokens','idx_action_tokens_expiry'],['rate_limits','idx_rate_limits_expiry'],['desktop_sessions','idx_desktop_sessions_expiry'],['desktop_challenges','idx_desktop_challenges_expiry']]) {
  const query=env.sqlite.prepare(`EXPLAIN QUERY PLAN SELECT expires_at FROM ${table} WHERE expires_at<=? ORDER BY expires_at LIMIT 500`).all(1000);
  assert.ok(query.some(row=>row.detail.includes(index)),JSON.stringify(query));
 }
 assert.deepEqual(env.sqlite.prepare('PRAGMA foreign_key_check').all(),[]);env.sqlite.close();
});

test('scheduled maintenance registers its bounded work and rejects invalid cleanup limits',async()=>{
 const {env}=fixture();let work;
 await worker.scheduled({},env,{waitUntil:promise=>{work=promise;}});assert.ok(work instanceof Promise);await work;
 assert.equal(env.sqlite.prepare('SELECT COUNT(*) n FROM desktop_challenges').get().n,0);
 for(const limit of [0,-1,1001,1.5])await assert.rejects(cleanupExpired(env,{limit}),{message:'INVALID_MAINTENANCE_BOUNDS'});
 env.sqlite.close();
});
