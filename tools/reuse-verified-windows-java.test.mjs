import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateDelta,validateOriginal,reportRows,origin,baselineHash} from './reuse-verified-windows-java.mjs';
const old='$baselineHash=if($FromStable1415){old}\nif(hash -ne expected){throw "bad checksum"}',fixed=old.replace('$baselineHash=if($FromStable1415)',`$baselineHash=if($FromStable1416){'${baselineHash}'}elseif($FromStable1415)`);
test('only the exact missing-baseline-hash repair can reuse the unchanged Java build',()=>{
 validateDelta(['tools/windows-upgrade-smoke.ps1','.github/workflows/build-java.yml','docs/proof.md'],old,fixed);
 validateDelta([],old,fixed.replaceAll('\n','\r\n'));
 for(const changed of ['src/main/java/App.java','src/test/java/AppTest.java','src/main/resources/settings.json','pom.xml','build.bat','tools/WindowsUpgradeDataProbe.java'])assert.throws(()=>validateDelta([changed],old,fixed));
 assert.throws(()=>validateDelta([],old,fixed.replace('throw "bad checksum"','return true')));
 assert.throws(()=>validateDelta([],fixed,fixed));
});
const run={id:origin.run,head_sha:origin.sha,workflow_id:origin.workflow,conclusion:'failure'};
const job={name:'build-and-test',steps:[{name:'Run Java and FXML test suite',conclusion:'success'},{name:'Preserve verified Windows Java inputs',conclusion:'success'},{name:'Verify data-preserving upgrade from Latest VN code 1.4.16',conclusion:'failure'}]};
const artifact={id:origin.artifact,name:'VN-code-1.4.17-Windows-verified-java',expired:false,digest:`sha256:${origin.digest}`};
test('recovery accepts only the exact original verification and immutable artifact',()=>{
 validateOriginal(run,[job],artifact);
 for(const patch of [{id:1},{head_sha:'b'.repeat(40)},{workflow_id:0}])assert.throws(()=>validateOriginal({...run,...patch},[job],artifact));
 assert.throws(()=>validateOriginal(run,[{...job,steps:job.steps.map(s=>s.name.startsWith('Run Java')?{...s,conclusion:'failure'}:s)}],artifact));
 assert.throws(()=>validateOriginal(run,[{...job,steps:[...job.steps,{name:'Another failure',conclusion:'failure'}]}],artifact));
 assert.throws(()=>validateOriginal(run,[job],{...artifact,digest:'sha256:'+'0'.repeat(64)}));
});
const xml=(name,tests,failures=0,skipped=0)=>`<?xml version="1.0"?><testsuite name="${name}" tests="${tests}" failures="${failures}" errors="0" skipped="${skipped}"></testsuite>`;
test('reused summaries come from complete passing original JUnit XML, with supported platform skips',()=>{
 const suites=Array.from({length:100},(_,i)=>xml(`fixture.C${i}`,i===0?1643:1));suites.push(xml('com.vncode.app.shared.WindowsSecretProtectorTest',1,0,1),xml('com.vncode.app.features.gs1.Gs1PrefixTemplateStoreTest',1,0,1));
 assert.equal(reportRows(suites).total.tests,1744);
 assert.throws(()=>reportRows(suites.slice(1)));
 assert.throws(()=>reportRows([...suites,suites[0]]));
 assert.throws(()=>reportRows(suites.map((s,i)=>i===0?xml('fixture.C0',1643,1):s)));
 assert.throws(()=>reportRows(suites.map((s,i)=>i===100?xml('fixture.UnapprovedSkip',1,0,1):s)));
});
test('the real probe has a pinned 1.4.16 checksum while keeping all integrity checks',()=>{
 const probe=readFileSync(new URL('./windows-upgrade-smoke.ps1',import.meta.url),'utf8');
 assert.ok(probe.includes(`$baselineHash=if($FromStable1416){'${baselineHash}'}elseif($FromStable1418)`));
 assert.match(probe,/Get-FileHash \$oldExe -Algorithm SHA256/);assert.match(probe,/Existing VN code data or saved templates changed during upgrade/);
 const workflow=readFileSync(new URL('../.github/workflows/build-java.yml',import.meta.url),'utf8');assert.match(workflow,/mvnw.cmd -B clean verify/);assert.match(workflow,/run: node tools\/reuse-verified-windows-java.mjs/);
 assert.match(workflow,/javac -cp \$classpath -d target\\test-classes/);assert.match(workflow,/SellerWorkspaceFixture.java src\\test\\java\\com\\vncode\\app\\features\\license\\LicenseDomainFixture.java/);assert.match(workflow,/Test-fixture compilation replaced the verified application JAR/);
});
