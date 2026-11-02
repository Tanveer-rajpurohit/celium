import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("prepare_release", Path(__file__).parents[1] / "prepare_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PrepareReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.metadata = self.root / "output-metadata.json"
        self.apk = self.root / "app-stable-release.apk"
        self.apk.write_bytes(b"test APK bytes")
        self.data = {
            "applicationId": "app.celium.android.debug",
            "elements": [{"versionCode": 76, "versionName": "7.0.5-celium", "filters": [], "outputFile": self.apk.name}],
        }

    def package(self, tag="v7.0.5", application_id="app.celium.android.debug"):
        self.metadata.write_text(json.dumps(self.data), encoding="utf-8")
        return release.prepare_release(self.metadata, self.root / "delivery", tag, application_id)

    def test_manifest_matches_apk_bytes_and_installed_identity(self):
        manifest = self.package()
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), manifest["sha256"])
        self.assertEqual(self.data["applicationId"], manifest["applicationId"])
        self.assertEqual(76, manifest["versionCode"])
        output = self.root / "delivery"
        self.assertEqual(self.apk.read_bytes(), (output / manifest["apkName"]).read_bytes())
        self.assertEqual(manifest, json.loads((output / "celium-update.json").read_text()))

    def test_mismatched_tag_and_package_are_rejected(self):
        with self.assertRaises(ValueError):
            self.package(tag="v7.0.4")
        with self.assertRaises(ValueError):
            self.package(application_id="app.celium.android")

    def test_split_apks_are_rejected(self):
        self.data["elements"][0]["filters"] = [{"filterType": "ABI", "value": "arm64-v8a"}]
        with self.assertRaises(ValueError):
            self.package()

    def test_path_outside_build_output_is_rejected(self):
        self.data["elements"][0]["outputFile"] = "../other.apk"
        with self.assertRaises(ValueError):
            self.package()

    def test_invalid_version_code_is_rejected(self):
        self.data["elements"][0]["versionCode"] = 0
        with self.assertRaises(ValueError):
            self.package()


if __name__ == "__main__":
    unittest.main()
