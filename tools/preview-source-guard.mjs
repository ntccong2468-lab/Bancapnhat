import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';

const publicationFiles=new Set([
 'tools/publish-vncode-preview.mjs',
 'tools/preview-source-guard.mjs',
 'tools/preview-source-guard.test.mjs',
 '.github/workflows/publish-vncode-preview.yml'
]);

// Acceptance is recorded after the tested build. Only publication metadata/tooling may differ;
// every other tracked input (application, tests, packaging, native probes, build workflow) is bound.
export function validatePublicationSource(buildSha,root=process.cwd()) {
 assert.match(buildSha,/^[0-9a-f]{40}$/);
 const git=args=>execFileSync('git',args,{cwd:root,encoding:'utf8'});
 git(['merge-base','--is-ancestor',buildSha,'HEAD']);
 const changed=git(['diff','--name-only','--no-renames','-z',buildSha,'HEAD']).split('\0').filter(Boolean);
 assert.ok(changed.every(name=>name.startsWith('docs/')||publicationFiles.has(name)),
  'Application/build inputs changed after the verified Windows build; rebuild is required');
}

export function validateJavaTestSummary(log) {
 const rows=[...log.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/g)]
  .map(row=>({tests:Number(row[1]),failures:Number(row[2]),errors:Number(row[3]),skipped:Number(row[4])}));
 assert.ok(rows.length>0,'Missing Java test results');
 assert.ok(rows.every(row=>row.failures===0&&row.errors===0),'A Java test failed');
 const summary=rows.reduce((a,b)=>a.tests>=b.tests?a:b);
 assert.equal(summary.failures,0);assert.equal(summary.errors,0);
 const skipped=[...log.matchAll(/Tests run: \d+, Failures: 0, Errors: 0, Skipped: ([1-9]\d*), Time elapsed: .*? -- in ([\w.]+)/g)];
 // NTFS lacks POSIX permissions; the off-Windows DPAPI-refusal case is disabled on Windows.
 // Each class has exactly one such case. Extra skips in either class or any other class fail.
 const platformCases=new Set(['com.vncode.app.features.gs1.Gs1PrefixTemplateStoreTest',
  'com.vncode.app.shared.WindowsSecretProtectorTest']);
 assert.ok(skipped.every(row=>platformCases.has(row[2])&&Number(row[1])===1));
 assert.equal(summary.skipped,skipped.reduce((sum,row)=>sum+Number(row[1]),0));
 assert.ok(summary.skipped<=2);
 assert.ok(summary.tests-summary.skipped>=585,'Require the complete passing Java suite');
 return {...summary,passed:summary.tests-summary.skipped};
}
