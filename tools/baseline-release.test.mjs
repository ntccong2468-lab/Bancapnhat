import test from 'node:test';
import assert from 'node:assert/strict';
import {requiredAssets, validateCompleteRelease, pruneOlderReleases} from './baseline-release.mjs';
const version='1.5.11', source='a'.repeat(40);
function fixture(){
 const files=Object.fromEntries(requiredAssets(version).map(name=>[name,{sha256:'b'.repeat(64),bytes:2048}]));
 const manifest={version,sourceCommit:source,files};
 const release={id:91,tag_name:`v${version}`,target_commitish:source,draft:false,prerelease:false,published_at:'2026-10-22T00:00:00Z',assets:Object.entries(files).map(([name,f])=>({name,size:f.bytes,state:'uploaded',digest:`sha256:${f.sha256}`}))};
 return {manifest,release};
}
test('complete installer, runtime, source, history and verification assets form a reusable baseline',()=>{
 const {manifest,release}=fixture();assert.equal(validateCompleteRelease(release,manifest,source),true);
 const names=requiredAssets(version);for(const part of ['source.zip','history.bundle','Windows-x64.zip','verification.zip'])assert.ok(names.some(name=>name.endsWith(part)));
});
test('an incomplete source/runtime/history baseline cannot authorize deletion',()=>{
 for(const missing of requiredAssets(version)){
  const {manifest,release}=fixture();release.assets=release.assets.filter(a=>a.name!==missing);
  assert.throws(()=>validateCompleteRelease(release,manifest,source));
 }
});
test('digest, byte size, tag source, draft and prerelease mismatches are rejected',()=>{
 for(const change of [r=>r.assets[0].digest='sha256:'+'c'.repeat(64),r=>r.assets[0].size++,r=>r.tag_name='v1.5.10',r=>r.draft=true,r=>r.prerelease=true,r=>r.assets.push({...r.assets[0]})]){
  const {manifest,release}=fixture();change(release);assert.throws(()=>validateCompleteRelease(release,manifest,source));
 }
 const {manifest,release}=fixture();assert.throws(()=>validateCompleteRelease(release,manifest,'d'.repeat(40)));
});
test('cleanup deletes only the older release records after verifying current Latest',async()=>{
 const {manifest,release}=fixture(),deleted=[];
 await pruneOlderReleases({release,manifest,sourceCommit:source,releases:[{id:2,tag_name:'v1.5.9',created_at:'2026-10-20T00:00:00Z'},{id:3,tag_name:'v1.5.10',created_at:'2026-10-21T00:00:00Z'},release],readLatest:async()=>release,deleteRelease:async id=>deleted.push(id)});
 assert.deepEqual(deleted,[2,3]);
});
test('cleanup fails before any delete when public release is incomplete or source changes',async()=>{
 const {manifest,release}=fixture(),deleted=[];release.assets.pop();
 await assert.rejects(pruneOlderReleases({release,manifest,sourceCommit:source,releases:[{id:2,tag_name:'v1.5.10'}],readLatest:async()=>release,deleteRelease:async id=>deleted.push(id)}));
 assert.deepEqual(deleted,[]);
});
test('concurrent Latest replacement stops cleanup before the next delete',async()=>{
 const {manifest,release}=fixture(),deleted=[];let reads=0;
 await assert.rejects(pruneOlderReleases({release,manifest,sourceCommit:source,releases:[{id:2,tag_name:'v1.5.9'},{id:3,tag_name:'v1.5.10'}],readLatest:async()=>++reads===1?release:{...release,id:92},deleteRelease:async id=>deleted.push(id)}));
 assert.deepEqual(deleted,[2]);
});
test('a newer or unrelated release cannot be swept into old-version cleanup',async()=>{
 for(const old of [{id:2,tag_name:'v1.5.12'},{id:2,tag_name:'other-product'},{id:2,tag_name:'v1.5.10',created_at:'2027-01-01T00:00:00Z'}]){
  const {manifest,release}=fixture(),deleted=[];
  await assert.rejects(pruneOlderReleases({release,manifest,sourceCommit:source,releases:[old],readLatest:async()=>release,deleteRelease:async id=>deleted.push(id)}));assert.deepEqual(deleted,[]);
 }
});
