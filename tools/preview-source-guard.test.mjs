import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {validatePublicationSource,validateJavaTestSummary} from './preview-source-guard.mjs';

async function fixture(t) {
 const root=await mkdtemp(path.join(tmpdir(),'vncode-publication-test-'));
 t.after(()=>rm(root,{recursive:true,force:true}));
 const git=args=>execFileSync('git',args,{cwd:root,encoding:'utf8'}).trim();
 const write=async(name,body)=>{const file=path.join(root,name);await mkdir(path.dirname(file),{recursive:true});await writeFile(file,body);};
 const commit=()=>{git(['add','.']);git(['-c','user.name=Release fixture','-c','user.email=release-fixture@example.invalid','commit','-qm','Fixture']);return git(['rev-parse','HEAD']);};
 git(['init','-q']);await write('src/main/App.java','class App {}');await write('pom.xml','1.4.0');
 const sha=commit();return {root,git,write,commit,sha};
}

test('post-build acceptance can be committed without changing the bound application source',async t=>{
 const f=await fixture(t);
 await f.write('docs/validation/acceptance.json',JSON.stringify({sourceCommit:f.sha}));
 await f.write('tools/preview-source-guard.mjs','publication tooling');f.commit();
 assert.doesNotThrow(()=>validatePublicationSource(f.sha,f.root));
 await f.write('src/main/App.java','class ChangedApp {}');f.commit();
 assert.throws(()=>validatePublicationSource(f.sha,f.root),/rebuild is required/);
});

test('changed packaging and native probe inputs require a fresh verified build',async t=>{
 const f=await fixture(t);await f.write('tools/WindowsGs1Probe.java','changed probe');f.commit();
 assert.throws(()=>validatePublicationSource(f.sha,f.root),/rebuild is required/);
 assert.throws(()=>validatePublicationSource('--unsafe',f.root));
});

test('moves from documentation into runtime sources cannot bypass build binding',async t=>{
 const f=await fixture(t);await f.write('docs/example.txt','fixture');const sha=f.commit();
 f.git(['mv','docs/example.txt','src/main/extra.txt']);f.commit();
 assert.throws(()=>validatePublicationSource(sha,f.root),/rebuild is required/);
});

test('Windows test evidence reports the POSIX-only skip accurately',()=>{
 const log='Tests run: 9, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.1 s -- in com.vncode.app.features.gs1.Gs1PrefixTemplateStoreTest\nTests run: 1177, Failures: 0, Errors: 0, Skipped: 1';
 assert.deepEqual(validateJavaTestSummary(log),{tests:1177,failures:0,errors:0,skipped:1,passed:1176});
 assert.equal(validateJavaTestSummary('Tests run: 1177, Failures: 0, Errors: 0, Skipped: 0').passed,1177);
 for(const invalid of [log.replace('Skipped: 1','Skipped: 2'),log.replace('Gs1PrefixTemplateStoreTest','OtherTest'),log.replace('Tests run: 1177, Failures: 0','Tests run: 1177, Failures: 1'),log.replace('Errors: 0','Errors: 1'),'Tests run: 10, Failures: 0, Errors: 0, Skipped: 0','']){
  assert.throws(()=>validateJavaTestSummary(invalid));
 }
});
