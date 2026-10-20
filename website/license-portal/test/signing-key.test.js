import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createSigningKeyProvider} from '../src/signing-key.js';

const material=n=>Buffer.alloc(48,n).toString('base64url');

test('actual Ed25519 PKCS8 imports from clipboard whitespace, Base64, quoted text and PEM without rotating the key',async()=>{
 const pair=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const der=Buffer.from(await crypto.subtle.exportKey('pkcs8',pair.privateKey));
 const b64=der.toString('base64'),url=der.toString('base64url');
 const forms=[url,'\ufeff  '+url+'\r\n',b64,JSON.stringify(url),'-----BEGIN PRIVATE KEY-----\n'+b64+'\n-----END PRIVATE KEY-----'];
 const get=createSigningKeyProvider(),message=new TextEncoder().encode('configuration compatibility check');
 for(const form of forms) {
  const imported=await get({LICENSE_KEY_ID:'vncode-1',LICENSE_SIGNING_KEY:form});
  const signature=await crypto.subtle.sign('Ed25519',imported.key,message);
  assert.equal(await crypto.subtle.verify('Ed25519',pair.publicKey,signature,message),true);
 }
 for(const invalid of ['{"d":"private"}','-----BEGIN PUBLIC KEY-----\n'+b64+'\n-----END PUBLIC KEY-----','a=garbage'])
  await assert.rejects(get({LICENSE_KEY_ID:'vncode-1',LICENSE_SIGNING_KEY:invalid}),{message:'SIGNING_NOT_CONFIGURED'});
});

test('1000 concurrent requests share one signing import and do not expose its material',async()=>{
 let imports=0,finish;
 const imported={fixture:'key-a'},get=createSigningKeyProvider(async raw=>{
  imports++;assert.equal(raw[0],1);await new Promise(resolve=>{finish=resolve;});return imported;
 });
 const env={LICENSE_KEY_ID:'a',LICENSE_SIGNING_KEY:material(1)};
 const pending=Array.from({length:1000},()=>get(env));
 await Promise.resolve();assert.equal(imports,1);finish();
 const keys=await Promise.all(pending);
 assert.ok(keys.every(value=>value.key===imported&&value.keyId==='a'));
 assert.equal(imports,1);assert.ok(!JSON.stringify(keys[0]).includes(env.LICENSE_SIGNING_KEY));
});

test('rotation captures the matching key ID and secret; a late old import cannot replace the new cache',async()=>{
 let imports=0,finishOld;
 const get=createSigningKeyProvider(async raw=>{
  imports++;
  if(raw[0]===1)await new Promise(resolve=>{finishOld=resolve;});
  return {material:raw[0]};
 });
 const env={LICENSE_KEY_ID:'old',LICENSE_SIGNING_KEY:material(1)},old=get(env);
 await Promise.resolve();
 env.LICENSE_KEY_ID='new';env.LICENSE_SIGNING_KEY=material(2);
 const fresh=await get(env);assert.equal(fresh.key.material,2);assert.equal(fresh.keyId,'new');
 finishOld();const prior=await old;assert.equal(prior.key.material,1);assert.equal(prior.keyId,'old');
 assert.strictEqual((await get(env)).key,fresh.key);assert.equal(imports,2);
 // Secret rotation under the same ID must still import the new material.
 env.LICENSE_SIGNING_KEY=material(3);assert.equal((await get(env)).key.material,3);assert.equal(imports,3);
 env.LICENSE_KEY_ID='new-id';assert.equal((await get(env)).keyId,'new-id');assert.equal(imports,4);
});

test('missing configuration never reuses a cached key and failed imports can recover',async()=>{
 let imports=0;
 const get=createSigningKeyProvider(async()=>{if(++imports===1)throw new Error('private diagnostic');return {fixture:'key'};});
 const env={LICENSE_KEY_ID:'valid',LICENSE_SIGNING_KEY:material(1)};
 await assert.rejects(get(env),{message:'SIGNING_NOT_CONFIGURED'});
 assert.equal((await get(env)).key.fixture,'key');assert.equal(imports,2);
 await assert.rejects(get({LICENSE_KEY_ID:'valid'}),{message:'SIGNING_NOT_CONFIGURED'});
 await assert.rejects(get({LICENSE_KEY_ID:'bad key',LICENSE_SIGNING_KEY:material(1)}),{message:'SIGNING_NOT_CONFIGURED'});
 await assert.rejects(get({LICENSE_KEY_ID:'valid',LICENSE_SIGNING_KEY:'a'.repeat(8193)}),{message:'SIGNING_NOT_CONFIGURED'});
 assert.equal(imports,2);
});
