"""Synthetic design experiment, not production code or a cryptographic implementation."""
import base64
import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

# The marker intentionally advertises that this codec is NOT encryption.
class FakeCodec:
    def protect(self, s):
        if s == 'SIMULATE_CODEC_FAILURE':
            raise ValueError('synthetic codec error')
        return 'INSECURE_TEST_ONLY:' + base64.b64encode(s.encode()).decode()
    def reveal(self, s):
        if not s.startswith('INSECURE_TEST_ONLY:'):
            raise ValueError('unrecognized synthetic ciphertext')
        return base64.b64decode(s.removeprefix('INSECURE_TEST_ONLY:'), validate=True).decode()

class ChangedDuringPreparation(Exception): pass

def fingerprint(shop_id, marketplace, client_id, secret):
    # This test-local identity is a proxy, not the production fingerprint algorithm.
    return hashlib.sha256(json.dumps([shop_id, marketplace, client_id, secret], ensure_ascii=False).encode()).hexdigest()

def snapshot(db, destination):
    with sqlite3.connect(destination) as backup:
        db.backup(backup)
        if backup.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise RuntimeError('invalid snapshot')

def migrate(db, backup_path, codec, before_commit=None, inject_failure=False):
    if db.execute('PRAGMA user_version').fetchone()[0] == 2:
        return
    snapshot(db, backup_path)  # Never write target if backup failed.
    # Protect and round-trip outside the short write transaction.
    shops = db.execute('SELECT id, secret FROM shops ORDER BY id').fetchall()
    pipelines = db.execute('SELECT id, oms FROM pipelines ORDER BY id').fetchall()
    encoded = [(i, s, codec.protect(s)) for i, s in shops]
    encoded_pipelines = [(i, s, codec.protect(s)) for i, s in pipelines]
    if any(codec.reveal(v) != s for _, s, v in encoded + encoded_pipelines):
        raise ValueError('synthetic round trip failed')
    if before_commit:
        before_commit()
    try:
        db.execute('BEGIN IMMEDIATE')
        if db.execute('SELECT id, secret FROM shops ORDER BY id').fetchall() != shops or db.execute('SELECT id, oms FROM pipelines ORDER BY id').fetchall() != pipelines:
            raise ChangedDuringPreparation()
        db.executemany('UPDATE shops SET secret=?, format=2 WHERE id=?', [(v, i) for i, _, v in encoded])
        if inject_failure:
            raise RuntimeError('synthetic interruption')
        db.executemany('UPDATE pipelines SET oms=?, format=2 WHERE id=?', [(v, i) for i, _, v in encoded_pipelines])
        db.execute('PRAGMA user_version=2')
        db.commit()
    except BaseException:
        db.rollback()
        raise

class PrototypeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = str(Path(self.directory.name) / 'synthetic.db')
        self.backup = str(Path(self.directory.name) / 'backup.db')
        self.db = sqlite3.connect(self.path)
        self.db.executescript('CREATE TABLE shops(id INTEGER PRIMARY KEY, marketplace TEXT, client_id TEXT, secret TEXT, format INTEGER); CREATE TABLE pipelines(id INTEGER PRIMARY KEY, shop_id INTEGER, oms TEXT, format INTEGER); PRAGMA user_version=1;')
        self.db.executemany('INSERT INTO shops VALUES(?,?,?,?,1)', [(1, 'WB', 'A', 'dpapi:legacy-not-encrypted'), (2, 'OZON', 'B', 'á' * 10000)])
        self.db.executemany('INSERT INTO pipelines VALUES(?,?,?,1)', [(1, 1, 'old-OMS'), (2, 1, 'new-OMS'), (3, 2, '')])
        self.db.commit()
        self.codec = FakeCodec()
    def tearDown(self):
        self.db.close()
        self.directory.cleanup()
    def dump(self):
        return (self.db.execute('SELECT * FROM shops ORDER BY id').fetchall(), self.db.execute('SELECT * FROM pipelines ORDER BY id').fetchall(), self.db.execute('PRAGMA user_version').fetchone())
    def test_identity_legacy_prefix_historical_oms_and_idempotence(self):
        before = self.dump()
        identities = [fingerprint(*row[:4]) for row in before[0]]
        migrate(self.db, self.backup, self.codec)
        after = self.dump()
        self.assertEqual(identities, [fingerprint(r[0], r[1], r[2], self.codec.reveal(r[3])) for r in after[0]])
        self.assertEqual(['old-OMS', 'new-OMS', ''], [self.codec.reveal(r[2]) for r in after[1]])
        migrate(self.db, self.backup, self.codec)
        self.assertEqual(after, self.dump())
        with sqlite3.connect(self.backup) as backup:
            self.assertEqual('ok', backup.execute('PRAGMA integrity_check').fetchone()[0])
            self.assertEqual(before[0], backup.execute('SELECT * FROM shops ORDER BY id').fetchall())
    def test_mid_transaction_failure_rolls_back_and_retry_works(self):
        before = self.dump()
        with self.assertRaisesRegex(RuntimeError, 'synthetic interruption'):
            migrate(self.db, self.backup, self.codec, inject_failure=True)
        self.assertEqual(before, self.dump())
        self.db.close()
        self.db = sqlite3.connect(self.path)
        migrate(self.db, self.backup, self.codec)
        self.assertEqual((2,), self.dump()[2])
    def test_codec_failure_never_changes_db(self):
        self.db.execute("UPDATE shops SET secret='SIMULATE_CODEC_FAILURE' WHERE id=2")
        self.db.commit()
        before = self.dump()
        with self.assertRaises(ValueError):
            migrate(self.db, self.backup, self.codec)
        self.assertEqual(before, self.dump())
    def test_backup_failure_never_changes_db(self):
        before = self.dump()
        with self.assertRaises(sqlite3.OperationalError):
            migrate(self.db, str(Path(self.directory.name) / 'missing' / 'backup.db'), self.codec)
        self.assertEqual(before, self.dump())
    def test_change_during_preparation_aborts_without_overwrite(self):
        def concurrent_change():
            with sqlite3.connect(self.path) as other:
                other.execute("UPDATE pipelines SET oms='rotated-OMS' WHERE id=1")
        with self.assertRaises(ChangedDuringPreparation):
            migrate(self.db, self.backup, self.codec, before_commit=concurrent_change)
        self.assertEqual('rotated-OMS', self.db.execute('SELECT oms FROM pipelines WHERE id=1').fetchone()[0])
        self.assertEqual((1,), self.db.execute('PRAGMA user_version').fetchone())
    def test_duplicate_keys_remain_ambiguous_after_migration(self):
        self.db.execute('UPDATE shops SET secret=? WHERE id IN (1,2)', ('synthetic-shared-key',))
        self.db.commit()
        self.assertEqual(2, self.db.execute('SELECT COUNT(*) FROM shops WHERE secret=?', ('synthetic-shared-key',)).fetchone()[0])
        migrate(self.db, self.backup, self.codec)
        rows = self.db.execute('SELECT secret FROM shops ORDER BY id').fetchall()
        self.assertEqual(2, sum(self.codec.reveal(row[0]) == 'synthetic-shared-key' for row in rows))
    def test_snapshot_restores_old_values_and_version(self):
        before = self.dump()
        migrate(self.db, self.backup, self.codec)
        self.db.close()
        with sqlite3.connect(self.backup) as backup, sqlite3.connect(self.path) as restored:
            backup.backup(restored)
        self.db = sqlite3.connect(self.path)
        self.assertEqual(before, self.dump())
    def test_bad_protected_data_fails_closed(self):
        with self.assertRaises(ValueError):
            self.codec.reveal('dpapi:legacy-not-encrypted')

if __name__ == '__main__':
    unittest.main(verbosity=2)
