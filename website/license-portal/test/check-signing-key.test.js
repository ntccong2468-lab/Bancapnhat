import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync} from 'node:crypto';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import fs from 'node:fs';
import {syncBuiltinESMExports} from 'node:module';
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

test('the size limit cannot be bypassed by a changed path between stat and read',()=>{
 const directory=mkdtempSync(join(tmpdir(),'vncode-rotation-race-'));
 const originalStat=fs.statSync,originalRead=fs.readFileSync;
 const file=join(directory,'oversized.txt');
 let unboundedPathRead=false;
 try {
  writeFileSync(file,Buffer.alloc(1024*1024,65));
  // Simulate a different small file being checked immediately before a path swap.
  fs.statSync=function(path,...args){
   const result=originalStat.call(this,path,...args);
   return path===file ? Object.assign(Object.create(Object.getPrototypeOf(result)),result,{size:1}) : result;
  };
  fs.readFileSync=function(path,...args){
   if(path===file)unboundedPathRead=true;
   return originalRead.call(this,path,...args);
  };
  syncBuiltinESMExports();
  assert.throws(()=>checkSigningKey(file),{message:'KEY_FILE_INVALID_OR_MISMATCH'});
  assert.equal(unboundedPathRead,false,'must not read an unbounded file by path after a separate stat');
 }finally{
  fs.statSync=originalStat;fs.readFileSync=originalRead;syncBuiltinESMExports();
  rmSync(directory,{recursive:true,force:true});
 }
});

test('reading remains bounded if the opened file grows after its size check',()=>{
 const directory=mkdtempSync(join(tmpdir(),'vncode-rotation-growth-'));
 const originalFstat=fs.fstatSync,originalRead=fs.readSync;
 const file=join(directory,'growing.txt');let totalRead=0;
 try {
  writeFileSync(file,Buffer.alloc(1024*1024,65));
  fs.fstatSync=function(...args){
   const result=originalFstat.apply(this,args);
   return Object.assign(Object.create(Object.getPrototypeOf(result)),result,{size:1});
  };
  fs.readSync=function(...args){const count=originalRead.apply(this,args);totalRead+=count;return count;};
  syncBuiltinESMExports();
  assert.throws(()=>checkSigningKey(file),{message:'KEY_FILE_INVALID_OR_MISMATCH'});
  assert.equal(totalRead,8193,'must read at most the limit plus one byte');
 }finally{
  fs.fstatSync=originalFstat;fs.readSync=originalRead;syncBuiltinESMExports();
  rmSync(directory,{recursive:true,force:true});
 }
});
