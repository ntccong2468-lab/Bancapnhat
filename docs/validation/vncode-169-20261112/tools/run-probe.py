#!/usr/bin/env python3
"""Read-only original published VN Code 1.6.8 updater check; no app override."""
import argparse, datetime, hashlib, json, os, pathlib, subprocess
ROOT=pathlib.Path('/tmp/vncode-169-exact168-probe')
AUDIT=pathlib.Path('/tmp/vncode-169-release-audit')
JAVA='/workspace/toolchains/jdk-25.0.4.1/bin/java'
CLASS=ROOT/'classes/com/vncode/app/integration/update/Installed168UpdateProbe.class'
SOURCE=ROOT/'Installed168UpdateProbe.java'
def digest(p):
    return {'bytes':p.stat().st_size,'sha256':hashlib.file_digest(p.open('rb'),'sha256').hexdigest()}
def load(p): return json.loads(p.read_text())
def verify_jars():
    original=load(ROOT/'jar-manifest-before.json')
    actual=[]
    for entry in original['files']:
        p=ROOT/entry['path']
        row={'path':entry['path'],**digest(p)}
        assert row==entry, 'Original public JAR changed: '+entry['path']
        actual.append(row)
    paths={str(p.relative_to(ROOT)) for p in (ROOT/'app').rglob('*.jar')}
    assert paths=={x['path'] for x in actual}, 'Unexpected application JAR set'
    assert len(actual)==75
    assert sorted(str(x.relative_to(ROOT/'classes')) for x in (ROOT/'classes').rglob('*.class'))==['com/vncode/app/integration/update/Installed168UpdateProbe.class'], 'Unexpected probe classes'
    return {**original,'files':actual}
def finalize(stage):
    manifest=verify_jars()
    after=ROOT/('jar-manifest-after-'+stage+'.json')
    after.write_text(json.dumps(manifest,indent=2)+'\n')
    receipt=AUDIT/('updater-'+stage+'.json')
    value=load(receipt)
    proof={'kind':'VNCODE_ORIGINAL_PUBLIC_168_UPDATER_INTEGRITY','verifiedAtUtc':datetime.datetime.now(datetime.UTC).isoformat(),
      'stage':stage,'installedBinaryVersion':'1.6.8','all75OriginalJarHashesUnchanged':True,
      'originalJarManifest':digest(ROOT/'jar-manifest-before.json'),'afterJarManifest':digest(after),
      'originalPublicZip':digest(ROOT/'VN-code-1.6.8-Windows-x64.zip'),
      'originalAppJar':digest(ROOT/'app/VNcode-1.6.8.jar'),'probeSource':digest(SOURCE),'probeClass':digest(CLASS),
      'probeRunner':digest(pathlib.Path(__file__)),'updaterReceipt':digest(receipt),
      'status':value['checkStatus'],'latestVersion':value['latestVersion'],
      'selectedMsiUrl':value['selectedDownload'],'selectedMsiSha256':value['sha256'],'selectedMsiBytes':value['size'],
      'limitations':['Linux runtime with Windows asset selection after actual network reads; no seller popup or installer execution.',
                     'Inherited Cloud proxy and configured trusted CA; repository credentials may be attached by proxy; anonymous access not independently proven.',
                     'No app version override, license activation, signing, seller API requests, Gmail, or publication.']}
    (AUDIT/('updater-'+stage+'-integrity.json')).write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps(proof,indent=2))
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--stage',choices=['before','after'],required=True)
    parser.add_argument('--latest-json',type=pathlib.Path)
    parser.add_argument('--finalize-existing',action='store_true')
    args=parser.parse_args()
    if not args.finalize_existing:
        assert args.latest_json
        expected='1.6.8' if args.stage=='before' else '1.6.9'
        status='CURRENT' if args.stage=='before' else 'AVAILABLE'
        latest=load(args.latest_json)
        assert latest['tag_name']=='v'+expected and not latest['draft'] and not latest['prerelease']
        asset=next(x for x in latest['assets'] if x['name']=='VN-code-'+expected+'-Windows-x64.msi')
        sha=asset['digest'].removeprefix('sha256:')
        assert len(sha)==64 and asset['size']>0
        verify_jars()
        profile=ROOT/('profile-'+args.stage+'-'+datetime.datetime.now(datetime.UTC).strftime('%Y%m%dT%H%M%S%f'))
        cmd=[JAVA,'-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts','-Dvncode.appdata.dir='+str(profile),'-cp',str(ROOT/'classes')+':'+str(ROOT/'app/*')+':'+str(ROOT/'app/lib/*'),'com.vncode.app.integration.update.Installed168UpdateProbe',str(AUDIT/('updater-'+args.stage+'.json')),status,expected,sha,str(asset['size']),str(ROOT/'app/VNcode-1.6.8.jar')]
        result=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
        (ROOT/('probe-'+args.stage+'.log')).write_text(result.stdout)
        assert result.returncode==0, 'Updater probe failed; inspect private probe log'
    finalize(args.stage)
if __name__=='__main__': main()
