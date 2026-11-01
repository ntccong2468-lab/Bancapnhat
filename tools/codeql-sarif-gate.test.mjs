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
