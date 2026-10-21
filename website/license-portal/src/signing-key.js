// One in-flight import per exact signing configuration in a Worker isolate.
// The response key ID is captured together with the key, including during rotation.
function bytes(material) {
 if(typeof material!=='string'||material.length>8192)throw new Error('SIGNING_NOT_CONFIGURED');
 try {
  let value=material.trim();
  if(value.startsWith('"')){value=JSON.parse(value);if(typeof value!=='string')throw new Error();value=value.trim();}
  if(value.startsWith('-----BEGIN PRIVATE KEY-----')) {
   const match=value.match(/^-----BEGIN PRIVATE KEY-----\s*([A-Za-z0-9+/=\s]+)\s*-----END PRIVATE KEY-----$/);
   if(!match)throw new Error();value=match[1];
  }
  value=value.replace(/\s/g,'');
  if(!/^[A-Za-z0-9+/_-]+={0,2}$/.test(value))throw new Error();
  value=value.replace(/=+$/,'').replaceAll('-','+').replaceAll('_','/');
  const raw=Uint8Array.from(atob(value+'='.repeat((4-value.length%4)%4)),x=>x.charCodeAt(0));
  if(raw.length<32||raw.length>4096)throw new Error();
  return raw;
 }catch {throw new Error('SIGNING_NOT_CONFIGURED');}
}

export function createSigningKeyProvider(importKey=raw=>crypto.subtle.importKey('pkcs8',raw,{name:'Ed25519'},false,['sign'])) {
 let current=null;
 return async env=>{
  const material=env.LICENSE_SIGNING_KEY,keyId=env.LICENSE_KEY_ID;
  if(typeof material!=='string'||!material||typeof keyId!=='string'||! /^[A-Za-z0-9_-]{1,64}$/.test(keyId))throw new Error('SIGNING_NOT_CONFIGURED');
  let entry=current;
  if(!entry||entry.material!==material||entry.keyId!==keyId) {
   entry={material,keyId,pending:null};
   // Set the entry before awaiting; concurrent requests share this promise.
   entry.pending=Promise.resolve().then(()=>importKey(bytes(material)));
   current=entry;
  }
  try {return Object.freeze({key:await entry.pending,keyId});}
  catch {
   // A failed older import must not invalidate a newer configuration.
   if(current===entry)current=null;
   throw new Error('SIGNING_NOT_CONFIGURED');
  }
 };
}

export const signingKey=createSigningKeyProvider();
