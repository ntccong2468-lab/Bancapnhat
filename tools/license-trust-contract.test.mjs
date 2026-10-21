import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createPublicKey} from 'node:crypto';
import {DESKTOP_PIN} from '../website/license-portal/src/license-diagnostics.js';

test('Worker deployment ID, diagnostic pin and packaged desktop trust agree after owner key rotation',()=>{
 const keys=JSON.parse(readFileSync(new URL('../src/main/resources/com/vncode/app/license/trusted-keys.json',import.meta.url),'utf8'));
 const configuration=JSON.parse(readFileSync(new URL('../website/license-portal/wrangler.jsonc',import.meta.url),'utf8'));
 assert.equal(DESKTOP_PIN.keyId,'vncode-2');assert.equal(configuration.vars.LICENSE_KEY_ID,DESKTOP_PIN.keyId);
 assert.equal(keys[DESKTOP_PIN.keyId],DESKTOP_PIN.publicKey);
 assert.equal(DESKTOP_PIN.publicKey,'SPtRxTUJ78fqt5YMJ4DJ_PZwB2cMYegs-J7nY62ADMQ');
 assert.equal(keys['vncode-1'],'Gg7F92gqXnCLGdMbI130qHcBJ2hiI6KseZBgWkNCTeI');
 for(const raw of Object.values(keys)) {
  const bytes=Buffer.from(raw,'base64url');assert.equal(bytes.length,32);assert.equal(bytes.toString('base64url'),raw);
  assert.equal(createPublicKey({key:Buffer.concat([Buffer.from('302a300506032b6570032100','hex'),bytes]),type:'spki',format:'der'}).asymmetricKeyType,'ed25519');
 }
});
