import { constants } from 'node:fs';
import { lstat, open, readdir, writeFile } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { pathToFileURL } from 'node:url';

// OpenQodex 0.16.0: packages/core/src/types.ts ScanResult and
// packages/scanners/src/run.ts. The Action does not expose its report path.
const builtins = new Set(['semgrep','gitleaks','sqllint','osv-scanner','actionlint',
  'hadolint','shellcheck','ruff','brakeman','rubocop','bandit','oxlint','golangci',
  'zizmor','trivy','squawk','kube-linter','tflint','kubeconform','cargo-deny','checkov','sqlfluff']);
const statuses = new Set(['ran','no_matching_files','not_installed','installing','failed','disabled','untrusted']);
const severities = ['critical','major','minor','nitpick','info'];
const reportLimit = 64 * 1024 * 1024;
class GateError extends Error {
  constructor(code, scanner, scannerStatus) {
    super(code); this.code=code; this.scanner=scanner; this.scannerStatus=scannerStatus;
  }
}
const fail = (code, scanner, status) => { throw new GateError(code, scanner, status); };
const scannerName = value => typeof value === 'string' && (builtins.has(value) || /^custom:[^\x00-\x1f\x7f]{1,128}$/.test(value));

/** Inspect health only. Findings' paths, messages, and reasons are never returned. */
export function inspectScan(scan) {
  if (!scan || typeof scan !== 'object' || Array.isArray(scan) ||
      !Array.isArray(scan.selection) || !Array.isArray(scan.scanners) ||
      !Array.isArray(scan.candidates) || (scan.outside !== undefined && !Array.isArray(scan.outside))) fail('invalid-report');
  const selection = new Map();
  for (const entry of scan.selection) {
    if (!entry || !scannerName(entry.scanner) || typeof entry.wanted !== 'boolean' || selection.has(entry.scanner)) fail('invalid-selection');
    selection.set(entry.scanner, entry.wanted);
  }
  if (![...selection.values()].some(Boolean)) fail('no-selected-scanners');
  const seen = new Set(), rows = [];
  for (const entry of scan.scanners) {
    if (!entry || !scannerName(entry.scanner) || !selection.has(entry.scanner) ||
        seen.has(entry.scanner) || !statuses.has(entry.status) ||
        !(entry.reason === null || typeof entry.reason === 'string')) fail('invalid-scanner-status');
    seen.add(entry.scanner);
    const wanted = selection.get(entry.scanner);
    // Inventory failures can appear even for wanted=false. A `ran` scanner
    // with a reason may have refused files or encountered a partial error.
    if (['failed','untrusted','disabled'].includes(entry.status) || (wanted && entry.status !== 'ran') ||
        (entry.status === 'ran' && entry.reason !== null)) fail('scanner-incomplete', builtins.has(entry.scanner) ? entry.scanner : 'custom', entry.status);
    if (!wanted && entry.status !== 'no_matching_files') fail('inconsistent-selection');
    rows.push({ scanner: builtins.has(entry.scanner) ? entry.scanner : 'custom', wanted, status: entry.status });
  }
  if (seen.size !== selection.size) fail('missing-scanner-status');
  const findings = Object.fromEntries(severities.map(s => [s, 0]));
  for (const candidate of scan.candidates) {
    if (!candidate || !Object.hasOwn(findings, candidate.reviewSeverity)) fail('invalid-finding-severity');
    findings[candidate.reviewSeverity]++;
  }
  return { schema: 1, status: 'complete', selected: [...selection.values()].filter(Boolean).length,
    scanners: rows, findings, outsideAdvisories: Array.isArray(scan.outside) ? scan.outside.length : 0 };
}

async function isRealDir(path) {
  try { const s = await lstat(path); return s.isDirectory() && !s.isSymbolicLink(); }
  catch (e) { if (e.code === 'ENOENT') return false; throw e; }
}

/** The Action makes a plan directory and a run directory; only one may contain scan/. */
export async function findReport(runnerTemp) {
  if (typeof runnerTemp !== 'string' || !isAbsolute(runnerTemp) || !(await isRealDir(runnerTemp))) fail('invalid-runner-temp');
  const entries = await readdir(runnerTemp, { withFileTypes: true });
  const reports = [];
  for (const entry of entries) {
    if (!/^openqodex-[A-Za-z0-9]{6}$/.test(entry.name)) continue;
    if (!entry.isDirectory() || entry.isSymbolicLink()) fail('invalid-run-directory');
    const folder = join(runnerTemp, entry.name, 'scan');
    let stat;
    try { stat = await lstat(folder); } catch (e) { if (e.code === 'ENOENT') continue; throw e; }
    if (!stat.isDirectory() || stat.isSymbolicLink()) fail('invalid-scan-directory');
    reports.push(join(folder, 'scan.json'));
  }
  if (reports.length !== 1) fail(reports.length ? 'multiple-reports' : 'missing-report');
  const stat = await lstat(reports[0]).catch(e => { if (e.code === 'ENOENT') fail('missing-report'); throw e; });
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > reportLimit) fail('invalid-report-file');
  const handle = await open(reports[0], constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0));
  try {
    const opened = await handle.stat();
    if (!opened.isFile() || opened.size > reportLimit) fail('invalid-report-file');
    // The file can grow after stat; never let readFile allocate without a cap.
    const chunks = [];
    let used = 0;
    while (used <= reportLimit) {
      const chunk = Buffer.allocUnsafe(Math.min(64 * 1024, reportLimit + 1 - used));
      const { bytesRead } = await handle.read(chunk, 0, chunk.length, null);
      if (bytesRead === 0) break;
      used += bytesRead;
      chunks.push(chunk.subarray(0, bytesRead));
    }
    if (used > reportLimit) fail('invalid-report-file');
    try { return JSON.parse(Buffer.concat(chunks, used).toString('utf8')); } catch { fail('invalid-json'); }
  } finally { await handle.close(); }
}

async function main() {
  const temp = process.argv[2];
  if (process.argv.length !== 3 || !isAbsolute(temp ?? '')) fail('invalid-arguments');
  let summary, failure;
  try { summary = inspectScan(await findReport(temp)); }
  catch (error) {
    failure = error;
    summary = { schema: 1, status: 'incomplete', error: error instanceof GateError ? error.code : 'report-io-failed',
      ...(error instanceof GateError && error.scanner ? { scanner: error.scanner, scannerStatus: error.scannerStatus } : {}) };
  }
  // Never upload raw SARIF/scan.json, which may contain values the scanner missed.
  await writeFile(join(temp, 'openqodex-health-summary.json'), `${JSON.stringify(summary, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
  if (failure) { console.error('OpenQodex health gate: report missing, invalid, or scanner incomplete.'); process.exitCode = 1; }
  else console.log(`OpenQodex health gate: ${summary.selected} selected scanners ran; findings counted in sanitized summary.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(() => { console.error('OpenQodex health gate: unable to verify or write sanitized summary.'); process.exitCode = 1; });
}
