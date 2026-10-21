import test from 'node:test';
import assert from 'node:assert/strict';
import {origin151,validateDelta151,validateOriginal151,validateReuse151} from './reuse-verified-windows-java-151.mjs';
const old='Invoke-WebRequest -Uri $baselineUrl -OutFile $oldExe';
const fixed=old+' -MaximumRetryCount 3 -RetryIntervalSec 2';
test('151 recovery permits only download retries and rejects every application/test/dependency delta',()=>{
 validateDelta151(['tools/windows-upgrade-smoke.ps1','docs/result.json'],old,fixed);
 for(const file of ['src/main/java/App.java','src/test/java/AppTest.java','pom.xml','build.bat','src/main/resources/logo.png'])assert.throws(()=>validateDelta151([file],old,fixed));
 assert.throws(()=>validateDelta151(['tools/windows-upgrade-smoke.ps1'],old,fixed+'; skip checksum'));
});
function original(){return [{id:origin151.run,head_sha:origin151.sha,workflow_id:origin151.workflow,conclusion:'failure'},[{name:'build-and-test',steps:[{name:'Run Java and FXML test suite',conclusion:'success'},{name:'Preserve verified Windows Java inputs',conclusion:'success'},{name:'Verify upgrade from VN code 1.3.0 Preview with saved data',conclusion:'failure'}]}],{id:origin151.artifact,name:'VN-code-1.5.1-Windows-verified-java',expired:false,digest:'sha256:'+origin151.digest},'Invoke-WebRequest: Unicorn!'];}
test('151 reuse requires the exact successful full Java origin and the observed external download failure',()=>{
 validateOriginal151(...original());
 for(const change of ['java','failure','artifact','network']){const a=original();if(change==='java')a[1][0].steps[0].conclusion='failure';if(change==='failure')a[1][0].steps[2].name='Native launch';if(change==='artifact')a[2].digest='sha256:'+'0'.repeat(64);if(change==='network')a[3]='Application assertion failure';assert.throws(()=>validateOriginal151(...a));}
});
const summary={tests:1924,failures:0,errors:0,skipped:2,passed:1922},sha='a'.repeat(40);
function proof(){return {version:'1.5.1',sourceCommit:sha,originRunId:origin151.run,originSourceCommit:origin151.sha,artifactId:origin151.artifact,archiveSha256:origin151.digest,applicationJarSha256:origin151.jar,applicationAndJavaTestInputsUnchanged:true,javaExecutedInOriginRun:true,nativeChecksMustRunAgain:true,fixtureClassesCompiledWithoutChangingJar:true,tests:{tests:1924,failures:0,errors:0,skipped:2}};}
test('151 reuse rejects changed JAR, archive, source, unfinished fixtures or test errors',()=>{
 validateReuse151(proof(),sha,summary);
 for(const [key,value] of [['applicationJarSha256','b'.repeat(64)],['archiveSha256','c'.repeat(64)],['originRunId',1],['applicationAndJavaTestInputsUnchanged',false],['fixtureClassesCompiledWithoutChangingJar',false]])assert.throws(()=>validateReuse151({...proof(),[key]:value},sha,summary));
 assert.throws(()=>validateReuse151({...proof(),tests:{...proof().tests,errors:1}},sha,summary));
});
