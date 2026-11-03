import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { findReport, inspectScan } from './openqodex-health-gate.mjs';

// Shapes from OpenQodex 0.16.0 ScanResult; only synthetic content is used.
const healthy = () => ({
  selection: [{scanner:'sqllint',wanted:true,paths:['changed.sql'],projects:[],skip:null},
    {scanner:'semgrep',wanted:false,paths:[],projects:[],skip:'no matching files'}],
  scanners: [{scanner:'sqllint',status:'ran',reason:null},
    {scanner:'semgrep',status:'no_matching_files',reason:null}],
  candidates: [{reviewSeverity:'major',message:'FAKE_SECRET_DO_NOT_OUTPUT',filePath:'private-file'}],
  outside: [{message:'FAKE_OUTSIDE_SECRET'}],
});
async function temp(t) { const d=await mkdtemp(join(tmpdir(),'oq-health-')); t.after(()=>rm(d,{recursive:true,force:true})); return d; }
async function report(root, name, data) {
  const p=join(root,name,'scan'); await mkdir(p,{recursive:true});
  await writeFile(join(p,'scan.json'),typeof data==='string'?data:JSON.stringify(data));
}

test('counts findings without returning sensitive report content', () => {
  const result=inspectScan(healthy());
  assert.equal(result.status,'complete'); assert.equal(result.selected,1);
  assert.equal(result.findings.major,1); assert.equal(result.outsideAdvisories,1);
  assert.doesNotMatch(JSON.stringify(result),/FAKE_SECRET|private-file|FAKE_OUTSIDE_SECRET/);
});

test('fails closed for selected scanner missing, failed, disabled, installing or partial', () => {
  for (const status of ['failed','not_installed','installing','disabled','untrusted','no_matching_files']) {
    const s=healthy(); s.scanners[0].status=status;
    assert.throws(()=>inspectScan(s));
  }
  const partial=healthy(); partial.scanners[0].reason='partial path and sensitive detail';
  assert.throws(()=>inspectScan(partial),/scanner-incomplete/);
  const missing=healthy(); missing.scanners.shift();
  assert.throws(()=>inspectScan(missing),/missing-scanner-status/);
});

test('fails when even an unselected scanner reports an inventory error', () => {
  const s=healthy(); s.scanners[1].status='failed'; s.scanners[1].reason='inventory error';
  assert.throws(()=>inspectScan(s),/scanner-incomplete/);
});

test('fails closed if an unselected scanner is disabled by configuration or offline mode', () => {
  const s=healthy(); s.scanners[1].status='disabled'; s.scanners[1].reason='offline or config';
  assert.throws(()=>inspectScan(s),/scanner-incomplete/);
});

test('rejects zero selection, duplicates, inconsistent status and malformed findings', () => {
  let s=healthy(); s.selection[0].wanted=false; assert.throws(()=>inspectScan(s),/no-selected-scanners/);
  s=healthy(); s.selection.push(s.selection[0]); assert.throws(()=>inspectScan(s),/invalid-selection/);
  s=healthy(); s.scanners.push(s.scanners[0]); assert.throws(()=>inspectScan(s),/invalid-scanner-status/);
  s=healthy(); s.scanners[1].status='ran'; assert.throws(()=>inspectScan(s),/inconsistent-selection/);
  s=healthy(); s.candidates[0].reviewSeverity='unknown'; assert.throws(()=>inspectScan(s),/invalid-finding-severity/);
  s=healthy(); s.outside={}; assert.throws(()=>inspectScan(s),/invalid-report/);
});

test('does not treat an untrusted configured scanner as covered', () => {
  const s=healthy(); s.selection.push({scanner:'custom:private-label',wanted:false});
  s.scanners.push({scanner:'custom:private-label',status:'untrusted',reason:'not approved'});
  assert.throws(()=>inspectScan(s),/scanner-incomplete/);
});

test('finds one Action report, ignoring its plan directory', async t => {
  const d=await temp(t); await mkdir(join(d,'openqodex-AbC123'));
  await report(d,'openqodex-DeF456',healthy());
  assert.equal(inspectScan(await findReport(d)).selected,1);
});

test('fails for missing, duplicate, malformed or linked reports', async t => {
  const d=await temp(t); await assert.rejects(findReport(d),/missing-report/);
  await report(d,'openqodex-AbC123','{'); await assert.rejects(findReport(d),/invalid-json/);
  await report(d,'openqodex-DeF456',healthy()); await assert.rejects(findReport(d),/multiple-reports/);
  // Windows CI accounts often cannot create symlinks without elevated rights.
  if (process.platform !== 'win32') {
    const e=await temp(t); await mkdir(join(e,'openqodex-AbC123','scan'),{recursive:true});
    await symlink(join(e,'outside.json'),join(e,'openqodex-AbC123','scan','scan.json'));
    await assert.rejects(findReport(e),/invalid-report-file/);
  }
});

test('CLI creates only sanitized summary on success and failure', async t => {
  const d=await temp(t); await report(d,'openqodex-AbC123',healthy());
  const script=fileURLToPath(new URL('./openqodex-health-gate.mjs',import.meta.url));
  const ok=spawnSync(process.execPath,[script,d],{encoding:'utf8'});
  assert.equal(ok.status,0,ok.stderr); const clean=await readFile(join(d,'openqodex-health-summary.json'),'utf8');
  assert.doesNotMatch(clean+ok.stdout+ok.stderr,/FAKE_SECRET|private-file|FAKE_OUTSIDE_SECRET/);
  const e=await temp(t); const bad=healthy(); bad.scanners[0].status='failed'; bad.scanners[0].reason='FAKE_SECRET_DO_NOT_OUTPUT';
  await report(e,'openqodex-AbC123',bad);
  const failed=spawnSync(process.execPath,[script,e],{encoding:'utf8'});
  assert.notEqual(failed.status,0);
  const failure=await readFile(join(e,'openqodex-health-summary.json'),'utf8');
  assert.equal(JSON.parse(failure).status,'incomplete');
  assert.equal(JSON.parse(failure).error,'scanner-incomplete');
  assert.equal(JSON.parse(failure).scanner,'sqllint');
  assert.equal(JSON.parse(failure).scannerStatus,'failed');
  assert.doesNotMatch(failure+failed.stdout+failed.stderr,/FAKE_SECRET|private-file/);
});
