import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateStableAsset,validatePublishedStable} from './publish-vncode-stable.mjs';

const version='1.4.6',digest='a'.repeat(64),size=140000000;
const asset={name:`VN-code-${version}-Windows-x64.exe`,state:'uploaded',digest:`sha256:${digest}`,size,
    browser_download_url:`https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v${version}/VN-code-${version}-Windows-x64.exe`};
test('published stable release has notes and a platform installer the desktop can discover',()=>{
    validateStableAsset(asset,version,digest,size);
    validatePublishedStable({tag_name:`v${version}`,draft:false,prerelease:false,body:'Changes for sellers',assets:[asset]},version);
    for(const patch of [{draft:true},{prerelease:true},{body:''},{assets:[]}])
        assert.throws(()=>validatePublishedStable({tag_name:`v${version}`,draft:false,prerelease:false,body:'Notes',assets:[asset],...patch},version));
});
test('stable publication rejects the wrong repository, diagnostic filename, bytes or digest',()=>{
    for(const patch of [{name:'VN-code-1.4.6-License-Diagnostics-Windows-x64.exe'},{size:size+1},{digest:'sha256:'+'b'.repeat(64)},
        {browser_download_url:asset.browser_download_url.replace('/Bancapnhat/','/Vncode/')}])
        assert.throws(()=>validateStableAsset({...asset,...patch},version,digest,size));
});
test('draft preparation retains production acceptance gate and publish promotes GitHub latest',()=>{
    const source=readFileSync(new URL('./publish-vncode-stable.mjs',import.meta.url),'utf8');
    assert.match(source,/if\(mode==='publish'\)\s*\{[\s\S]*?validateLicenseReadiness/);
    assert.match(source,/make_latest:'true'/);
    assert.match(source,/validatePublishedStable\(api\('releases\/latest'\),version\)/);
    assert.match(source,/Never overwrite an already published version/);
});
