import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import path from 'node:path';

const repository=process.env.GITHUB_REPOSITORY;
assert.match(repository??'',/^ntccong2468-lab\/(Bancapnhat|Vncode)$/);
const root=path.resolve('docs/releases/publication');
const index=JSON.parse(readFileSync(path.join(root,'index.json'),'utf8'));
assert.equal(index.repository,repository);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:8*1024*1024});
const api=endpoint=>JSON.parse(gh(['api',`repos/${repository}/${endpoint}`]));
for(const item of index.releases) {
  assert.match(item.tag,/^v\d{1,5}\.\d{1,5}\.\d{1,5}(?:-preview\.[1-9][0-9]*)?$/);
  const directory=path.join(root,item.tag);
  const files=['build-info.json','release-notes.md',...(item.hasFeatureStatus?['feature-status.json']:[]),'checksums.sha256'];
  const desired={};
  for(const name of files)desired[name]=hash(readFileSync(path.join(directory,name)));
  const expected={};
  for(const line of readFileSync(path.join(directory,'checksums.sha256'),'utf8').trim().split('\n')) {
    const match=/^([0-9a-f]{64})\s{2}([A-Za-z0-9_.-]+)$/.exec(line);assert.ok(match);
    expected[match[2]]=match[1];
  }
  expected['checksums.sha256']=desired['checksums.sha256'];
  assert.deepEqual(Object.keys(expected).sort(),item.originalAssets.map(a=>a.name).sort());
  for(const name of files)assert.equal(expected[name],desired[name]);
  const release=api(`releases/${item.id}`);
  assert.equal(release.tag_name,item.tag);assert.equal(release.target_commitish,item.targetCommitish);
  assert.equal(release.draft,item.draft);assert.equal(release.prerelease,item.prerelease);
  assert.ok(release.assets.every(a=>Object.hasOwn(expected,a.name)));
  for(const original of item.originalAssets.filter(a=>!files.includes(a.name))) {
    const current=release.assets.find(a=>a.name===original.name);
    assert.ok(current,original.name);assert.equal(current.id,original.id);
    assert.equal(current.state,'uploaded');assert.equal(current.size,original.size);
    assert.equal(current.digest,original.digest);assert.equal(expected[original.name],original.digest.slice(7));
  }
  const info=JSON.parse(readFileSync(path.join(directory,'build-info.json'),'utf8'));
  assert.equal(info.sourceCommit,item.sourceCommit);
  assert.equal(info.projectDeveloper,'Nguyễn Thành Công');
  const installer=item.originalAssets.find(a=>a.name===info.installer.filename);
  assert.ok(installer);assert.equal(installer.digest,'sha256:'+info.installer.sha256);
  assert.equal(installer.size,info.installer.bytes);
  for(const name of files) {
    const current=release.assets.find(a=>a.name===name);
    if(current?.state==='uploaded'&&current.digest==='sha256:'+desired[name])continue;
    if(current?.state==='uploaded')assert.equal(current.digest,item.originalAssets.find(a=>a.name===name).digest);
    gh(['release','upload',item.tag,path.join(directory,name),'--repo',repository,'--clobber']);
  }
  gh(['release','edit',item.tag,'--repo',repository,'--title',item.title,'--notes-file',path.join(directory,'release-notes.md')]);
  const published=api(`releases/${item.id}`);
  assert.equal(published.tag_name,item.tag);assert.equal(published.target_commitish,item.targetCommitish);
  assert.equal(published.draft,item.draft);assert.equal(published.prerelease,item.prerelease);
  assert.deepEqual(published.assets.map(a=>a.name).sort(),Object.keys(expected).sort());
  for(const asset of published.assets) {
    assert.equal(asset.state,'uploaded');assert.equal(asset.digest,'sha256:'+expected[asset.name]);
    if(asset.name.endsWith('.exe'))assert.equal(asset.id,item.originalAssets.find(a=>a.name===asset.name).id);
  }
  console.log(JSON.stringify({repository,tag:item.tag,metadata:'updated',checksums:'passed',installer:'unchanged'}));
}
