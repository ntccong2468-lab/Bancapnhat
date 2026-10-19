import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync,readdirSync,mkdirSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateJavaTestSummary} from './preview-source-guard.mjs';

// One bounded recovery of the 1.4.16 baseline-checksum error. This is not a test-skip option.
export const origin={run:39075980471,sha:'c09bc0d52eeae70f95dd8d9da065c4d309849c6d',workflow:379116841,
 artifact:12117796539,digest:'a63aeab854f814d7a72b449710e5478599a55e66cbb6fa78ca31a09963a14dbf',tests:1744};
export const baselineHash='9c18a6d388945358213dad0c6eaa158cdd53b4f78cacbe773fe87b6d0883dfb2';
const allowed=new Set(['.github/workflows/build-java.yml','tools/windows-upgrade-smoke.ps1',
 'tools/reuse-verified-windows-java.mjs','tools/reuse-verified-windows-java.test.mjs',
 'tools/publish-vncode-stable.mjs','tools/publish-vncode-stable.test.mjs']);
export function validateDelta(files,oldProbe,newProbe){
 oldProbe=oldProbe.replaceAll('\r\n','\n');newProbe=newProbe.replaceAll('\r\n','\n');
 assert.ok(files.every(file=>file.startsWith('docs/')||allowed.has(file)),'Application, Java test, dependency or packaging inputs changed; run the full Windows suite again');
 const repaired=oldProbe.replace('$baselineHash=if($FromStable1415)',`$baselineHash=if($FromStable1416){'${baselineHash}'}elseif($FromStable1415)`);
 assert.notEqual(repaired,oldProbe,'The original must be the known missing-hash probe');
 assert.equal(newProbe,repaired,'Only the missing 1.4.16 checksum may change in the installation probe');
}
export function validateOriginal(run,jobs,artifact){
 assert.equal(run.id,origin.run);assert.equal(run.head_sha,origin.sha);assert.equal(run.workflow_id,origin.workflow);assert.equal(run.conclusion,'failure');
 const job=jobs.find(job=>job.name==='build-and-test');assert.ok(job);
 for(const name of ['Run Java and FXML test suite','Preserve verified Windows Java inputs'])assert.equal(job.steps.find(s=>s.name===name)?.conclusion,'success',name);
 const failures=job.steps.filter(s=>s.conclusion==='failure');assert.equal(failures.length,1);assert.equal(failures[0].name,'Verify data-preserving upgrade from Latest VN code 1.4.16');
 assert.equal(artifact.id,origin.artifact);assert.equal(artifact.name,'VN-code-1.4.17-Windows-verified-java');assert.equal(artifact.expired,false);assert.equal(artifact.digest,`sha256:${origin.digest}`);
}
export function reportRows(xmls,expectedTests=origin.tests){
 const names=new Set(),rows=[];
 for(const xml of xmls){
  const root=xml.replace(/^\uFEFF/,'').match(/^\s*(?:<\?xml[^?]*\?>\s*)?<testsuite\b([^>]*)>/);assert.ok(root,'Require a Surefire suite root');
  const attrs=Object.fromEntries([...root[1].matchAll(/([\w:.-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
  assert.match(attrs.name??'',/^[A-Za-z_$][\w.$]*$/);assert.ok(!names.has(attrs.name),'Duplicate suite');names.add(attrs.name);
  for(const key of ['tests','failures','errors','skipped'])assert.match(attrs[key]??'',/^\d+$/);
  rows.push({name:attrs.name,tests:+attrs.tests,failures:+attrs.failures,errors:+attrs.errors,skipped:+attrs.skipped});
 }
 assert.ok(rows.length>100,'Require the complete uploaded report collection');
 const total=rows.reduce((a,r)=>({tests:a.tests+r.tests,failures:a.failures+r.failures,errors:a.errors+r.errors,skipped:a.skipped+r.skipped}),{tests:0,failures:0,errors:0,skipped:0});
 assert.equal(total.tests,expectedTests);assert.equal(total.failures,0);assert.equal(total.errors,0);
 const text=rows.map(r=>`Tests run: ${r.tests}, Failures: ${r.failures}, Errors: ${r.errors}, Skipped: ${r.skipped}, Time elapsed: 0 s -- in ${r.name}`).join('\n')+`\nTests run: ${total.tests}, Failures: ${total.failures}, Errors: ${total.errors}, Skipped: ${total.skipped}`;
 validateJavaTestSummary(text);return {total,text};
}
async function main(){
 assert.equal(process.env.GITHUB_REPOSITORY,'ntccong2468-lab/Bancapnhat');assert.equal(process.env.RUNNER_OS,'Windows');assert.equal(process.env.VNCODE_REUSE_JAVA_RUN,String(origin.run));
 const git=args=>execFileSync('git',args,{encoding:'utf8'});const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024});const api=endpoint=>JSON.parse(gh(['api',`repos/ntccong2468-lab/Bancapnhat/${endpoint}`]));
 git(['merge-base','--is-ancestor',origin.sha,'HEAD']);
 validateDelta(git(['diff','--name-only','--no-renames','-z',origin.sha,'HEAD']).split('\0').filter(Boolean),git(['show',`${origin.sha}:tools/windows-upgrade-smoke.ps1`]),readFileSync('tools/windows-upgrade-smoke.ps1','utf8'));
 const run=api(`actions/runs/${origin.run}`),jobs=api(`actions/runs/${origin.run}/jobs?per_page=100`).jobs,artifact=api(`actions/artifacts/${origin.artifact}`);validateOriginal(run,jobs,artifact);
 const archive=execFileSync('gh',['api',`repos/ntccong2468-lab/Bancapnhat/actions/artifacts/${origin.artifact}/zip`],{maxBuffer:128*1024*1024});
 assert.equal(createHash('sha256').update(archive).digest('hex'),origin.digest,'Immutable archive bytes must match the original verified artifact');
 const file=path.join(process.env.RUNNER_TEMP,'vncode-verified-java.zip');writeFileSync(file,archive);mkdirSync('target',{recursive:true});
 execFileSync('pwsh',['-NoProfile','-Command','Expand-Archive -LiteralPath $env:VNCODE_JAVA_ARCHIVE -DestinationPath target -Force'],{env:{...process.env,VNCODE_JAVA_ARCHIVE:file},stdio:'inherit'});
 const reports=readdirSync('target/surefire-reports').filter(name=>/^TEST-.*\.xml$/.test(name));const result=reportRows(reports.map(name=>readFileSync(path.join('target/surefire-reports',name),'utf8')));
 assert.ok(readFileSync('target/VNcode-1.4.17.jar').length>1000000);assert.ok(readdirSync('target/lib').length>10);
 const proof={version:'1.4.17',sourceCommit:git(['rev-parse','HEAD']).trim(),originRunId:origin.run,originSourceCommit:origin.sha,artifactId:origin.artifact,archiveSha256:origin.digest,applicationAndJavaTestInputsUnchanged:true,tests:result.total,javaExecutedInOriginRun:true,nativeChecksMustRunAgain:true};
 writeFileSync('target/java-verification-reuse.json',JSON.stringify(proof,null,2)+'\n');
 console.log(`REUSED_WINDOWS_JAVA: already executed on Windows in run ${origin.run}, source ${origin.sha}; application/test/dependency inputs are identical. Native installer checks run again.`);console.log(result.text);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main();
