import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';


/** Resolve SARIF reportingDescriptorReference, including CodeQL query-pack extensions. */
function resultRule(run, result) {
  const driver=run?.tool?.driver;
  if (!driver || !Array.isArray(driver.rules)) throw new Error('Missing SARIF driver rules');
  if (!result || typeof result!=='object' || Array.isArray(result)) throw new Error('Invalid SARIF result');
  const reference=result.rule;
  if (reference!==undefined && (!reference || typeof reference!=='object' || Array.isArray(reference))) throw new Error('Invalid SARIF rule reference');
  const component=reference?.toolComponent;
  let source=driver;
  if (component!==undefined) {
    if (!component || typeof component!=='object' || Array.isArray(component)) throw new Error('Invalid SARIF tool component');
    const extensions=run.tool.extensions;
    if (!Array.isArray(extensions)) throw new Error('Missing SARIF tool extensions');
    if (component.index!==undefined) {
      if (!Number.isInteger(component.index) || component.index<0 || component.index>=extensions.length) throw new Error('Unknown SARIF tool component');
      source=extensions[component.index];
      if ((component.name!==undefined && component.name!==source?.name) || (component.guid!==undefined && component.guid!==source?.guid)) throw new Error('Conflicting SARIF tool component');
    } else {
      const matches=[driver,...extensions].filter(item=>item && (component.name===undefined || item.name===component.name) && (component.guid===undefined || item.guid===component.guid));
      if ((component.name===undefined && component.guid===undefined) || matches.length!==1) throw new Error('Unknown or ambiguous SARIF tool component');
      source=matches[0];
    }
  }
  if (!Array.isArray(source?.rules)) throw new Error('Missing SARIF component rules');
  const topId=result.ruleId, nestedId=reference?.id;
  if ((topId!==undefined && (typeof topId!=='string' || !topId)) || (nestedId!==undefined && (typeof nestedId!=='string' || !nestedId)) || (topId!==undefined && nestedId!==undefined && topId!==nestedId)) throw new Error('Conflicting SARIF rule identifier');
  const topIndex=result.ruleIndex, nestedIndex=reference?.index;
  if (topIndex!==undefined && nestedIndex!==undefined && topIndex!==nestedIndex) throw new Error('Conflicting SARIF rule index');
  const id=topId??nestedId, index=topIndex??nestedIndex;
  if (index!==undefined) {
    if (!Number.isInteger(index) || index<0 || index>=source.rules.length) throw new Error('SARIF result references an unknown rule');
    const rule=source.rules[index];
    if (!rule || typeof rule.id!=='string' || !rule.id || (id!==undefined && id!==rule.id)) throw new Error('Conflicting SARIF rule reference');
    return rule;
  }
  const matches=source.rules.filter(rule=>rule?.id===id && typeof id==='string' && id!=='');
  if (matches.length!==1) throw new Error('SARIF result references an unknown or ambiguous rule');
  return matches[0];
}

/** Fail closed when a CodeQL security result cannot be classified. */
export function inspectSarif(report) {
  if (!report || report.version !== '2.1.0' || !Array.isArray(report.runs) || report.runs.length === 0) {
    throw new Error('Missing or invalid SARIF runs');
  }
  let total=0, blocked=0;
  for (const run of report.runs) {
    const rules=run?.tool?.driver?.rules;
    if (!Array.isArray(rules) || !Array.isArray(run?.results)) throw new Error('Missing SARIF rules or results');
    for (const result of run.results) {
      const rule=resultRule(run,result);
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
