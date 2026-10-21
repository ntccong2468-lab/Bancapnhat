import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync,readdirSync,mkdirSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {reportRows} from './reuse-verified-windows-java.mjs';
export const origin151=Object.freeze({run:39286749991,sha:'96e20614882488a2d60319fc29f4ca62b6cb9b81',workflow:379116841,artifact:12205273211,digest:'a069bfc06c520cc3f35c4ad4db2f2d96420496a2c959b3ba362c1a3de1384f30',jar:'15fb154ceb416f48d1366267db7e1953b59195a2c9529f68ae46e1266db51e67',tests:1924});
export const plan151=Object.freeze({kind:'byte-verified-java-origin',runId:origin151.run,sourceCommit:origin151.sha,artifactId:origin151.artifact,archiveSha256:origin151.digest});
const allowed=new Set(['.github/workflows/build-java.yml','tools/windows-upgrade-smoke.ps1','tools/reuse-verified-windows-java.mjs','tools/reuse-verified-windows-java-151.mjs','tools/reuse-verified-windows-java-151.test.mjs','tools/publish-vncode-stable.mjs','tools/publish-vncode-stable.test.mjs']);
export function validateDelta151(files,oldProbe,newProbe){
 assert.ok(files.every(f=>f.startsWith('docs/')||allowed.has(f)),'App, Java tests, dependencies or packaging changed: full Java execution is required');
 oldProbe=oldProbe.replaceAll('\r\n','\n');newProbe=newProbe.replaceAll('\r\n','\n');
 const command='Invoke-WebRequest -Uri $baselineUrl -OutFile $oldExe';
 assert.equal(oldProbe.split(command).length,2);
 assert.equal(newProbe,oldProbe.replace(command,command+' -MaximumRetryCount 3 -RetryIntervalSec 2'),'Only bounded download retries may change in the native probe');
}
export function validateOriginal151(run,jobs,artifact,logs){
 assert.equal(run.id,origin151.run);assert.equal(run.head_sha,origin151.sha);assert.equal(run.workflow_id,origin151.workflow);assert.equal(run.conclusion,'failure');
 const job=jobs.find(j=>j.name==='build-and-test');assert.ok(job);
 for(const name of ['Run Java and FXML test suite','Preserve verified Windows Java inputs'])assert.equal(job.steps.find(s=>s.name===name)?.conclusion,'success');
 const failed=job.steps.filter(s=>s.conclusion==='failure');assert.equal(failed.length,1);assert.equal(failed[0].name,'Verify upgrade from VN code 1.3.0 Preview with saved data');
 assert.ok(logs.includes('Invoke-WebRequest:')&&logs.includes('Unicorn!'),'Require the observed GitHub download service failure');
 assert.equal(artifact.id,origin151.artifact);assert.equal(artifact.name,'VN-code-1.5.1-Windows-verified-java');assert.equal(artifact.expired,false);assert.equal(artifact.digest,'sha256:'+origin151.digest);
}
export function validateReuse151(proof,sha,summary){
 assert.equal(proof.version,'1.5.1');assert.equal(proof.sourceCommit,sha);assert.equal(proof.originRunId,origin151.run);assert.equal(proof.originSourceCommit,origin151.sha);
 assert.equal(proof.artifactId,origin151.artifact);assert.equal(proof.archiveSha256,origin151.digest);assert.equal(proof.applicationJarSha256,origin151.jar);
 for(const key of ['applicationAndJavaTestInputsUnchanged','javaExecutedInOriginRun','nativeChecksMustRunAgain','fixtureClassesCompiledWithoutChangingJar'])assert.equal(proof[key],true,key);
 assert.deepEqual(proof.tests,{tests:1924,failures:0,errors:0,skipped:2});
 for(const key of ['tests','failures','errors','skipped'])assert.equal(summary[key],proof.tests[key]);
}
async function main(){
 assert.equal(process.env.GITHUB_REPOSITORY,'ntccong2468-lab/Bancapnhat');assert.equal(process.env.RUNNER_OS,'Windows');assert.equal(process.env.VNCODE_REUSE_JAVA_RUN,String(origin151.run));
 const git=args=>execFileSync('git',args,{encoding:'utf8'});const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:64*1024*1024});const api=p=>JSON.parse(gh(['api','repos/ntccong2468-lab/Bancapnhat/'+p]));
 git(['merge-base','--is-ancestor',origin151.sha,'HEAD']);
 validateDelta151(git(['diff','--name-only','--no-renames','-z',origin151.sha,'HEAD']).split('\0').filter(Boolean),git(['show',origin151.sha+':tools/windows-upgrade-smoke.ps1']),readFileSync('tools/windows-upgrade-smoke.ps1','utf8'));
 validateOriginal151(api('actions/runs/'+origin151.run),api('actions/runs/'+origin151.run+'/jobs?per_page=100').jobs,api('actions/artifacts/'+origin151.artifact),gh(['run','view',String(origin151.run),'--repo','ntccong2468-lab/Bancapnhat','--log']));
 const zip=execFileSync('gh',['api','repos/ntccong2468-lab/Bancapnhat/actions/artifacts/'+origin151.artifact+'/zip'],{maxBuffer:128*1024*1024});assert.equal(createHash('sha256').update(zip).digest('hex'),origin151.digest);
 const file=path.join(process.env.RUNNER_TEMP,'vncode151-verified-java.zip');writeFileSync(file,zip);mkdirSync('target',{recursive:true});
 execFileSync('pwsh',['-NoProfile','-Command','Expand-Archive -LiteralPath $env:VNCODE_JAVA_ARCHIVE -DestinationPath target -Force'],{env:{...process.env,VNCODE_JAVA_ARCHIVE:file},stdio:'inherit'});
 const xmls=readdirSync('target/surefire-reports').filter(n=>/^TEST-.*\.xml$/.test(n)).map(n=>readFileSync(path.join('target/surefire-reports',n),'utf8'));assert.equal(xmls.length,323);
 const result=reportRows(xmls,origin151.tests);assert.deepEqual(result.total,{tests:1924,failures:0,errors:0,skipped:2});
 const jarHash=createHash('sha256').update(readFileSync('target/VNcode-1.5.1.jar')).digest('hex');assert.equal(jarHash,origin151.jar);
 const proof={version:'1.5.1',sourceCommit:git(['rev-parse','HEAD']).trim(),originRunId:origin151.run,originSourceCommit:origin151.sha,artifactId:origin151.artifact,archiveSha256:origin151.digest,restoredApplicationJarSha256:jarHash,applicationAndJavaTestInputsUnchanged:true,tests:result.total,javaExecutedInOriginRun:true,nativeChecksMustRunAgain:true,reason:'transient-github-baseline-download'};
 writeFileSync('target/java-verification-reuse.json',JSON.stringify(proof,null,2)+'\n');
 console.log(`REUSED_WINDOWS_JAVA: fresh full execution in run ${origin151.run}; immutable archive and JAR verified; app/tests/dependencies unchanged. All native probes rerun.`);console.log(result.text);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main();
