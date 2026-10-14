import test from 'node:test';
import assert from 'node:assert/strict';
import {validateCandidateDraft,validatePortalEntries} from './publish-vncode-license-candidate.mjs';

test('a license-test candidate remains a draft bound to the actual verified source',()=>{
 const sha='a'.repeat(40),tag='v1.4.0-license-test.1';
 const release={draft:true,prerelease:true,tag_name:tag,target_commitish:sha};
 assert.doesNotThrow(()=>validateCandidateDraft(release,tag,sha,'1.4.0'));
 for(const change of [{draft:false},{prerelease:false},{target_commitish:'b'.repeat(40)},{tag_name:'v1.4.0'}])
  assert.throws(()=>validateCandidateDraft({...release,...change},tag,sha,'1.4.0'));
 assert.throws(()=>validateCandidateDraft(release,'v1.4.0-preview.1',sha,'1.4.0'));
 assert.throws(()=>validateCandidateDraft(release,tag,sha,'1.3.0'));
});


test('Portal download must include deployment instructions and exclude credentials or escaping paths',()=>{
 const files=['src/desktop.js','HUONG-DAN-CAP-NHAT-032.md','wrangler.jsonc','schema.sql'];
 assert.doesNotThrow(()=>validatePortalEntries(files));
 for(const unsafe of ['.dev.vars','.env.production','LICENSE_SIGNING_KEY.txt','node_modules/a.js','../private','/private'])
  assert.throws(()=>validatePortalEntries([...files,unsafe]));
 assert.throws(()=>validatePortalEntries(files.filter(name=>name!=='HUONG-DAN-CAP-NHAT-032.md')));
});
