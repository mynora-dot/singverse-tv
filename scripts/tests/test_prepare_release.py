import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import subprocess

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_manifest import SUPPORTED_ABIS

spec = importlib.util.spec_from_file_location("prepare_release", Path(__file__).resolve().parents[1] / "prepare-release.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)
spec = importlib.util.spec_from_file_location("validate_prepared_release", Path(__file__).resolve().parents[1] / "validate-release.py")
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)


class PrepareReleaseTest(unittest.TestCase):
    def test_real_asset_layout_checksums_and_immutable_retries_cover_every_abi(self):
        for platform in SUPPORTED_ABIS:
            with self.subTest(platform=platform), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                output = root / "assets"
                output.mkdir()
                (output / f"singverse-{platform}.apk").write_bytes(b"stale universal")
                args = ["prepare-release.py", "--platform", platform, "--output", str(output), "--build-tools", str(root)]
                results = []
                for abi in SUPPORTED_ABIS[platform]:
                    apk = root / f"{abi}.apk"
                    apk.write_bytes(("APK-" + abi).encode())
                    args.extend(["--apk", str(apk)])
                    badging = (f"package: name='com.mynora.singverse.{platform}' versionCode='1000' versionName='0.1.0'\n"
                               f"sdkVersion:'{21 if platform == 'tv' else 24}'\nnative-code: '{abi}'\n")
                    results.extend([subprocess.CompletedProcess([], 0, badging, ""),
                                    subprocess.CompletedProcess([], 0, "Signer #1 certificate DN: CN=SingVerse Release\nSigner #1 certificate SHA-256 digest: " + "b" * 64, "")])
                with patch.object(sys, "argv", args), patch.dict(os.environ, {"SINGVERSE_VERSION": "0.1.0", "RELEASE_TAG": "", "RELEASE_NOTES": "修复播放"}), patch.object(prepare.subprocess, "run", side_effect=results):
                    prepare.main()
                index = json.loads((output / "release.json").read_text())
                names = validation.prepared_files(output, index, "0.1.0", platform)
                self.assertEqual(len(names), len(SUPPORTED_ABIS[platform]) + 2)
                self.assertFalse((output / f"singverse-{platform}.apk").exists())
                self.assertEqual(set(names), {path.name for path in output.iterdir()})
                existing = {"name": names[0], "state": "uploaded", "id": 1, "size": (output / names[0]).stat().st_size}
                release = {"assets": [existing]}
                missing = validation.reusable_assets(release, output, lambda *_: (output / names[0]).read_bytes(), platform, names)
                self.assertEqual([path.name for path in missing], names[1:])
                with self.assertRaisesRegex(ValueError, "different content"):
                    validation.reusable_assets(release, output, lambda *_: b"x" * existing["size"], platform, names)
                (output / names[0]).write_bytes(b"corrupt")
                with self.assertRaisesRegex(ValueError, "checksum differs"):
                    validation.prepared_files(output, index, "0.1.0", platform)

    def test_an_individual_apk_cannot_be_fat_debuggable_or_use_an_abi_version_offset(self):
        good = "package: name='com.mynora.singverse.mobile' versionCode='1000' versionName='0.1.0'\nsdkVersion:'24'\nnative-code: 'arm64-v8a'\n"
        signature = "Signer #1 certificate DN: CN=SingVerse Release\nSigner #1 certificate SHA-256 digest: " + "b" * 64
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            apk.write_bytes(b"fixture")
            for badging in (good.replace("'arm64-v8a'", "'arm64-v8a' 'armeabi-v7a'"), good + "application-debuggable\n", good.replace("versionCode='1000'", "versionCode='3000'")):
                with patch.object(prepare.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0, badging, ""), subprocess.CompletedProcess([], 0, signature, "")]), self.assertRaises(ValueError):
                    prepare.inspect_apk(apk, "mobile", "0.1.0", Path(temporary), "")


if __name__ == "__main__":
    unittest.main()
