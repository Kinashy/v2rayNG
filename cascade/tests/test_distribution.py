import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare
import release


class DistributionTests(unittest.TestCase):
    def test_shared_policy_is_complete_and_has_no_personal_profile(self):
        metadata, rules, apps, settings = prepare.load_policy()
        self.assertEqual(24, len(rules))
        self.assertEqual(13, len(apps))
        self.assertEqual("IPOnDemand", metadata["domain_strategy"])
        self.assertEqual("bittorrent", rules[0]["protocol"][0])
        self.assertEqual("direct", rules[-1]["outboundTag"])
        self.assertIn("com.microsoft.teams", apps)
        self.assertTrue(settings["pref_per_app_proxy"]["value"])
        self.assertFalse(settings["pref_bypass_apps"]["value"])
        self.assertNotIn("SERVER_CONFIG", json.dumps(settings))

    def test_changed_upstream_anchor_stops_preparation(self):
        with self.assertRaisesRegex(ValueError, "Upstream changed"):
            prepare.replace_once("changed source", "expected", "replacement", "test")
        with self.assertRaisesRegex(ValueError, "Upstream changed"):
            prepare.replace_once("expected expected", "expected", "replacement", "test")

    def test_function_replacement_keeps_neighbouring_code(self):
        source = "before\nfun selected() { if (true) { 1 } }\nafter"
        self.assertEqual("before\nfun selected() { 2 }\nafter", prepare.replace_function(source, "fun selected()", "fun selected() { 2 }"))

    def test_rejects_mismatched_source_before_writing(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            gradle = root / "V2rayNG/app/build.gradle.kts"
            gradle.parent.mkdir(parents=True)
            original = 'applicationId = "com.v2ray.ang"\nversionCode = 10\nversionName = "2.0.0"\n'
            gradle.write_text(original, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Source version"):
                prepare.prepare(root, "2.3.15")
            self.assertEqual(original, gradle.read_text(encoding="utf-8"))

    def test_failed_asset_digest_does_not_write_a_file(self):
        content = b"downloaded"
        fake = {"assets": [{"name": "geoip.dat", "size": len(content), "digest": "sha256:" + "0" * 64,
                            "browser_download_url": "https://github.com/example/repo/releases/download/tag/geoip.dat"}]}
        with tempfile.TemporaryDirectory() as folder, patch.object(release, "request", return_value=content):
            target = Path(folder) / "geoip.dat"
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                release.download_asset(fake, "geoip.dat", target)
            self.assertFalse(target.exists())

    def test_published_build_is_not_rebuilt_and_draft_is_retryable(self):
        for draft in (False, True):
            with self.subTest(draft=draft), tempfile.TemporaryDirectory() as folder:
                output = Path(folder) / "plan.json"
                config = {"upstream_repository": "upstream/project", "repository": "owner/fork", "revision": 7}
                with patch.object(release, "read_json", return_value=config), patch.object(release, "stable_release", return_value={"tag_name": "2.3.15"}), patch.object(release, "api", return_value={"draft": draft}):
                    release.plan("", output)
                result = json.loads(output.read_text())
                self.assertEqual(draft, result["build"])
                self.assertEqual("v2.3.15.7", result["release_tag"])

    def test_apk_validation_rejects_missing_native_library(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source"
            outputs = source / "V2rayNG/app/build/outputs/apk/playstore/release"
            outputs.mkdir(parents=True)
            (source / "cascade-build.json").write_text(json.dumps({"version": "2.3.15.1", "geo_assets": []}))
            with zipfile.ZipFile(outputs / "v2rayNG_2.3.15.1_arm64-v8a.apk", "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
            with self.assertRaisesRegex(ValueError, "Missing native library"):
                release.package(source, root / "release")

    def test_geodata_must_contain_the_categories_used_by_enabled_rules(self):
        def database(code):
            encoded = code.encode("ascii")
            entry = b"\x0a" + bytes([len(encoded)]) + encoded
            return b"\x0a" + bytes([len(entry)]) + entry

        rules = [{"ip": ["geoip:ru"]}, {"domain": ["geosite:google"]},
                 {"enabled": False, "domain": ["geosite:disabled"]}]
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "geoip.dat").write_bytes(database("RU"))
            (root / "geosite.dat").write_bytes(database("GOOGLE"))
            self.assertEqual({"geoip": ["ru"], "geosite": ["google"]}, release.validate_geo_categories(root, rules))
            (root / "geosite.dat").write_bytes(database("OTHER"))
            with self.assertRaisesRegex(ValueError, "missing routing categories: google"):
                release.validate_geo_categories(root, rules)
            (root / "geoip.dat").write_bytes(b"\x0a\x64truncated")
            with self.assertRaisesRegex(ValueError, "Truncated"):
                release.validate_geo_categories(root, rules)


if __name__ == "__main__":
    unittest.main()
