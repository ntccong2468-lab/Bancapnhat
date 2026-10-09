import test from 'node:test';
import assert from 'node:assert/strict';
import {validatePreview,validateInstaller,validatePreviewUpgrades} from './publish-vncode-preview.mjs';
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

const upgrade=(fromVersion)=>({appName:'VN code',version:'1.2.2',fromVersion,result:'passed',installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',singleRegistration:true,dataPreserved:true,liveMarketplaceMutations:false,fingerprint:'a'.repeat(64)});
test('a newer preview requires upgrades from both already published previews',()=>{
 assert.doesNotThrow(()=>validatePreviewUpgrades('1.2.2',upgrade('1.2.0'),upgrade('1.2.1')));
 assert.throws(()=>validatePreviewUpgrades('1.2.2',upgrade('1.2.0'),null));
 assert.throws(()=>validatePreviewUpgrades('1.2.2',upgrade('1.2.0'),upgrade('1.2.0')));
 assert.throws(()=>validatePreviewUpgrades('1.2.2',upgrade('1.2.0'),{...upgrade('1.2.1'),dataPreserved:false}));
});
test('the preceding 1.2.1 preview still needs only its 1.2.0 baseline',()=>{
 assert.doesNotThrow(()=>validatePreviewUpgrades('1.2.1',{...upgrade('1.2.0'),version:'1.2.1'},null));
});
