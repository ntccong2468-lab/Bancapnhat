import test from 'node:test';
import assert from 'node:assert/strict';
import { validateBuild, validateNativeSmoke } from './publish-vncode-windows.mjs';
const sha='3b5c725ec8b481556cb6635859b08dcdb66ab053';
const run={status:'completed',conclusion:'success',head_sha:sha,workflow_id:7,repository:{full_name:'ntccong2468-lab/Vncode'},head_repository:{full_name:'ntccong2468-lab/Vncode'}};
test('publication requires the successful native build for the exact tag and repository',()=>{
  assert.doesNotThrow(()=>validateBuild(run,sha,7));
  for(const bad of [{...run,conclusion:'failure'},{...run,head_sha:'0'.repeat(40)},
    {...run,workflow_id:8},{...run,repository:{full_name:'other/repository'}}]) {
    assert.throws(()=>validateBuild(bad,sha,7));
  }
});
test('publication rejects incomplete migration or live mutation smoke evidence',()=>{
  const smoke={version:'1.1.33',platform:'windows-x64',nativeLauncher:'passed',schemaMigration:'3 to 4',historyPreserved:true,rollbackSnapshotVerified:true,liveMarketplaceMutations:false};
  assert.doesNotThrow(()=>validateNativeSmoke(smoke,'1.1.33'));
  for(const bad of [{...smoke,historyPreserved:false},{...smoke,liveMarketplaceMutations:true},
    {...smoke,version:'1.1.32'},{...smoke,rollbackSnapshotVerified:false}]) {
    assert.throws(()=>validateNativeSmoke(bad,'1.1.33'));
  }
});
test('publication rejects unexpected files and requires the complete asset set',async()=>{
  const {validateReleaseAssets}=await import('./publish-vncode-windows.mjs');
  const names=['Vncode.exe','checksums.sha256','build-info.json','native-smoke.json','release-notes.md'];
  const assets=names.map(name=>({name}));
  assert.doesNotThrow(()=>validateReleaseAssets(assets,names,true));
  assert.doesNotThrow(()=>validateReleaseAssets([],names,false));
  assert.throws(()=>validateReleaseAssets([...assets,{name:'update-manifest.json'}],names,false));
  assert.throws(()=>validateReleaseAssets(assets.slice(1),names,true));
});
