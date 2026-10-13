// One in-flight import per exact signing configuration in a Worker isolate.
// The response key ID is captured together with the key, including during rotation.
function bytes(material) {
 if(typeof material!=='string'||material.length>8192||! /^[A-Za-z0-9_-]+$/.test(material))throw new Error('SIGNING_NOT_CONFIGURED');
 try {
  const raw=Uint8Array.from(atob(material.replaceAll('-','+').replaceAll('_','/')+'='.repeat((4-material.length%4)%4)),x=>x.charCodeAt(0));
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
