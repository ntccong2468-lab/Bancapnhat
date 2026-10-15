import {test} from 'node:test';
import assert from 'node:assert/strict';
import gateway from '../src/license-gateway.js';
import worker from '../src/index.js';
import {envSetup} from './fixtures.js';

test('gateway preserves the backup origin and device proof, strips browser cookies, blocks admin and redirects',async()=>{
 let forwarded;
 const env={BACKEND:{fetch:async request=>{forwarded=request;return new Response('ok');}}};
 const response=await gateway.fetch(new Request('https://license.vncode.id.vn/api/client/license/activate',{
  method:'POST',headers:{authorization:'Bearer fixture',cookie:'session=fixture','x-forwarded-for':'spoof','content-type':'application/json'},body:'{"challenge_id":"fixture"}'
 }),env);
 assert.equal(response.status,200);assert.equal(forwarded.url,'https://license.vncode.id.vn/api/client/license/activate');
 assert.equal(forwarded.headers.get('authorization'),'Bearer fixture');assert.equal(forwarded.headers.get('cookie'),null);
 assert.equal(forwarded.headers.get('x-forwarded-for'),null);assert.equal(await forwarded.text(),'{"challenge_id":"fixture"}');
 for(const path of ['/api/admin/desktop/settings','/admin','/api/client/license/activate?url=https://evil.test'])
  assert.equal((await gateway.fetch(new Request('https://license.vncode.id.vn'+path),env)).status,404);
 assert.equal((await gateway.fetch(new Request('https://license.vncode.id.vn/api/config',{method:'DELETE'}),env)).status,404);
});

test('backup service binding returns nonce and origin bound proof from existing issuer without accounts or devices',async()=>{
 const keys=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const env=envSetup({LICENSE_KEY_ID:'fixture',LICENSE_SIGNING_KEY:Buffer.from(await crypto.subtle.exportKey('pkcs8',keys.privateKey)).toString('base64url')});
 try {
  const nonce=Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url');
  const response=await gateway.fetch(new Request('https://license.vncode.id.vn/api/client/license/endpoint-proof',{
   method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({nonce,origin:'https://evil.test/'})
  }),{BACKEND:{fetch:request=>worker.fetch(request,env)}});
  assert.equal(response.status,200);const proof=await response.json();assert.equal(proof.key_id,'fixture');
  const bytes=Buffer.from(proof.payload,'base64url'),payload=JSON.parse(bytes);
  assert.equal(await crypto.subtle.verify('Ed25519',keys.publicKey,Buffer.from(proof.signature,'base64url'),bytes),true);
  assert.equal(payload.nonce,nonce);assert.equal(payload.origin,'https://license.vncode.id.vn/');
  assert.equal(payload.purpose,'license-endpoint');assert.equal(payload.expiresAt-payload.issuedAt,120);
  for(const table of ['users','license_devices','license_redemptions','desktop_registrations','desktop_challenges'])
   assert.equal(env.sqlite.prepare(`SELECT COUNT(*) n FROM ${table}`).get().n,0);
  const invalid=await worker.fetch(new Request('https://license.vncode.id.vn/api/client/license/endpoint-proof',{method:'POST',headers:{'content-type':'application/json'},body:'{"nonce":"bad"}'}),env);
  assert.equal(invalid.status,400);
 }finally {env.sqlite.close();}
});
