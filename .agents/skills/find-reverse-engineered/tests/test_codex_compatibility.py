"""Installation/portability checks; no seller API, credentials or network writes."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("re_index", ROOT / "scripts/re_index.py")
index = importlib.util.module_from_spec(spec)
spec.loader.exec_module(index)


class CodexCompatibilityTest(unittest.TestCase):
    def test_codex_name_and_description_are_present(self):
        text = (ROOT / "SKILL.md").read_text(encoding="utf-8")
        self.assertTrue(text.startswith("---\nname: find-reverse-engineered\n"))
        self.assertIn("\ndescription: ", text.split("---", 2)[1])

    def test_offline_uses_bundled_index_without_network(self):
        with patch.dict(os.environ, {"RE_INDEX_OFFLINE": "1"}, clear=True), \
                patch.object(index.urllib.request, "urlopen") as fetch:
            entries = index.load()
        fetch.assert_not_called()
        self.assertEqual(12866, len(entries))
        self.assertTrue(any(e["url"] == "https://github.com/glmn/wb-private-api" for e in entries))

    def test_blocked_network_still_searches_bundled_index(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(os.environ, {}, clear=True), \
                patch.object(index, "CACHE", str(Path(directory) / "missing.json")), \
                patch.object(index.urllib.request, "urlopen", side_effect=OSError("network unavailable")):
            self.assertEqual(12866, len(index.load()))

    def test_corrupt_cache_does_not_break_offline_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "entries.json"
            cache.write_text("not JSON", encoding="utf-8")
            with patch.dict(os.environ, {}, clear=True), patch.object(index, "CACHE", str(cache)):
                self.assertEqual(12866, len(index.load()))

    def test_read_only_cache_falls_back_to_bundled_data(self):
        with tempfile.TemporaryDirectory() as directory:
            blocker = Path(directory) / "not-a-directory"
            blocker.write_text("fixture", encoding="utf-8")
            response = unittest.mock.MagicMock()
            response.__enter__.return_value.read.return_value = b'[]'
            with patch.dict(os.environ, {}, clear=True), \
                    patch.object(index, "CACHE", str(blocker / "entries.json")), \
                    patch.object(index.urllib.request, "urlopen", return_value=response):
                self.assertEqual(12866, len(index.load()))

    def test_search_from_unrelated_working_directory(self):
        environment = dict(os.environ, RE_INDEX_OFFLINE="1")
        environment.pop("RE_INDEX_DATA", None)
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/re_index.py"),
                "search", "wildberries", "--json"], cwd=directory, env=environment,
                capture_output=True, text=True, check=True)
        self.assertEqual("https://github.com/glmn/wb-private-api", json.loads(result.stdout)[0]["url"])

    def test_readme_uses_configured_cli_without_extracting_token(self):
        completed = subprocess.CompletedProcess([], 0, stdout="# Public README", stderr="")
        with patch.object(index.shutil, "which", return_value="/usr/bin/gh"), \
                patch.object(index.subprocess, "run", return_value=completed) as run:
            self.assertEqual("# Public README", index.github_readme("https://github.com/glmn/wb-private-api"))
        self.assertEqual(["gh", "api", "--hostname", "github.com", "repos/glmn/wb-private-api/readme",
            "-H", "Accept: application/vnd.github.raw"], run.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
