import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash,randomBytes} from 'node:crypto';
import {envSetup,req} from './fixtures.js';

const encode=bytes=>Buffer.from(bytes).toString('base64url');
const hash=value=>createHash('sha256').update(value).digest('hex');

async function tenants(count) {
 const signing=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const env=envSetup({LICENSE_KEY_ID:'load-fixture',LICENSE_SIGNING_KEY:encode(await crypto.subtle.exportKey('pkcs8',signing.privateKey))});
 const keys=await Promise.all(Array.from({length:count},()=>crypto.subtle.generateKey('Ed25519',true,['sign','verify'])));
 const customers=[];
 env.sqlite.exec('BEGIN');
 for(let i=0;i<count;i++) {
  const id=crypto.randomUUID(),device=crypto.randomUUID(),token=encode(randomBytes(32)),publicKey=encode(await crypto.subtle.exportKey('raw',keys[i].publicKey)),time=Math.floor(Date.now()/1000);
  env.sqlite.prepare("INSERT INTO users(id,email,password_salt,password_hash,role,status,email_verified,license_expires_at,created_at,updated_at) VALUES(?,?,?,?,'user','approved',1,?,?,?)").run(id,`customer-${i}@gmail.com`,'fixture-salt','fixture-hash',time+86400,time,time);
  env.sqlite.prepare('INSERT INTO license_policy(user_id,max_devices,max_shops,offline_hours,revision) VALUES(?,?,?,?,?)').run(id,1,10,72,i+1);
  env.sqlite.prepare('INSERT INTO license_devices(id,user_id,public_key,key_hash,name,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?)').run(device,id,publicKey,hash(publicKey),'Fixture Windows',time,time);
  env.sqlite.prepare('INSERT INTO desktop_sessions VALUES(?,?,?)').run(hash(token),device,time+86400);
  customers.push({id,device,publicKey,keys:keys[i],auth:{authorization:'Bearer '+token},email:`customer-${i}@gmail.com`,revision:i+1});
 }
 env.sqlite.exec('COMMIT');
 return {env,customers,signing};
}

async function proof(env,customer) {
 const challenge=await req(env,'/api/client/license/challenge','POST',{action:'renew',public_key:customer.publicKey},'',customer.auth);
 assert.equal(challenge.status,200,JSON.stringify(challenge.data));
 return {challenge_id:challenge.data.challenge_id,public_key:customer.publicKey,
  signature:encode(await crypto.subtle.sign('Ed25519',customer.keys.privateKey,new TextEncoder().encode(challenge.data.message)))};
}

for(const count of [100,1000])test(`${count} concurrent independent customers receive only their own signed ten-shop lease`,async()=>{
 const {env,customers,signing}=await tenants(count);
 try {
  const proofs=await Promise.all(customers.map(customer=>proof(env,customer)));
  const results=await Promise.all(customers.map((customer,index)=>req(env,'/api/client/license/renew','POST',proofs[index],'',customer.auth)));
  assert.equal(results.filter(result=>result.status===200).length,count,JSON.stringify(results.find(result=>result.status!==200)));
  await Promise.all(results.map(async(result,index)=>{
   const customer=customers[index],lease=result.data.lease,payload=JSON.parse(Buffer.from(lease.payload,'base64url'));
   assert.equal(result.data.user.email,customer.email);assert.equal(payload.accountId,customer.id);assert.equal(payload.deviceId,customer.device);
   assert.equal(payload.devicePublicKeyHash,hash(customer.publicKey));assert.equal(payload.licenseRevision,customer.revision);
   assert.equal(payload.maxShops,10);assert.equal(payload.maxDevices,1);assert.equal(lease.key_id,'load-fixture');
   assert.equal(await crypto.subtle.verify('Ed25519',signing.publicKey,Buffer.from(lease.signature,'base64url'),Buffer.from(lease.payload,'base64url')),true);
  }));
  assert.equal(env.sqlite.prepare('SELECT COUNT(*) n FROM desktop_challenges WHERE used=1').get().n,count);
  assert.equal(env.sqlite.prepare('SELECT COUNT(*) n FROM license_devices WHERE last_lease_expires_at IS NOT NULL').get().n,count);
  assert.deepEqual(env.sqlite.prepare('PRAGMA foreign_key_check').all(),[]);
  assert.equal(env.sqlite.prepare('PRAGMA integrity_check').get().integrity_check,'ok');
 }finally {env.sqlite.close();}
});

test('concurrent customers cannot exchange proofs or renew after revocation and expiry',async()=>{
 const {env,customers}=await tenants(100);
 try {
  const proofs=await Promise.all(customers.map(customer=>proof(env,customer)));
  env.sqlite.prepare('UPDATE license_devices SET revoked=1 WHERE id=?').run(customers[1].device);
  env.sqlite.prepare('UPDATE users SET license_expires_at=1 WHERE id=?').run(customers[2].id);
  const requests=customers.map((customer,index)=>req(env,'/api/client/license/renew','POST',proofs[index],'',customer.auth));
  requests.push(req(env,'/api/client/license/renew','POST',proofs[3],'',customers[4].auth));
  const results=await Promise.all(requests);
  assert.equal(results[0].status,200);assert.equal(results[1].status,403);assert.equal(results[2].status,403);
  assert.equal(results[100].status,409);
  assert.equal(results.slice(3,100).filter(result=>result.status===200).length,97);
  assert.equal(env.sqlite.prepare('SELECT last_lease_expires_at FROM license_devices WHERE id=?').get(customers[1].device).last_lease_expires_at,null);
  assert.equal(env.sqlite.prepare('SELECT last_lease_expires_at FROM license_devices WHERE id=?').get(customers[2].device).last_lease_expires_at,null);
  assert.equal((await req(env,'/api/client/license/challenge','POST',{action:'renew',public_key:customers[0].publicKey},'',customers[4].auth)).status,403);
 }finally {env.sqlite.close();}
});

test('a revoke or policy change during signing prevents the grant from being returned',async()=>{
 for(const change of ['revocation','policy']) {
  const {env,customers}=await tenants(1),customer=customers[0];
  try {
   const body=await proof(env,customer),prepare=env.DB.prepare.bind(env.DB);let authorityQueries=0;
   env.DB.prepare=sql=>{
    const statement=prepare(sql);
    if(sql.includes('SELECT u.id account_id'))authorityQueries++;
    if(sql.startsWith('UPDATE license_devices SET last_seen_at=')) {
     const run=statement.run.bind(statement);
     statement.run=()=>{
      if(change==='revocation')env.sqlite.prepare('UPDATE license_devices SET revoked=1 WHERE id=?').run(customer.device);
      else env.sqlite.prepare('UPDATE license_policy SET revision=revision+1 WHERE user_id=?').run(customer.id);
      return run();
     };
    }
    return statement;
   };
   const result=await req(env,'/api/client/license/renew','POST',body,'',customer.auth);
   assert.equal(result.status,403);assert.equal(result.data.error,'LICENSE_CHANGED');assert.equal(result.data.lease,undefined);
   assert.equal(authorityQueries,1,'Current user, policy and device authority use one joined read');
   assert.equal(env.sqlite.prepare('SELECT last_lease_expires_at FROM license_devices WHERE id=?').get(customer.device).last_lease_expires_at,null);
  }finally {env.sqlite.close();}
 }
});

test('signing-key rotation signs the captured key ID with the corresponding new private key',async()=>{
 const {env,customers,signing}=await tenants(1),customer=customers[0];
 try {
  const first=await req(env,'/api/client/license/renew','POST',await proof(env,customer),'',customer.auth);
  assert.equal(first.status,200);assert.equal(first.data.lease.key_id,'load-fixture');
  const replacement=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
  env.LICENSE_SIGNING_KEY=encode(await crypto.subtle.exportKey('pkcs8',replacement.privateKey));env.LICENSE_KEY_ID='rotated-fixture';
  const second=await req(env,'/api/client/license/renew','POST',await proof(env,customer),'',customer.auth);
  assert.equal(second.status,200);assert.equal(second.data.lease.key_id,'rotated-fixture');
  const lease=second.data.lease;
  assert.equal(await crypto.subtle.verify('Ed25519',replacement.publicKey,Buffer.from(lease.signature,'base64url'),Buffer.from(lease.payload,'base64url')),true);
  assert.equal(await crypto.subtle.verify('Ed25519',signing.publicKey,Buffer.from(lease.signature,'base64url'),Buffer.from(lease.payload,'base64url')),false);
 }finally {env.sqlite.close();}
});
