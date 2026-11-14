import pathlib,json,subprocess,os,sys
p=pathlib.Path('/tmp/vncode-ozon-exact-20261114');r=json.loads((p/'related-selection.json').read_text())
cmd=['xvfb-run','-a','/workspace/toolchains/mvn-vncode','-B','-Dtest='+','.join(r['classSelectors']),'clean','verify']
env=os.environ.copy();env['PATH']='/workspace/toolchains/xvfb-current/root/usr/bin:'+env['PATH']
with (p/'related-clean-verify.log').open('w') as f:
 result=subprocess.run(cmd,cwd='/workspace/vncode-ozon-exact-gtin-20261114',env=env,stdout=f,stderr=subprocess.STDOUT)
(p/'related-exit.json').write_text(json.dumps({'exitCode':result.returncode,'sourceCommit':r['sourceCommit']},indent=2)+'\n')
sys.exit(result.returncode)
