import {signingKey} from './signing-key.js';
import {mailReady} from './mail.js';
// Public desktop trust pin. A secret's presence alone does not prove this pair matches.
export const DESKTOP_PIN=Object.freeze({keyId:'vncode-2',publicKey:'SPtRxTUJ78fqt5YMJ4DJ_PZwB2cMYegs-J7nY62ADMQ'});
export async function licenseDiagnostics(env,pin=DESKTOP_PIN) {
 const checks={
  users:'id,email,role,status,license_expires_at',
  desktop_registrations:'id,token_hash,verified,user_id,expires_at',
  license_policy:'user_id,max_devices,max_shops,offline_hours,revision',
  license_codes:'id,code_hash,assigned_user_id,state,redeemed_by,days',
  license_redemptions:'id,code_id,user_id',
  license_devices:'id,user_id,public_key,key_hash,revoked,last_lease_expires_at',
  desktop_sessions:'token_hash,device_id,expires_at',
  desktop_challenges:'id,credential_hash,key_hash,action,message,expires_at,used,claim',
  rate_limits:'key,count,expires_at',audit_log:'id,actor_id,target_id,action,detail,created_at'
 };
 const schema=await Promise.all(Object.entries(checks).map(async([table,columns])=>{
  try{await env.DB.prepare(`SELECT ${columns} FROM ${table} LIMIT 0`).all();return {table,ready:true};}
  catch{return {table,ready:false};}
 }));
 let signing_key_valid=false,signature_matches_client=false;
 try {
  const authority=await signingKey(env);signing_key_valid=true;
  const challenge=new TextEncoder().encode('vncode-license-config-check-v1\n'+pin.keyId);
  const signature=await crypto.subtle.sign('Ed25519',authority.key,challenge);
  const raw=Uint8Array.from(atob(pin.publicKey.replaceAll('-','+').replaceAll('_','/')+'='),c=>c.charCodeAt(0));
  const publicKey=await crypto.subtle.importKey('raw',raw,{name:'Ed25519'},false,['verify']);
  signature_matches_client=await crypto.subtle.verify('Ed25519',publicKey,signature,challenge);
 }catch{/* Only readiness flags are public to the authenticated admin; no secret/error body. */}
 const database_ready=schema.every(check=>check.ready),key_id_matches=env.LICENSE_KEY_ID===pin.keyId;
 return {portal_version:'0.3.6',registration_mode:'gmail_admin',mail_ready:mailReady(env),
  client_key_id:pin.keyId,client_public_key:pin.publicKey,minimum_client_version:'1.4.4',key_id:env.LICENSE_KEY_ID||null,
  signing_key_present:!!env.LICENSE_SIGNING_KEY,signing_key_valid,signature_matches_client,key_id_matches,
  database_ready,schema,activation_configuration_ready:database_ready&&signing_key_valid&&signature_matches_client&&key_id_matches,
  production_activation_verified:false};
}
