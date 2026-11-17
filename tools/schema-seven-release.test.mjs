import test from 'node:test';
import assert from 'node:assert/strict';
import {validateNativeSmoke} from './publish-vncode-windows.mjs';
import {databaseSchemaForVersion} from './baseline-release.mjs';
import {spawnSync} from 'node:child_process';
import {mkdtempSync,readFileSync,rmSync,mkdirSync,readdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const proof=(version,migration)=>({appName:'VN code',windowTitle:`VN code v${version}`,
  version,platform:'windows-x64',nativeLauncher:'passed',schemaMigration:migration,
  historyPreserved:true,rollbackSnapshotVerified:true,liveMarketplaceMutations:false});

test('schema-seven release accepts its actual native migration proof',()=>{
  assert.doesNotThrow(()=>validateNativeSmoke(proof('1.6.11','3 to 7'),'1.6.11'));
});
test('schema-seven release refuses a stale schema-six migration claim',()=>{
  assert.throws(()=>validateNativeSmoke(proof('1.6.11','3 to 6'),'1.6.11'));
});
test('historical schema-four five and six release evidence stays unchanged',()=>{
  for(const [version,migration] of [['1.1.34','3 to 4'],['1.4.0','3 to 5'],
      ['1.4.18','3 to 5'],['1.4.19','3 to 6'],['1.6.10','3 to 6']]){
    assert.doesNotThrow(()=>validateNativeSmoke(proof(version,migration),version));
    assert.throws(()=>validateNativeSmoke(proof(version,'3 to 7'),version));
  }
});
test('native target and previous baseline use the same exact version-to-schema boundary',()=>{
  for(const [version,schema] of [['1.1.34',4],['1.4.0',5],['1.4.18',5],['1.4.19',6],
      ['1.6.9',6],['1.6.10',6],['1.6.11',7],['1.6.12',7],['1.7.0',7],['2.0.0',7]]){
    assert.equal(databaseSchemaForVersion(version),schema);
  }
  for(const invalid of ['',undefined,null,'1.6.11-preview.1','v1.6.11','1.6','1.6.11;other']){
    assert.throws(()=>databaseSchemaForVersion(invalid));
  }
});
test('schema CLI works outside a Git repository and fails closed without writing outputs',()=>{
  const directory=mkdtempSync(path.join(tmpdir(),'vncode-schema-cli-'));
  try{
    const emptyPath=path.join(directory,'no-executables');mkdirSync(emptyPath);
    const file=fileURLToPath(new URL('./baseline-release.mjs',import.meta.url));
    const invoke=version=>spawnSync(process.execPath,[file,'schema',version],{
      cwd:directory,env:{PATH:emptyPath},encoding:'utf8'});
    for(const [version,expected] of [['1.6.10','6'],['1.6.11','7']]){
      const result=invoke(version);assert.equal(result.status,0,result.stderr);assert.equal(result.stdout.trim(),expected);
    }
    const invalid=invoke('1.6.11-not-a-release');assert.notEqual(invalid.status,0);assert.equal(invalid.stdout,'');
    assert.deepEqual(readdirSync(directory),['no-executables']);
  }finally{rmSync(directory,{recursive:true,force:true});}
});
test('native and baseline-upgrade probes obtain their real evidence labels from the shared resolver',()=>{
  const native=readFileSync(new URL('./windows-native-smoke.ps1',import.meta.url),'utf8');
  const upgrade=readFileSync(new URL('./windows-baseline-upgrade-smoke.ps1',import.meta.url),'utf8');
  assert.match(native,/node tools\/baseline-release\.mjs schema \$Version/);
  assert.match(native,/schemaMigration = "3 to \$targetSchema"/);
  assert.match(upgrade,/node tools\/baseline-release\.mjs schema \$fromVersion/);
  assert.match(upgrade,/WindowsDataProbe ready \$data/);
  assert.match(upgrade,/fromSchema=\[int\]\$baselineSchema;toSchema=\[int\]\$targetSchema;schemaMigration="\$baselineSchema to \$targetSchema"/);
});
