// Runs only on the owner's machine. Never print or upload private-key material.
import {readFileSync,statSync} from 'node:fs';
import {createPrivateKey,createPublicKey,sign,verify} from 'node:crypto';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {DESKTOP_PIN} from '../src/license-diagnostics.js';

export function checkSigningKey(file,pin=DESKTOP_PIN) {
 try {
  if(!statSync(file).isFile()||statSync(file).size>8192)throw new Error();
  const material=readFileSync(file,'utf8').trim();
  if(!/^[A-Za-z0-9_-]+$/.test(material))throw new Error();
  const bytes=Buffer.from(material,'base64url');
  if(bytes.toString('base64url')!==material)throw new Error();
  const privateKey=createPrivateKey({key:bytes,format:'der',type:'pkcs8'});
  if(privateKey.asymmetricKeyType!=='ed25519')throw new Error();
  const publicKey=createPublicKey(privateKey);
  const raw=publicKey.export({format:'der',type:'spki'}).subarray(-32).toString('base64url');
  if(raw!==pin.publicKey)throw new Error();
  const message=Buffer.from('vncode-owner-rotation-check-v1\n'+pin.keyId);
  if(!verify(null,message,publicKey,sign(null,message,privateKey)))throw new Error();
  return {keyId:pin.keyId,publicKey:raw};
 }catch{throw new Error('KEY_FILE_INVALID_OR_MISMATCH');}
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 try {
  if(process.argv.length!==3)throw new Error('KEY_FILE_REQUIRED');
  const result=checkSigningKey(process.argv[2]);
  console.log(`Khóa riêng khớp public key ${result.keyId}. Có thể thực hiện hướng dẫn cập nhật Secret.`);
 }catch{
  console.error('Không xác minh được tệp khóa riêng với public key VNCode. Kiểm tra đúng LICENSE_SIGNING_KEY.txt trên máy; không gửi nội dung tệp qua chat.');
  process.exitCode=1;
 }
}
