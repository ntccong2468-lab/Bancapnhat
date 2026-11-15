#!/usr/bin/env python3
"""Execute only the unchanged public 1.6.9 updater against actual GitHub GET."""
import argparse, datetime, hashlib, json, pathlib, subprocess

ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA='/workspace/toolchains/jdk-25.0.4.1/bin/java'
ZIP_SHA='a43e26199f27458d5264bd86a2c63f2976783cc0ccfc56be92d5bb61961bea58'
JAR_SHA='da4bf48b8ed32e29f8fb786b344e219a1479e981d3f3219939de9564c111637f'
SOURCE='b74b5edfd46636e8779c94f467eef01c03c50a14'

def load(p):return json.loads(pathlib.Path(p).read_text())
def digest(p):
    p=pathlib.Path(p)
    assert p.is_file() and not p.is_symlink(),str(p)
    with p.open('rb') as f:h=hashlib.file_digest(f,'sha256').hexdigest()
    return {'bytes':p.stat().st_size,'sha256':h}
def verify_jars():
    rows=load(ROOT/'original169-jars.json')
    assert len(rows)==75 and len({r['name'] for r in rows})==75
    assert {x.name for x in (ROOT/'original169').iterdir()}=={x['name'] for x in rows}
    for r in rows:assert digest(ROOT/'original169'/r['name'])=={k:r[k] for k in ('bytes','sha256')},r['name']
    assert digest(ROOT/'original169/VNcode-1.6.9.jar')=={'bytes':7665594,'sha256':JAR_SHA}
    assert digest(ROOT/'VN-code-1.6.9-Windows-x64.zip')=={'bytes':148353662,'sha256':ZIP_SHA}
    assert [x.relative_to(ROOT/'probe-classes').as_posix() for x in (ROOT/'probe-classes').rglob('*.class')]==['com/vncode/app/integration/update/Installed169UpdateProbe.class']
    return rows
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stage',choices=['before','after'],required=True)
    p.add_argument('--latest-json',type=pathlib.Path,required=True)
    p.add_argument('--review',type=pathlib.Path)
    a=p.parse_args();latest=load(a.latest_json)
    version='1.6.9' if a.stage=='before' else '1.6.10'
    status='CURRENT' if a.stage=='before' else 'AVAILABLE'
    assert latest['tag_name']=='v'+version and latest['immutable'] and not latest['draft'] and not latest['prerelease']
    asset=next(x for x in latest['assets'] if x['name']==f'VN-code-{version}-Windows-x64.msi')
    expect={'bytes':148763909,'sha256':'df41d1c79a73f92208c2d6c6ffc6fbc61dae35e5cb3debdf9b409b0628ceadf4'}
    if a.stage=='after':
        assert a.review is not None,'An independent reviewed package receipt is required'
        review=load(a.review)
        assert review['result']=='PASS' and review['sourceCommit']==SOURCE and review['ciRun']==43641520595
        expect=review['assets'][asset['name']]
    assert asset['size']==expect['bytes'] and asset['digest']=='sha256:'+expect['sha256']
    manifest=verify_jars();profile=ROOT/('profile-'+a.stage+'-'+datetime.datetime.now(datetime.UTC).strftime('%Y%m%dT%H%M%S%f'))
    out=ROOT/f'updater-{a.stage}.json';assert not out.exists(),'Do not overwrite an actual probe observation'
    cmd=[JAVA,'-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts','-Dvncode.appdata.dir='+str(profile),'-cp',str(ROOT/'probe-classes')+':'+str(ROOT/'original169/*'),'com.vncode.app.integration.update.Installed169UpdateProbe',str(out),status,version,expect['sha256'],str(expect['bytes']),str(ROOT/'original169/VNcode-1.6.9.jar')]
    run=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=120)
    (ROOT/f'probe-{a.stage}.log').write_text(run.stdout)
    assert run.returncode==0,'Actual updater failed; inspect probe log'
    assert verify_jars()==manifest
    after=ROOT/f'original169-jars-after-{a.stage}.json';after.write_text((ROOT/'original169-jars.json').read_text())
    actual=load(out)
    receipt={'kind':'VNCODE_ORIGINAL_PUBLIC_169_UPDATER_INTEGRITY','result':'PASS','stage':a.stage,'verifiedAtUtc':datetime.datetime.now(datetime.UTC).isoformat(),'installedBinaryVersion':'1.6.9','all75OriginalJarHashesUnchanged':True,'status':actual['checkStatus'],'latestVersion':actual['latestVersion'],'selectedMsiUrl':actual['selectedDownload'],'selectedMsiSha256':actual['sha256'],'selectedMsiBytes':actual['size'],'profile':profile.name,'scope':'Original 1.6.9 classes executed with a fresh synthetic profile, inherited Cloud proxy and verified CA. Linux networking; Windows installer selection only after network. No MainApplication, license activation, seller API, signing, paid operation or publication. Anonymous access and customer Windows popup/installation are not established.'}
    for k,path in {'originalJarManifest':ROOT/'original169-jars.json','afterJarManifest':after,'originalPublicZip':ROOT/'VN-code-1.6.9-Windows-x64.zip','originalAppJar':ROOT/'original169/VNcode-1.6.9.jar','probeSource':ROOT/'tools/Installed169UpdateProbe.java','probeClass':ROOT/'probe-classes/com/vncode/app/integration/update/Installed169UpdateProbe.class','probeRunner':pathlib.Path(__file__),'updaterReceipt':out}.items():receipt[k]=digest(path)
    (ROOT/f'updater-{a.stage}-integrity.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
