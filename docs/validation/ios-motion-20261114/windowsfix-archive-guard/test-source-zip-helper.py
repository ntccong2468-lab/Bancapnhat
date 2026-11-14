#!/usr/bin/env python3
"""Small positive/negative controls for the helper; never actual source ZIP acceptance."""
import importlib.util
import copy
import hashlib
import json
import pathlib
import stat
import subprocess
import tempfile
import unittest
import zipfile

HELPER = pathlib.Path(__file__).resolve().parent / 'verify-source-zip.py'
spec = importlib.util.spec_from_file_location('source_zip_helper', HELPER)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class SourceZipHelperControls(unittest.TestCase):
    def info(self, path='file.txt', size=3, mode=0):
        value = zipfile.ZipInfo(m.PREFIX + path)
        value.file_size = size
        value.external_attr = mode << 16
        return value

    def expected(self, path='file.txt', mode='100644', size=3):
        return {path: {'oid': 'a' * 40, 'mode': mode, 'kind': 'blob'}}, {'a' * 40: size}

    def check(self, values, path='file.txt', mode='100644', size=3):
        return m.validate_entries(values, *self.expected(path, mode, size))

    def test_safe_regular_entry(self):
        self.assertEqual(set(self.check([self.info()])[0]), {'file.txt'})

    def test_safe_git_timestamp_extra(self):
        value = self.info()
        value.extra = b'\x55\x54\x05\x00\x01\x00\x00\x00\x00'
        self.check([value])

    def test_rejects_traversal_and_ambiguous_paths(self):
        for name in ('../evil', '/absolute', 'C:/drive', 'a/../evil', 'a//b', 'a/./b',
                     'a\\b', 'file:secret', 'NUL.txt', 'foo. ', 'a\x00b'):
            with self.subTest(name=repr(name)), self.assertRaises(m.VerificationError):
                m.validate_member_name(m.PREFIX + name)

    def test_rejects_wrong_root_prefix(self):
        with self.assertRaises(m.VerificationError):
            m.validate_member_name('VN-code-old-source/file.txt')

    def test_rejects_truncated_nul_name(self):
        value = zipfile.ZipInfo(m.PREFIX + 'file.txt\x00hidden')
        value.file_size = 3
        self.assertNotEqual(value.orig_filename, value.filename)
        with self.assertRaises(m.VerificationError):
            self.check([value])

    def test_rejects_duplicate_members(self):
        with self.assertRaises(m.VerificationError):
            self.check([self.info(), self.info()])

    def test_rejects_case_collision_on_windows(self):
        expected = {'File.txt': {'oid': 'a' * 40, 'mode': '100644'},
                    'file.txt': {'oid': 'b' * 40, 'mode': '100644'}}
        with self.assertRaises(m.VerificationError):
            m.validate_entries([self.info('File.txt'), self.info('file.txt')], expected,
                               {'a' * 40: 3, 'b' * 40: 3})

    def test_rejects_untracked_file(self):
        with self.assertRaises(m.VerificationError):
            self.check([self.info('extra.txt')])

    def test_rejects_missing_file(self):
        with self.assertRaises(m.VerificationError):
            self.check([])

    def test_rejects_oversized_zip_metadata_before_decompression(self):
        with self.assertRaises(m.VerificationError):
            self.check([self.info(size=2 ** 40)])

    def test_rejects_generated_or_user_profile_paths(self):
        for path in ('.git/config', 'target/app.jar', 'node_modules/x', 'profiles/private.json',
                     'AppData/VNCode/settings.json', '.aws/credentials', '.env'):
            with self.subTest(path=path), self.assertRaises(m.VerificationError):
                m.validate_member_name(m.PREFIX + path)

    def test_rejects_unix_special_type(self):
        with self.assertRaises(m.VerificationError):
            self.check([self.info(mode=stat.S_IFIFO | 0o644)])

    def test_rejects_symlink_instead_of_regular_file(self):
        with self.assertRaises(m.VerificationError):
            self.check([self.info(mode=stat.S_IFLNK | 0o777)])

    def test_requires_executable_wrapper_bits(self):
        with self.assertRaises(m.VerificationError):
            self.check([self.info()], mode='100755')
        self.check([self.info(mode=stat.S_IFREG | 0o755)], mode='100755')

    def test_rejects_alternate_unicode_path_extra(self):
        value = self.info()
        value.extra = b'\x75\x70\x04\x00evil'
        with self.assertRaises(m.VerificationError):
            self.check([value])

    def test_rejects_non_git_per_entry_comment(self):
        value = self.info()
        value.comment = b'untracked metadata'
        with self.assertRaises(m.VerificationError):
            self.check([value])

    def test_crc_corruption_detected(self):
        with tempfile.TemporaryDirectory(prefix='zip-crc-control-') as directory:
            path = pathlib.Path(directory) / 'fixture.zip'
            payload = b'CRC control payload'
            with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as bundle:
                bundle.writestr(m.PREFIX + 'file.txt', payload)
            raw = bytearray(path.read_bytes())
            raw[raw.index(payload)] ^= 1
            path.write_bytes(raw)
            with zipfile.ZipFile(path) as bundle:
                self.assertIsNotNone(bundle.testzip())

    def test_old_504_runtime_tree_is_rejected(self):
        before = {f'src/main/keep-{i}': {'oid': 'a' * 40} for i in range(800)}
        old_copy = dict(list(before.items())[:504])
        with tempfile.TemporaryDirectory(prefix='preservation-control-') as directory:
            path = pathlib.Path(directory) / 'preservation.json'
            path.write_text(json.dumps({'kind': 'VN_CODE_169_TO_1610_SOURCE_PRESERVATION',
                                        'result': 'PASS', 'published169Source': m.BASE,
                                        'candidateCodeSource': m.CODE}))
            with self.assertRaises(m.VerificationError):
                m.check_preservation(None, before, old_copy, old_copy, path)

    def test_only_exact_reviewed_fixture_is_classified_as_synthetic(self):
        path, pattern = next(iter(m.KNOWN_FIXTURE))
        self.assertEqual(m.classify_credential(path, pattern, m.KNOWN_FIXTURE[(path, pattern)]),
                         'CONFIRMED_SYNTHETIC_DIAGNOSTIC_TEST_FIXTURE')
        self.assertEqual(m.classify_credential(path, pattern, '0' * 64),
                         'UNREVIEWED_POTENTIAL_CREDENTIAL_REQUIRES_REVIEW')

    def test_test_directory_is_not_a_blanket_credential_allowlist(self):
        self.assertEqual(m.classify_credential('src/test/java/SomeOtherTest.java', 'jwt_literal', '0' * 64),
                         'UNREVIEWED_POTENTIAL_CREDENTIAL_REQUIRES_REVIEW')

    def test_actual_small_git_archive_format_and_raw_crlf(self):
        with tempfile.TemporaryDirectory(prefix='git-source-format-control-') as directory:
            repo = pathlib.Path(directory) / 'repo'
            repo.mkdir()
            subprocess.run(['git', 'init', '-q', str(repo)], check=True)
            (repo / 'docs').mkdir()
            (repo / '.gitattributes').write_text('docs/** -text\n')
            (repo / 'mvnw').write_bytes(b'#!/bin/sh\nexit 0\n')
            (repo / 'mvnw').chmod(0o755)
            (repo / 'docs/proof.json').write_bytes(b'{\r\n  "fixture": true\r\n}\r\n')
            subprocess.run(['git', 'add', '.'], cwd=repo, check=True)
            subprocess.run(['git', '-c', 'user.name=ZIP helper test',
                            '-c', 'user.email=zip-test@example.invalid', 'commit', '-qm', 'fixture'], cwd=repo, check=True)
            ref = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
            archive = pathlib.Path(directory) / 'format.zip'
            subprocess.run(['git', 'archive', '--format=zip', '--prefix=' + m.PREFIX, '-o', str(archive), ref], cwd=repo, check=True)
            git = m.Git(repo)
            try:
                entries, _ = git.tree(ref)
                with zipfile.ZipFile(archive) as bundle:
                    files, _ = m.validate_entries(bundle.infolist(), entries, git.blob_sizes(entries))
                    self.assertIsNone(bundle.testzip())
                    self.assertEqual(bundle.comment, ref.encode())
                    for path, entry in entries.items():
                        self.assertEqual(bundle.read(files[path]), git.blob(entry['oid']))
            finally:
                git.close()


class ReviewedAdditionControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo=pathlib.Path('/workspace/vncode-ios-motion-20261114')
        cls.git=m.Git(cls.repo)
        cls.before,_=cls.git.tree(m.BASE)
        cls.code,_=cls.git.tree(m.CODE)
        cls.manifest=pathlib.Path(__file__).resolve().parent/'source-preservation.json'
        cls.added='src/main/java/com/vncode/app/features/gtinsync/OzonExactVariantIdentity.java'
        cls.baseline_path='src/main/java/com/vncode/app/Launcher.java'
    @classmethod
    def tearDownClass(cls):
        cls.git.close()
    def check(self,before=None,code=None,final=None,manifest=None,pin=m.PRESERVATION_SHA256):
        return m.check_preservation(self.git,before or self.before,code or self.code,final or self.code,manifest or self.manifest,pin)
    def test_actual_reviewed_new_matcher_addition_is_accepted(self):
        r=self.check()
        self.assertEqual(800,r['runtimeBefore']);self.assertEqual(805,r['runtimeAfter'])
        self.assertEqual(sorted([self.added,'src/main/java/com/vncode/app/shared/WorkspaceInteractionMotion.java','src/main/java/com/vncode/app/shared/WorkspaceBackdropMaterial.java','src/main/java/com/vncode/app/shared/WorkspaceRoundedControlClip.java','src/main/java/com/vncode/app/shared/WorkspaceDialogChrome.java']),[x['path'] for x in r['added']])
        self.assertEqual(756,r['unchanged']);self.assertEqual(44,len(r['modified']))
        self.assertTrue(r['baselinePathsRetained'])
    def test_unlisted_runtime_addition_is_rejected(self):
        code=copy.deepcopy(self.code);code['src/main/java/Unreviewed.java']=code[self.added]
        with self.assertRaisesRegex(m.VerificationError,'differ from the reviewed manifest'):
            self.check(code=code,final=code)
    def test_baseline_deletion_is_rejected_even_with_equal_total_count(self):
        code=copy.deepcopy(self.code);code['src/main/java/Replacement.java']=code.pop(self.baseline_path)
        with self.assertRaisesRegex(m.VerificationError,'baseline runtime file was removed'):
            self.check(code=code,final=code)
    def test_final_missing_new_matcher_is_rejected(self):
        final=copy.deepcopy(self.code);del final[self.added]
        with self.assertRaisesRegex(m.VerificationError,'after the tested code commit'):
            self.check(final=final)
    def test_final_tampered_new_matcher_is_rejected(self):
        final=copy.deepcopy(self.code);final[self.added]['oid']=self.code[self.baseline_path]['oid']
        with self.assertRaisesRegex(m.VerificationError,'after the tested code commit'):
            self.check(final=final)
    def test_added_matcher_blob_must_equal_reviewed_manifest(self):
        code=copy.deepcopy(self.code);code[self.added]['oid']=self.code[self.baseline_path]['oid']
        with self.assertRaisesRegex(m.VerificationError,'runtime blob inventory'):
            self.check(code=code,final=code)
    def test_final_runtime_mode_change_is_rejected(self):
        final=copy.deepcopy(self.code);final[self.added]['mode']='100755'
        with self.assertRaisesRegex(m.VerificationError,'after the tested code commit'):
            self.check(final=final)
    def test_final_test_change_is_rejected(self):
        final=copy.deepcopy(self.code);path=next(p for p in final if p.startswith('src/test/'))
        final[path]['oid']=self.code[self.baseline_path]['oid']
        with self.assertRaisesRegex(m.VerificationError,'tested source/tests'):
            self.check(final=final)
    def test_final_new_test_after_code_pin_is_rejected(self):
        final=copy.deepcopy(self.code);final['src/test/java/Untested.java']=final[self.baseline_path]
        with self.assertRaisesRegex(m.VerificationError,'added source/tests'):
            self.check(final=final)
    def test_final_new_maven_input_after_code_pin_is_rejected(self):
        final=copy.deepcopy(self.code);final['.mvn/unreviewed.config']=final[self.baseline_path]
        with self.assertRaisesRegex(m.VerificationError,'untested Maven build input'):
            self.check(final=final)
    def test_final_changed_pom_is_rejected(self):
        final=copy.deepcopy(self.code);final['pom.xml']['oid']=self.code[self.baseline_path]['oid']
        with self.assertRaisesRegex(m.VerificationError,'tested build input'):
            self.check(final=final)
    def test_reduced_504_tree_is_rejected_using_the_actual_pinned_manifest(self):
        code={p:e for p,e in self.code.items() if not p.startswith('src/main/')}
        code.update(dict(list((p,e) for p,e in self.code.items() if p.startswith('src/main/'))[:504]))
        with self.assertRaisesRegex(m.VerificationError,'baseline runtime file was removed'):
            self.check(code=code,final=code)
    def test_manifest_byte_tamper_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='archiveguard-manifest-') as d:
            p=pathlib.Path(d)/'changed.json';p.write_bytes(self.manifest.read_bytes()+b' ')
            with self.assertRaisesRegex(m.VerificationError,'reviewed SHA-256 pin'):
                self.check(manifest=p)
    def test_caller_cannot_repin_a_tampered_manifest(self):
        with tempfile.TemporaryDirectory(prefix='archiveguard-repin-') as d:
            p=pathlib.Path(d)/'changed.json';p.write_bytes(self.manifest.read_bytes()+b' ')
            with self.assertRaisesRegex(m.VerificationError,'Unreviewed source-preservation'):
                self.check(manifest=p,pin=hashlib.sha256(p.read_bytes()).hexdigest())
    def test_pinned_fixture_also_has_exact_invalid_es256_shape(self):
        data=(self.repo/'src/test/java/com/vncode/app/ui/StartupProgressTest.java').read_bytes()
        self.assertTrue(m.reviewed_fixture_shape(data))
        self.assertFalse(m.reviewed_fixture_shape(b'not a token'))
        self.assertFalse(m.reviewed_fixture_shape(data+data))


class UiPredecessorControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.git=m.Git(pathlib.Path('/workspace/vncode-ios-motion-20261114'))
        cls.code,_=cls.git.tree(m.CODE)
        cls.previous,_=cls.git.tree(m.PREVIOUS_CODE)
    @classmethod
    def tearDownClass(cls): cls.git.close()
    def test_actual_ui_changes_keep_prior_business_runtime(self):
        r=m.check_ui_predecessor(self.git,self.code,self.code)
        self.assertEqual(801,r['previousRuntimeFiles']);self.assertEqual(805,r['runtimeFiles'])
        self.assertEqual(773,r['unchangedPriorRuntimeFiles'])
        self.assertEqual(28,len(r['modifiedUiPaths']));self.assertEqual(4,len(r['addedUiPaths']))
    def test_unreviewed_business_change_in_ui_phase_is_rejected(self):
        code=copy.deepcopy(self.code)
        path='src/main/java/com/vncode/app/integration/znack/ZnackPurchaseCoordinator.java'
        code[path]['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'non-UI business/config/model/integration runtime'):
            m.check_ui_predecessor(self.git,code,code)
    def test_prior_business_file_deletion_is_rejected(self):
        code=copy.deepcopy(self.code)
        del code['src/main/java/com/vncode/app/integration/znack/ZnackPurchaseCoordinator.java']
        with self.assertRaisesRegex(m.VerificationError,'lost a runtime file'):
            m.check_ui_predecessor(self.git,code,code)
    def test_unreviewed_extra_ui_file_is_rejected(self):
        code=copy.deepcopy(self.code)
        code['src/main/java/com/vncode/app/shared/UnreviewedUiHelper.java']=code['src/main/java/com/vncode/app/Launcher.java']
        with self.assertRaisesRegex(m.VerificationError,'outside the reviewed UI path sets'):
            m.check_ui_predecessor(self.git,code,code)
    def test_ui_pom_change_is_rejected(self):
        code=copy.deepcopy(self.code);code['pom.xml']['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'pre-UI POM'):
            m.check_ui_predecessor(self.git,code,code)
    def test_unapplied_reviewed_ui_change_is_rejected(self):
        code=copy.deepcopy(self.code)
        path='src/main/java/com/vncode/app/shared/MotionBoundary.java';code[path]=copy.deepcopy(self.previous[path])
        with self.assertRaisesRegex(m.VerificationError,'outside the reviewed UI path sets'):
            m.check_ui_predecessor(self.git,code,code)


class CorrectedTestPinControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.git=m.Git(pathlib.Path('/workspace/vncode-ios-motion-20261114'))
        cls.code,_=cls.git.tree(m.CODE)
    @classmethod
    def tearDownClass(cls): cls.git.close()
    def test_actual_test_only_successor_has_exact_corrected_test(self):
        r=m.check_test_only_successor(self.git,self.code,self.code)
        self.assertTrue(r['clipCheckpointProductionRuntimeByteExact'])
        self.assertEqual(m.CORRECTED_TEST_SHA256,r['correctedTestSha256'])
    def test_corrected_control_clip_test_cannot_change_after_code_pin(self):
        final=copy.deepcopy(self.code)
        final[m.CORRECTED_TEST]['oid']=self.code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'changed or removed the corrected test'):
            m.check_test_only_successor(self.git,self.code,final)


class FocusSuccessorControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.git=m.Git(pathlib.Path('/workspace/vncode-ios-motion-20261114'))
        cls.code,_=cls.git.tree(m.FOCUS_CHECKPOINT)
    @classmethod
    def tearDownClass(cls): cls.git.close()
    def test_actual_minimal_focus_successor_is_accepted(self):
        r=m.check_focus_successor(self.git,self.code,self.code)
        self.assertTrue(r['exactInnerShadowRemoval']);self.assertTrue(r['oldMaterialTestAssertionsRetained'])
    def test_unreviewed_focus_code_path_is_rejected(self):
        code=copy.deepcopy(self.code);p='src/main/java/com/vncode/app/Launcher.java';code[p]['oid']=code[m.FOCUS_CSS]['oid']
        with self.assertRaisesRegex(m.VerificationError,'outside the reviewed CSS'):
            m.check_focus_successor(self.git,code,code)
    def test_focus_css_tamper_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.FOCUS_CSS]['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'CSS SHA-256'):
            m.check_focus_successor(self.git,code,code)
    def test_focus_test_tamper_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.FOCUS_TEST]['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'regression test SHA-256'):
            m.check_focus_successor(self.git,code,code)
    def test_focus_input_mode_change_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.FOCUS_CSS]['mode']='100755'
        with self.assertRaisesRegex(m.VerificationError,'input mode'):
            m.check_focus_successor(self.git,code,code)
    def test_final_focus_test_change_is_rejected(self):
        final=copy.deepcopy(self.code);final[m.FOCUS_TEST]['oid']=self.code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'changed or removed a reviewed focus input'):
            m.check_focus_successor(self.git,self.code,final)


class LogicalAttachmentControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.git=m.Git(pathlib.Path('/workspace/vncode-ios-motion-20261114'))
        cls.code,_=cls.git.tree(m.LOGICAL_CHECKPOINT)
        cls.previous,_=cls.git.tree(m.DYNAMIC_PREDECESSOR)
    @classmethod
    def tearDownClass(cls): cls.git.close()
    def test_actual_minimal_attachment_successor_is_accepted(self):
        r=m.check_dynamic_successor(self.git,self.code,self.code)
        self.assertTrue(r['exactNullSceneRetentionChange']);self.assertTrue(r['oldFiveMaterialTestsByteRetained'])
        self.assertEqual(49,r['additiveNativeRegressionLines']);self.assertEqual(7,r['nativeRegressionTestsAfter'])
    def test_attachment_unreviewed_path_is_rejected(self):
        code=copy.deepcopy(self.code);p='src/main/java/com/vncode/app/Launcher.java';code[p]['oid']=code[m.DYNAMIC_HELPER]['oid']
        with self.assertRaisesRegex(m.VerificationError,'outside the reviewed helper'):
            m.check_dynamic_successor(self.git,code,code)
    def test_old_helper_without_attachment_fix_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.DYNAMIC_HELPER]=self.previous[m.DYNAMIC_HELPER]
        with self.assertRaisesRegex(m.VerificationError,'outside the reviewed helper'):
            m.check_dynamic_successor(self.git,code,code)
    def test_old_test_without_two_regressions_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.DYNAMIC_TEST]=self.previous[m.DYNAMIC_TEST]
        with self.assertRaisesRegex(m.VerificationError,'outside the reviewed helper'):
            m.check_dynamic_successor(self.git,code,code)
    def test_attachment_mode_change_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.DYNAMIC_HELPER]['mode']='100755'
        with self.assertRaisesRegex(m.VerificationError,'input mode'):
            m.check_dynamic_successor(self.git,code,code)
    def test_final_attachment_helper_change_is_rejected(self):
        final=copy.deepcopy(self.code);final[m.DYNAMIC_HELPER]['oid']=self.code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'changed or removed a reviewed attachment input'):
            m.check_dynamic_successor(self.git,self.code,final)
    def test_final_attachment_native_test_change_is_rejected(self):
        final=copy.deepcopy(self.code);final[m.DYNAMIC_TEST]['oid']=self.code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'changed or removed a reviewed attachment input'):
            m.check_dynamic_successor(self.git,self.code,final)
    def test_intermediate_unreviewed_document_is_rejected(self):
        delegate=self.git
        class ChangedIntermediate:
            def __getattr__(self,name):return getattr(delegate,name)
            def tree(self,ref):
                tree,dirs=delegate.tree(ref)
                if ref==m.DYNAMIC_PREDECESSOR:
                    tree=copy.deepcopy(tree);tree[m.DYNAMIC_DOC]['oid']=self.code['src/main/java/com/vncode/app/Launcher.java']['oid']
                return tree,dirs
        # Bind the source blob once; only the guard fixture tree is varied.
        ChangedIntermediate.code=self.code
        with self.assertRaisesRegex(m.VerificationError,'pinned research documentation correction'):
            m.check_dynamic_successor(ChangedIntermediate(),self.code,self.code)


class WindowsSuccessorControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.git=m.Git(pathlib.Path('/workspace/vncode-ios-motion-20261114'));cls.code,_=cls.git.tree(m.WINDOWS_CHECKPOINT)
        cls.old,_=cls.git.tree(m.LOGICAL_CHECKPOINT)
    @classmethod
    def tearDownClass(cls):cls.git.close()
    def test_actual_reviewed_windows_successor_is_accepted(self):
        r=m.check_windows_successor(self.git,self.code,self.code)
        self.assertTrue(r['callerOwnershipMarkerBooleanOnly']);self.assertTrue(r['previous12BackdropTestsByteRetained'])
        self.assertTrue(r['headerAdjacencyThresholdsUnchanged']);self.assertTrue(r['contrast45AndFocus3ThresholdsUnchanged'])
        self.assertTrue(r['unfilteredCleanVerifyRetained']);self.assertTrue(r['mainTestPomByteExactToNativeCheckpoint'])
    def test_unreviewed_windows_source_change_is_rejected(self):
        code=copy.deepcopy(self.code);p='src/main/java/com/vncode/app/Launcher.java';code[p]['oid']=code[m.DYNAMIC_HELPER]['oid']
        with self.assertRaisesRegex(m.VerificationError,'outside the seven reviewed inputs'):m.check_windows_successor(self.git,code,code)
    def test_tampered_windows_caller_helper_is_rejected(self):
        code=copy.deepcopy(self.code);code['src/main/java/com/vncode/app/shared/WorkspaceBackdropMaterial.java']['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Windows input SHA-256 differs'):m.check_windows_successor(self.git,code,code)
    def test_tampered_windows_caller_test_is_rejected(self):
        code=copy.deepcopy(self.code);code['src/test/java/com/vncode/app/shared/WorkspaceBackdropMaterialTest.java']['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Windows input SHA-256 differs'):m.check_windows_successor(self.git,code,code)
    def test_tampered_windows_render_fixture_is_rejected(self):
        code=copy.deepcopy(self.code);code['src/test/java/com/vncode/app/shared/WorkspaceMaterialRenderingTest.java']['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Windows input SHA-256 differs'):m.check_windows_successor(self.git,code,code)
    def test_tampered_windows_header_fixture_is_rejected(self):
        code=copy.deepcopy(self.code);code['src/test/java/com/vncode/app/ui/workspace/NativeGlassEvidenceTest.java']['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Windows input SHA-256 differs'):m.check_windows_successor(self.git,code,code)
    def test_tampered_windows_ci_workflow_is_rejected(self):
        code=copy.deepcopy(self.code);code['.github/workflows/publish-vncode-baseline.yml']['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Windows input SHA-256 differs'):m.check_windows_successor(self.git,code,code)
    def test_tampered_windows_spi_diagnostic_is_rejected(self):
        code=copy.deepcopy(self.code);code['tools/diagnostics/windows-motion-environment.ps1']['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Windows input SHA-256 differs'):m.check_windows_successor(self.git,code,code)
    def test_tampered_windows_java_diagnostic_is_rejected(self):
        code=copy.deepcopy(self.code);code['tools/diagnostics/WindowsMotionPreferenceProbe.java']['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Windows input SHA-256 differs'):m.check_windows_successor(self.git,code,code)
    def test_windows_input_mode_change_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.DYNAMIC_HELPER]['mode']='100755'
        with self.assertRaisesRegex(m.VerificationError,'mode/type changed'):m.check_windows_successor(self.git,code,code)
    def test_final_windows_input_cannot_change_after_pin(self):
        final=copy.deepcopy(self.code);final[m.DYNAMIC_HELPER]['oid']=final['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Final archive changed a reviewed Windows input'):m.check_windows_successor(self.git,self.code,final)


class EmbeddedFrameControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.git=m.Git(pathlib.Path('/workspace/vncode-ios-motion-20261114'));cls.code,_=cls.git.tree(m.CODE)
        cls.old,_=cls.git.tree(m.WINDOWS_CHECKPOINT)
    @classmethod
    def tearDownClass(cls):cls.git.close()
    def test_actual_embedded_frame_successor_is_accepted(self):
        r=m.check_embedded_successor(self.git,self.code,self.code)
        self.assertTrue(r['exactScopedBackgroundColorAndInsetsAddition']);self.assertTrue(r['oldFiveMaterialTestsAndAllHelpersByteRetained'])
        self.assertEqual(38,r['additiveNativeTestLines']);self.assertEqual(6,r['nativeTestsAfter']);self.assertEqual(.015,r['pixelDistanceThreshold'])
    def test_embedded_unreviewed_runtime_change_is_rejected(self):
        code=copy.deepcopy(self.code);p='src/main/java/com/vncode/app/Launcher.java';code[p]['oid']=code[m.EMBEDDED_CSS]['oid']
        with self.assertRaisesRegex(m.VerificationError,'more than the reviewed CSS'):m.check_embedded_successor(self.git,code,code)
    def test_missing_embedded_css_fix_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.EMBEDDED_CSS]=self.old[m.EMBEDDED_CSS]
        with self.assertRaisesRegex(m.VerificationError,'more than the reviewed CSS'):m.check_embedded_successor(self.git,code,code)
    def test_missing_embedded_regression_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.EMBEDDED_TEST]=self.old[m.EMBEDDED_TEST]
        with self.assertRaisesRegex(m.VerificationError,'more than the reviewed CSS'):m.check_embedded_successor(self.git,code,code)
    def test_tampered_embedded_css_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.EMBEDDED_CSS]['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Embedded-frame input SHA-256 differs'):m.check_embedded_successor(self.git,code,code)
    def test_tampered_embedded_test_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.EMBEDDED_TEST]['oid']=code['src/main/java/com/vncode/app/Launcher.java']['oid']
        with self.assertRaisesRegex(m.VerificationError,'Embedded-frame input SHA-256 differs'):m.check_embedded_successor(self.git,code,code)
    def test_embedded_mode_change_is_rejected(self):
        code=copy.deepcopy(self.code);code[m.EMBEDDED_CSS]['mode']='100755'
        with self.assertRaisesRegex(m.VerificationError,'input mode/type changed'):m.check_embedded_successor(self.git,code,code)
    def test_final_embedded_css_change_is_rejected(self):
        final=copy.deepcopy(self.code);final[m.EMBEDDED_CSS]=self.old[m.EMBEDDED_CSS]
        with self.assertRaisesRegex(m.VerificationError,'Final archive changed a reviewed embedded-frame input'):m.check_embedded_successor(self.git,self.code,final)
    def test_final_embedded_test_change_is_rejected(self):
        final=copy.deepcopy(self.code);final[m.EMBEDDED_TEST]=self.old[m.EMBEDDED_TEST]
        with self.assertRaisesRegex(m.VerificationError,'Final archive changed a reviewed embedded-frame input'):m.check_embedded_successor(self.git,self.code,final)


if __name__ == '__main__':
    unittest.main(verbosity=2)
