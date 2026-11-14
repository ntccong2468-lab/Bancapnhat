#!/usr/bin/env python3
"""Independent final-docs-only gate; read Git objects, never mutate source or Git."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess,sys
CODE='b74b5edfd46636e8779c94f467eef01c03c50a14'
ROOT_DOCS={'DESIGN.md','PRODUCT.md','SOURCE-1.6.10.md'}
class GateError(Exception): pass
def require(value,reason):
    if not value: raise GateError(reason)
def allowed(path): return path.startswith('docs/') or path in ROOT_DOCS
def check_trees(before,after):
    removed=sorted(set(before)-set(after))
    require(not removed,'Final docs commit removed tracked paths')
    changed=sorted(p for p in set(before)|set(after) if before.get(p)!=after.get(p))
    require(all(allowed(p) for p in changed),'Final docs commit changed a path outside explicit documentation scope')
    require(all(after[p]['kind']=='blob' and after[p]['mode']=='100644' for p in changed),
            'Final documentation change is executable, symlink, submodule or unsupported type')
    require(all(before[p]['mode']==after[p]['mode'] for p in changed if p in before),
            'Final documentation changed a prior file mode')
    return changed
def git(repo,*args):
    p=subprocess.run(['git','--no-replace-objects',*args],cwd=repo,capture_output=True)
    require(p.returncode==0,'Required read-only Git operation failed: '+args[0])
    return p.stdout
def commit(repo,ref):
    require(re.fullmatch('[a-f0-9]{40}',ref) is not None,'An exact full40-character commit is required')
    require(git(repo,'rev-parse','--verify',ref+'^{commit}').decode().strip()==ref,'Commit did not resolve exactly')
    return ref
def tree(repo,ref):
    out={}
    for row in git(repo,'ls-tree','-r','-z','--full-tree',ref).split(b'\0'):
        if not row:continue
        meta,path=row.split(b'\t',1);mode,kind,oid=meta.decode().split(' ')
        out[path.decode()]={'mode':mode,'kind':kind,'oid':oid}
    return out
def run(args):
    repo=args.repo.resolve();code=commit(repo,CODE);final=commit(repo,args.final_ref)
    git(repo,'merge-base','--is-ancestor',code,final)
    before,after=tree(repo,code),tree(repo,final);changed=check_trees(before,after)
    return {'kind':'VN_CODE_1610_FINAL_DOCS_ONLY_DIFF_GATE','status':'PASS','sourceCommit':code,'finalCommit':final,
            'checkedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'allowedRootDocumentationPaths':sorted(ROOT_DOCS),'allowedDirectory':'docs/',
            'changedPaths':[{'path':p,'before':before.get(p),'after':after[p]} for p in changed],
            'changedPathCount':len(changed),'removed':[],
            'allSourceTestsBuildToolsWorkflowsWebsiteOutsideDocsByteExact':True,
            'noChangedModesOrNewExecutableSymlinkGitlink':True,
            'scope':'Read-only final Git tree diff. Separate actual ZIP verification and functional receipts are required.',
            'helperSha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=pathlib.Path,required=True);p.add_argument('--final-ref',required=True);p.add_argument('--receipt',type=pathlib.Path,required=True);args=p.parse_args()
    require(not args.receipt.exists(),'Refuse to overwrite a previous receipt')
    require(not args.receipt.resolve().is_relative_to(args.repo.resolve()),'Receipt must be outside source repository')
    require(args.receipt.resolve()!=pathlib.Path(__file__).resolve(),'Receipt would overwrite helper')
    try:result=run(args);code=0
    except Exception as e:result={'kind':'VN_CODE_1610_FINAL_DOCS_ONLY_DIFF_GATE','status':'FAIL','sourceCommit':CODE,'requestedFinalCommit':args.final_ref,'reason':str(e),'exceptionType':type(e).__name__};code=1
    args.receipt.parent.mkdir(parents=True,exist_ok=True);args.receipt.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'status':result['status'],'receipt':str(args.receipt),'reason':result.get('reason','')}));return code
if __name__=='__main__':
    try:sys.exit(main())
    except GateError as e:print(str(e),file=sys.stderr);sys.exit(2)
