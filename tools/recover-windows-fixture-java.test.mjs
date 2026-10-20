import {test} from 'node:test';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

test('fixture recovery preserves every passing method and requires the repaired class to execute successfully',()=>{
 const script=fileURLToPath(new URL('./recover-windows-fixture-java.py',import.meta.url));
 execFileSync('python',['-c',String.raw`
import importlib.util,sys,xml.etree.ElementTree as E
spec=importlib.util.spec_from_file_location('recovery',sys.argv[1]);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def xml(name,n,skip=0,context=False):
 r=E.Element('testsuite',name=name,tests=str(n+int(context)),failures='0',errors=str(int(context)),skipped=str(skip))
 for i in range(n):
  c=E.SubElement(r,'testcase',name='test'+str(i),classname=name)
  if i<skip:E.SubElement(c,'skipped')
 if context:
  c=E.SubElement(r,'testcase',name='',classname=name);e=E.SubElement(c,'error',message='Failed to close extension context');e.text='wcode_analytics.db being used by another process'
 return E.tostring(r)
old={m.FXML:xml(m.FXML,55,context=True),'Other':xml('Other',1690)}
for name in m.SKIP_CLASSES:old[name]=xml(name,1,skip=1)
fresh={m.FXML:xml(m.FXML,55),m.CLEANUP:xml(m.CLEANUP,1)}
merged,total,executed=m.reconcile(old,fresh);assert total==dict(tests=1748,failures=0,errors=0,skipped=2) and executed==56
def reject(data):
 try:m.reconcile(old,data)
 except AssertionError:return
 raise AssertionError('Unsafe recovery was accepted')
reject({m.CLEANUP:fresh[m.CLEANUP]})
reject({**fresh,m.FXML:xml(m.FXML,54)})
reject({**fresh,m.FXML:xml(m.FXML,55,context=True)})
reject({**fresh,m.CLEANUP:xml(m.CLEANUP,0)})
reject({**fresh,'UnreviewedNewClass':xml('UnreviewedNewClass',1)})
bad=dict(old);r=E.fromstring(bad['Other']);r.set('failures','1');E.SubElement(r.find('testcase'),'failure');bad['Other']=E.tostring(r)
try:m.reconcile(bad,fresh)
except AssertionError:pass
else:raise AssertionError('Original functional failure was hidden')
` ,script],{stdio:'pipe'});
});
