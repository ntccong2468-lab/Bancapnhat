import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

/** Fail closed when a CodeQL security result cannot be classified. */
export function inspectSarif(report) {
  if (!report || report.version !== '2.1.0' || !Array.isArray(report.runs) || report.runs.length === 0) {
    throw new Error('Missing or invalid SARIF runs');
  }
  let total=0, blocked=0;
  for (const run of report.runs) {
    const rules=run?.tool?.driver?.rules;
    if (!Array.isArray(rules) || !Array.isArray(run?.results)) throw new Error('Missing SARIF rules or results');
    const byId=new Map(rules.map(rule=>[rule.id,rule]));
    for (const result of run.results) {
      const rule=result?.ruleId ? byId.get(result.ruleId) : rules[result?.ruleIndex];
      if (!rule) throw new Error('SARIF result references an unknown rule');
      const value=rule.properties?.['security-severity'];
      const severity=(typeof value==='number'||typeof value==='string'&&value.trim()!=='')?Number(value):NaN;
      total++;
      if (!Number.isFinite(severity) || severity<0 || severity>10 || severity>=7) blocked++;
    }
  }
  return {total,blocked};
}

async function main() {
  const files=process.argv.slice(2);
  if (files.length===0) throw new Error('Expected at least one CodeQL SARIF file');
  let total=0,blocked=0;
  for (const file of files) {
    const result=inspectSarif(JSON.parse(await readFile(file,'utf8')));
    total+=result.total;blocked+=result.blocked;
  }
  console.log(`CodeQL security findings: ${total}; high, critical or unclassified: ${blocked}`);
  if (blocked) process.exitCode=1;
}

if (process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
  main().catch(error=>{console.error(`CodeQL gate failed: ${error.message}`);process.exitCode=1;});
}
