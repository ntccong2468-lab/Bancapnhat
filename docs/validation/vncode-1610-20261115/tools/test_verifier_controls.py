#!/usr/bin/env python3
"""Corrupt local/in-memory fixtures, never actual evidence or application data."""
import collections,copy,io,json,pathlib,stat,tempfile,unittest,warnings,xml.etree.ElementTree as ET,zipfile
import verify_package as w
v=w.v;ROOT=pathlib.Path('/workspace/release-1610-20261115/build/assets');REPO=pathlib.Path('/workspace/vncode-ios-motion-20261114')
class Controls(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tree=v.source_tree(REPO);cls.tmp=tempfile.TemporaryDirectory(dir=w.HERE,prefix='.controls-');cls.home=pathlib.Path(cls.tmp.name);cls.info=v.parse((ROOT/'build-info.json').read_bytes())
  with zipfile.ZipFile(ROOT/'VN-code-1.6.10-verification.zip') as z:cls.evidence={i.filename:z.read(i) for i in z.infolist() if not i.is_dir()}
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 def rejected(self,fn,part=None):
  with self.assertRaises((v.VerificationError,KeyError,ValueError,zipfile.BadZipFile)) as cm:fn()
  if part:self.assertIn(part,str(cm.exception))
 def small_zip(self,rows):
  b=io.BytesIO()
  with warnings.catch_warnings():
   warnings.simplefilter('ignore',UserWarning)
   with zipfile.ZipFile(b,'w') as z:
    for name,data in rows:z.writestr(name,data)
  b.seek(0);return zipfile.ZipFile(b)
 def test_duplicate_json(self):self.rejected(lambda:v.parse(b'{"x":1,"x":2}'),'duplicate-json')
 def test_checksum_duplicate(self):self.rejected(lambda:v.checksums((('a'*64+'  a\n')*2).encode()),'duplicate-checksum')
 def test_checksum_path(self):self.rejected(lambda:v.checksums(('a'*64+'  ../evil\n').encode()),'invalid-checksum')
 def test_checksum_digest(self):self.rejected(lambda:v.checksums(b'ZZ  a\n'),'invalid-checksum')
 def test_zip_traversal(self):
  with self.small_zip([('../evil',b'x')]) as z:self.rejected(lambda:v.members(z),'unsafe-zip')
 def test_zip_windows(self):
  with self.small_zip([('C:\\evil',b'x')]) as z:self.rejected(lambda:v.members(z),'unsafe-zip')
 def test_zip_absolute(self):
  with self.small_zip([('/evil',b'x')]) as z:self.rejected(lambda:v.members(z),'unsafe-zip')
 def test_zip_duplicate(self):
  with self.small_zip([('a',b'x'),('a',b'y')]) as z:self.rejected(lambda:v.members(z),'duplicate-zip')
 def test_zip_symlink(self):
  i=zipfile.ZipInfo('a');i.create_system=3;i.external_attr=(stat.S_IFLNK|0o777)<<16
  with self.small_zip([(i,b'target')]) as z:self.rejected(lambda:v.members(z),'symlink-zip')
 def test_binary_change(self):self.rejected(lambda:v.equiv(b'\x80a',b'\x80b','b'),'binary-mismatch')
 def test_text_change(self):self.rejected(lambda:v.equiv(b'a\n',b'b\n','t'),'text-mismatch')
 def test_text_nul_not_line_endings(self):self.rejected(lambda:v.equiv(b'a\0\r\n',b'a\0\n','t'),'text-mismatch')
 def test_valid_crlf(self):self.assertEqual(v.equiv(b'a\r\n',b'a\n','t'),'crlf-lf-only')
 def test_exact_type(self):self.rejected(lambda:v.check_fields({'x':True},{'x':1},'x'),'x:x')
 def source_control(self,mode):
  directory=self.home/('source-'+mode);directory.mkdir();dst=directory/'VN-code-1.6.10-source.zip'
  prefix='VN-code-1.6.10-source/'
  with zipfile.ZipFile(ROOT/'VN-code-1.6.10-source.zip') as old,zipfile.ZipFile(dst,'w',compression=zipfile.ZIP_STORED) as z:
   for info in old.infolist():
    n=info.filename
    if mode=='missing' and n==prefix+'pom.xml':continue
    if mode=='prefix':n=n.replace(prefix,'wrong-prefix/',1)
    b=old.read(info)
    if mode=='content' and n==prefix+'src/main/java/com/vncode/app/BuildConfig.java':b+=b'\n// unreviewed change\n'
    z.writestr(n,b)
   if mode=='extra':z.writestr(prefix+'src/main/resources/unreviewed.txt',b'bad')
  self.rejected(lambda:v.verify_source(REPO,directory,self.tree),{'missing':'file-set','extra':'file-set','prefix':'prefix','content':'text-mismatch'}[mode])
  dst.unlink();directory.rmdir()
 def test_actual_source_content_corrupt(self):self.source_control('content')
 def test_actual_source_file_missing(self):self.source_control('missing')
 def test_actual_source_file_extra(self):self.source_control('extra')
 def test_actual_source_prefix_wrong(self):self.source_control('prefix')
 def junit_control(self,kind):
  fs=dict(self.evidence);info=copy.deepcopy(self.info);replacement={}
  for p,b in fs.items():
   if p.endswith('.xml') and pathlib.PurePosixPath(p).name.startswith('TEST-'):replacement[p]=ET.fromstring(b)
  if kind=='stress':
   for s in replacement.values():
    for c in s.findall('testcase'):
     if c.get('name')==v.STRESS[0]:c.set('name','renamedMissingStress')
  elif kind=='skip_identity':
   skipped=next((s,c) for s in replacement.values() for c in s.findall('testcase') if c.find('skipped') is not None)
   skipped[1].set('name','unexpectedOSskip')
  elif kind=='legacy':
   name=next(iter(v.CERT_SUITES));p=next(p for p,s in replacement.items() if s.get('name')==name);replacement[p].set('name','unexpected.suite')
  elif kind=='new':
   name='com.vncode.app.shared.WorkspaceAllSurfaceGlassTest';p=next(p for p,s in replacement.items() if s.get('name')==name);replacement[p].set('name','unexpected.suite')
  elif kind=='ui_case':
   for s in replacement.values():
    for c in s.findall('testcase'):
     if c.get('name')=='realFxmlTablesInsideAWorkingSheetHaveNoSeparateRectangularPaintedFrame':c.set('name','missingEdgeRegression')
  elif kind=='failure':
   s=next(iter(replacement.values()));c=s.findall('testcase')[0];ET.SubElement(c,'failure');s.set('failures',str(int(s.get('failures','0'))+1))
  for p,s in replacement.items():fs[p]=ET.tostring(s)
  directory=self.home/('junit-'+kind);directory.mkdir()
  for name in ('native-smoke.json','gs1-smoke.json','side-by-side-smoke.json','upgrade-baseline-smoke.json','previous-baseline.json'):(directory/name).write_bytes((ROOT/name).read_bytes())
  with zipfile.ZipFile(directory/'VN-code-1.6.10-verification.zip','w',compression=zipfile.ZIP_DEFLATED) as z:
   for p,b in fs.items():z.writestr(p,b)
  if kind in ('new','ui_case'):fn=lambda:w.extended_junit(directory,REPO)
  else:fn=lambda:v.verify_junit(directory,info,4700,585)
  self.rejected(fn,{'stress':'stress-fixtures','skip_identity':'skip-identities','legacy':'certificate-suite','new':'new-suite','ui_case':'regression-not-passed','failure':'expected-totals'}[kind])
 def test_actual_junit_stress_missing(self):self.junit_control('stress')
 def test_actual_junit_skip_identity_wrong(self):self.junit_control('skip_identity')
 def test_actual_junit_legacy_suite_missing(self):self.junit_control('legacy')
 def test_actual_junit_new_suite_missing(self):self.junit_control('new')
 def test_actual_junit_ui_case_missing(self):self.junit_control('ui_case')
 def test_actual_junit_failure(self):self.junit_control('failure')
 def test_actual_pe_msi_wrong_digest(self):
  self.rejected(lambda:v.verify_embedded_msi(ROOT/'VN-code-1.6.10-Windows-x64.exe',(ROOT/'VN-code-1.6.10-Windows-x64.msi').stat().st_size,'0'*64),'embedded-msi-byte-mismatch')
 def test_actual_runtime_map_corrupt(self):
  p=self.home/'wrong-preservation.json';d=json.loads((REPO/'docs/validation/ios-motion-20261114/source-preservation.json').read_text());k=next(iter(d['runtimeBlobs']));d['runtimeBlobs'][k]='0'*40;p.write_text(json.dumps(d));self.rejected(lambda:w.inclusion(REPO,self.tree,p),'manifest-blob')
 def test_actual_business_preservation_corrupt(self):
  p=self.home/'wrong-business.json';d=json.loads((REPO/'docs/validation/ios-motion-20261114/source-preservation.json').read_text());d['previousSourceDelta']['unchanged']=0;p.write_text(json.dumps(d));self.rejected(lambda:w.inclusion(REPO,self.tree,p),'business-checkpoint')
 def test_actual_main_class_absent(self):
  directory=self.home/'jar-missing';directory.mkdir();dst=directory/'VN-code-1.6.10-Windows-x64.zip';jp='VN code/app/VNcode-1.6.10.jar'
  with zipfile.ZipFile(ROOT/'VN-code-1.6.10-Windows-x64.zip') as z:
   jb=io.BytesIO()
   with zipfile.ZipFile(io.BytesIO(z.read(jp))) as j,zipfile.ZipFile(jb,'w',compression=zipfile.ZIP_DEFLATED) as out:
    for i in j.infolist():
     if i.filename!='com/vncode/app/BuildConfig.class':out.writestr(i,j.read(i))
   with zipfile.ZipFile(dst,'w') as out:out.writestr(jp,jb.getvalue())
  self.rejected(lambda:w.whole_jar(directory,REPO,self.tree),'main-source-class-missing')
if __name__=='__main__':
 suite=unittest.defaultTestLoader.loadTestsFromTestCase(Controls);result=unittest.TextTestRunner(verbosity=2).run(suite)
 receipt={'result':'PASS' if result.wasSuccessful() else 'FAIL','testsRun':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped),'helperSha256':v.filehash(pathlib.Path(__file__)),'scope':'Corrupt copies/synthetic malformed inputs only; actual build assets and app source never changed.'};(w.HERE/'verifier-controls.json').write_text(json.dumps(receipt,indent=2)+'\n');raise SystemExit(0 if result.wasSuccessful() else 1)
