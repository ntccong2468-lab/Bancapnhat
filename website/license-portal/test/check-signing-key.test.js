import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync} from 'node:crypto';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {checkSigningKey} from '../tools/check-signing-key.mjs';

test('local preflight verifies the actual pair and rejects unrelated or malformed files without printing secrets',()=>{
 const directory=mkdtempSync(join(tmpdir(),'vncode-rotation-preflight-'));
 try {
  const keys=generateKeyPairSync('ed25519'),other=generateKeyPairSync('ed25519');
  const material=keys.privateKey.export({format:'der',type:'pkcs8'}).toString('base64url');
  const pin={keyId:'fixture-rotation',publicKey:keys.publicKey.export({format:'der',type:'spki'}).subarray(-32).toString('base64url')};
  const file=join(directory,'private.txt');writeFileSync(file,'\ufeff '+material+'\r\n',{mode:0o600});
  assert.deepEqual(checkSigningKey(file,pin),pin);
  writeFileSync(file,other.privateKey.export({format:'der',type:'pkcs8'}).toString('base64url'));
  assert.throws(()=>checkSigningKey(file,pin),{message:'KEY_FILE_INVALID_OR_MISMATCH'});
  writeFileSync(file,material);
  const cli=spawnSync(process.execPath,[fileURLToPath(new URL('../tools/check-signing-key.mjs',import.meta.url)),file],{encoding:'utf8'});
  assert.equal(cli.status,1,'A fixture key must not match the shipped owner pin');
  assert.ok(!(cli.stdout+cli.stderr).includes(material));
  writeFileSync(file,'not-a-key');assert.throws(()=>checkSigningKey(file,pin));
 }finally{rmSync(directory,{recursive:true,force:true});}
});
