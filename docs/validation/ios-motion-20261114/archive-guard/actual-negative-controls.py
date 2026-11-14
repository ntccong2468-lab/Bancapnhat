import pathlib,zipfile,subprocess,json,datetime
ROOT=pathlib.Path('/tmp/vncode-ios-floating-20261114/archive-guard-dynamic')
REPO=pathlib.Path('/workspace/vncode-ios-motion-20261114')
CODE='43b82cca683b29e783a33df995afd675a76aa0dd'
PREFIX='VN-code-1.6.10-source-floating-glass/'
targets={
 'tampered-motion':('src/main/java/com/vncode/app/shared/WorkspaceInteractionMotion.java',b'WorkspaceInteractionMotion',b'XorkspaceInteractionMotion'),
 'tampered-backdrop':('src/main/java/com/vncode/app/shared/WorkspaceBackdropMaterial.java',b'WorkspaceBackdropMaterial',b'XorkspaceBackdropMaterial'),
 'tampered-chrome':('src/main/java/com/vncode/app/shared/WorkspaceDialogChrome.java',b'WorkspaceDialogChrome',b'XorkspaceDialogChrome'),
 'tampered-clip':('src/main/java/com/vncode/app/shared/WorkspaceRoundedControlClip.java',b'WorkspaceRoundedControlClip',b'XorkspaceRoundedControlClip'),
 'tampered-focus-css':('src/main/resources/com/vncode/app/styles/workspace-controls.css',b'.spinner:focused',b'.spinnxr:focused'),
 'tampered-dynamic-test':('src/test/java/com/vncode/app/shared/WorkspaceAllSurfaceGlassTest.java',b'reducedNavigationMaterializesNewViewport',b'xeducedNavigationMaterializesNewViewport'),
 'tampered-focus-test':('src/test/java/com/vncode/app/shared/WorkspaceMaterialRenderingTest.java',b'focusedShopSelectorKeepsNativeContrast',b'xocusedShopSelectorKeepsNativeContrast'),
 'tampered-corrected-test':('src/test/java/com/vncode/app/shared/WorkspaceRoundedControlClipTest.java',b'WorkspaceRoundedControlClipTest',b'XorkspaceRoundedControlClipTest'),
}
baseline=PREFIX+'src/main/java/com/vncode/app/integration/znack/ZnackPurchaseCoordinator.java'
rows=[]
for kind in list(targets)+['untracked-file','missing-baseline']:
 path=ROOT/('actual-negative-'+kind+'.zip');assert not path.exists()
 changed_count=0
 with zipfile.ZipFile(ROOT/'code-control.zip') as source,zipfile.ZipFile(path,'w') as target:
  target.comment=source.comment
  for info in source.infolist():
   if kind=='missing-baseline' and info.filename==baseline:changed_count+=1;continue
   data=source.read(info)
   if kind in targets and info.filename==PREFIX+targets[kind][0]:
    changed=data.replace(targets[kind][1],targets[kind][2],1)
    assert len(changed)==len(data) and changed!=data
    data=changed;changed_count+=1
   target.writestr(info,data)
  if kind=='untracked-file':target.writestr(PREFIX+'UNREVIEWED.txt',b'untracked control');changed_count+=1
 assert changed_count==1,(kind,changed_count)
 receipt=ROOT/('actual-negative-'+kind+'-receipt.json');assert not receipt.exists()
 cmd=['python3',str(ROOT/'verify-source-zip.py'),'--repo',str(REPO),'--zip',str(path),'--ref',CODE,'--code-ref',CODE,'--preservation',str(ROOT/'source-preservation.json'),'--receipt',str(receipt)]
 run=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 output=json.loads(receipt.read_text());assert run.returncode==1 and output['result']=='FAIL',(kind,run.returncode,output)
 expected=('ZIP bytes differ from the pinned Git blob:' if kind in targets else ('not tracked at the final commit' if kind=='untracked-file' else 'missing one or more final-commit tracked source files'))
 assert expected in output['reason'],(kind,output['reason'])
 rows.append({'control':kind,'exitCode':run.returncode,'result':output['result'],'reason':output['reason'],'receipt':receipt.name})
result={'kind':'ARCHIVE_GUARD_ACTUAL_NEGATIVE_CONTROLS','status':'PASS','sourceCommit':CODE,'createdAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'controls':rows,'notFinalSourceZipAcceptance':True,'noMavenAppOrRemoteExecution':True}
(ROOT/'actual-negative-controls-result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'status':'PASS','actualNegativeControls':len(rows),'notFinalSourceAcceptance':True}))
