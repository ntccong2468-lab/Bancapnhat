import test from 'node:test';
import assert from 'node:assert/strict';
import {validatePreview,validateInstaller} from './publish-vncode-preview.mjs';
test('incomplete builds can only be published as explicitly disclosed previews',()=>{
 const status={version:'1.2.0',complete:false,blocked:['Ozon workflow remains incomplete']};
 assert.doesNotThrow(()=>validatePreview(status,'v1.2.0-preview.1'));
 assert.throws(()=>validatePreview(status,'v1.2.0'));
 assert.throws(()=>validatePreview({...status,blocked:[]},'v1.2.0-preview.1'));
 assert.throws(()=>validatePreview({...status,complete:true},'v1.2.0-preview.1'));
});
test('a preview installer must match its checksum and be a Windows executable',()=>{
 assert.throws(()=>validateInstaller(Buffer.from('not an installer'),'0'.repeat(64)));
});

test('newer previews retain matching Windows version and cannot reuse an old tag',()=>{
 const status={version:'1.2.1',complete:false,blocked:['AI remains incomplete']};
 assert.doesNotThrow(()=>validatePreview(status,'v1.2.1-preview.1'));
 assert.throws(()=>validatePreview(status,'v1.2.0-preview.1'));
});
