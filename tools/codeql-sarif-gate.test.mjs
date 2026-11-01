import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectSarif } from './codeql-sarif-gate.mjs';

function sarif(severity, level='warning') {
  return {version:'2.1.0', runs:[{tool:{driver:{rules:[{id:'java/example', properties:severity === undefined ? {} : {'security-severity':severity}}]}}, results:[{ruleId:'java/example', level}]}]};
}

test('passes a complete result with no findings', () => {
  assert.deepEqual(inspectSarif({version:'2.1.0',runs:[{tool:{driver:{rules:[]}},results:[]}]}), {total:0,blocked:0});
});

test('blocks high and critical severity but reports lower findings without blocking', () => {
  assert.deepEqual(inspectSarif(sarif('7.0')), {total:1,blocked:1});
  assert.deepEqual(inspectSarif(sarif('9.8')), {total:1,blocked:1});
  assert.deepEqual(inspectSarif(sarif('6.9')), {total:1,blocked:0});
});

test('does not silently pass results whose severity is missing or invalid', () => {
  for (const value of [undefined, '', 'NaN', '11', '-1']) {
    assert.deepEqual(inspectSarif(sarif(value)), {total:1,blocked:1});
  }
});

test('resolves result ruleIndex when the identifier is omitted', () => {
  const report=sarif('8.0');report.runs[0].results=[{ruleIndex:0}];
  assert.deepEqual(inspectSarif(report), {total:1,blocked:1});
});

test('rejects missing runs, malformed results and unknown rules', () => {
  for (const report of [null,{}, {version:'2.1.0',runs:[]}, {version:'2.1.0',runs:[{}]}]) {
    assert.throws(()=>inspectSarif(report));
  }
  const report=sarif('9.0');report.runs[0].results=[{ruleId:'unknown'}];
  assert.throws(()=>inspectSarif(report));
});

test('resolves real CodeQL extension rule references and enforces their severity', () => {
  const report={version:'2.1.0',runs:[{
    tool:{driver:{rules:[]},extensions:[{name:'codeql-action/pr-diff-range',rules:[]},{name:'codeql/javascript-queries',rules:[
      {id:'js/file-system-race',properties:{'security-severity':'7.7'}},
      {id:'js/regex/missing-regexp-anchor',properties:{'security-severity':'7.8'}}
    ]}]},results:[
      {ruleId:'js/file-system-race',rule:{id:'js/file-system-race',index:0,toolComponent:{index:1}}},
      {ruleId:'js/regex/missing-regexp-anchor',rule:{id:'js/regex/missing-regexp-anchor',index:1,toolComponent:{index:1}}}
    ]
  }]};
  assert.deepEqual(inspectSarif(report),{total:2,blocked:2});
  report.runs[0].tool.extensions[1].rules[0].properties['security-severity']='6.9';
  assert.deepEqual(inspectSarif(report),{total:2,blocked:1});
});

test('fails closed for ambiguous, missing, or conflicting extension references', () => {
  function report() {return {version:'2.1.0',runs:[{tool:{driver:{rules:[{id:'x',properties:{'security-severity':'1'}}]},extensions:[{name:'queries',rules:[{id:'x',properties:{'security-severity':'9'}}]}]},results:[{ruleId:'x',rule:{id:'x',index:0,toolComponent:{index:0}}}]}]};}
  const bad=[
    r=>{r.runs[0].results[0].rule.toolComponent.index=2;},
    r=>{r.runs[0].results[0].rule.toolComponent.name='wrong';},
    r=>{r.runs[0].results[0].rule.id='other';},
    r=>{r.runs[0].results[0].rule.index=1;},
    r=>{r.runs[0].results[0].ruleIndex=1;},
    r=>{r.runs[0].tool.extensions[0].rules.push({id:'x',properties:{'security-severity':'1'}});delete r.runs[0].results[0].rule.index;},
    r=>{delete r.runs[0].results[0].rule.toolComponent.index;},
    r=>{delete r.runs[0].tool.extensions[0].rules;},
  ];
  for (const mutate of bad) {const r=report();mutate(r);assert.throws(()=>inspectSarif(r));}
  const r=report();assert.deepEqual(inspectSarif(r),{total:1,blocked:1});
  r.runs[0].results[0].rule.toolComponent={name:'queries'};
  assert.deepEqual(inspectSarif(r),{total:1,blocked:1});
});
