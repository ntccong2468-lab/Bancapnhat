import {test} from 'node:test';
import assert from 'node:assert/strict';
import {licenseDiagnostics,DESKTOP_PIN} from '../src/license-diagnostics.js';
import {envSetup,req} from './fixtures.js';
async function fixture() {
 const pair=await crypto.subtle.generateKey('Ed25519',true,['sign','verify']);
 const privateKey=Buffer.from(await crypto.subtle.exportKey('pkcs8',pair.privateKey)).toString('base64url');
 const publicKey=Buffer.from(await crypto.subtle.exportKey('raw',pair.publicKey)).toString('base64url');
 return {env:envSetup({LICENSE_KEY_ID:'vncode-1',LICENSE_SIGNING_KEY:privateKey}),pin:{keyId:'vncode-1',publicKey},privateKey};
}
test('ready means the actual signing operation verifies against the client pin, not just a present secret',async()=>{
 const {env,pin,privateKey}=await fixture();const d=await licenseDiagnostics(env,pin);
 assert.equal(d.signing_key_valid,true);assert.equal(d.signature_matches_client,true);assert.equal(d.database_ready,true);assert.equal(d.activation_configuration_ready,true);
 assert.equal(d.production_activation_verified,false);assert.ok(!JSON.stringify(d).includes(privateKey));
 const wrong=await licenseDiagnostics(env,DESKTOP_PIN);assert.equal(wrong.signing_key_valid,true);assert.equal(wrong.signature_matches_client,false);assert.equal(wrong.activation_configuration_ready,false);
 env.LICENSE_KEY_ID='another-id';assert.equal((await licenseDiagnostics(env,pin)).key_id_matches,false);
 env.sqlite.close();
});
test('a malformed secret and missing activation schema are actionable without returning private error text',async()=>{
 const {env,pin}=await fixture();env.LICENSE_SIGNING_KEY='not-a-private-key';
 let d=await licenseDiagnostics(env,pin);assert.equal(d.signing_key_present,true);assert.equal(d.signing_key_valid,false);assert.equal(d.activation_configuration_ready,false);
 env.sqlite.exec('DROP TABLE desktop_challenges');d=await licenseDiagnostics(env,pin);
 assert.equal(d.database_ready,false);assert.ok(d.schema.some(row=>row.table==='desktop_challenges'&&!row.ready));
 env.sqlite.close();
});
test('diagnostics endpoint is admin-only and checks do not allocate licenses or devices',async()=>{
 const {env}=await fixture();assert.equal((await req(env,'/api/admin/desktop/diagnostics')).status,403);
 const credentials={email:'owner@gmail.com',password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'};
 await req(env,'/api/register','POST',credentials);env.sqlite.prepare("UPDATE users SET role='admin',status='approved'").run();
 const login=await req(env,'/api/login','POST',credentials);const d=await req(env,'/api/admin/desktop/diagnostics','GET',null,login.cookie.split(';')[0]);
 assert.equal(d.status,200);assert.equal(d.data.signing_key_valid,true);assert.equal(d.data.signature_matches_client,false);
 for(const table of ['license_devices','license_codes','desktop_registrations'])assert.equal(env.sqlite.prepare(`SELECT COUNT(*) n FROM ${table}`).get().n,0);
 env.LICENSE_SIGNING_KEY='invalid-secret';
 const settings=await req(env,'/api/admin/desktop/settings','GET',null,login.cookie.split(';')[0]);
 assert.equal(settings.data.signing_key_present,true);assert.equal(settings.data.signing_ready,false);
 env.sqlite.close();
});
