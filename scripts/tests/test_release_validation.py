import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release_validation", Path(__file__).resolve().parents[1] / "validate-release.py")
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)


class ReleaseValidationTest(unittest.TestCase):
    def release(self, tag="v0.1.0", **values):
        return dict({"tag_name": tag, "draft": False, "prerelease": False, "assets": []}, **values)

    def metadata(self, version="0.1.0", certificate="a" * 64):
        return dict(schemaVersion=1, platform="tv", packageName="com.mynora.singverse.tv", minSdk=23,
                    versionName=version, versionCode=validation.version_code(version), certificateSha256=certificate,
                    sha256="c" * 64, size=123, abis=["universal"], notes="发行说明")

    def test_release_tag_must_match_root_version(self):
        with self.assertRaisesRegex(ValueError, "tag must equal"):
            validation.release_plan("0.1.0", "v0.2.0", [])

    def test_an_older_or_duplicate_product_version_cannot_be_published(self):
        for tag in ("v0.2.0", "0.1.0"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "greater"):
                validation.release_plan("0.1.0", "v0.1.0", [self.release(), self.release(tag)])

    def test_formal_new_release_ignores_drafts_and_prereleases(self):
        current = self.release()
        result = validation.release_plan("0.1.0", "v0.1.0", [current, self.release("draft", draft=True),
                                        self.release("v1.0.0-rc.1", prerelease=True)], self.metadata())
        self.assertEqual(result, current)

    def test_certificate_and_code_are_continuous_from_previous_formal_release(self):
        old = self.release("v0.0.999", assets=[dict(name="release.json", state="uploaded", id=12)])
        with patch.object(validation, "asset", wraps=validation.asset) as lookup:
            validation.release_plan("0.1.0", "v0.1.0", [self.release(), old], self.metadata(),
                                    lambda item, maximum: json.dumps(self.metadata("0.0.999")).encode())
            self.assertEqual(lookup.call_args.args[0]["tag_name"], "v0.0.999")
        with self.assertRaisesRegex(ValueError, "certificate must match"):
            validation.release_plan("0.1.0", "v0.1.0", [self.release(), old], self.metadata(certificate="b" * 64),
                                    lambda item, maximum: json.dumps(self.metadata("0.0.999")).encode())
        bad = self.metadata("0.0.999"); bad["versionCode"] = 1000
        with self.assertRaisesRegex(ValueError, "metadata differs"):
            validation.release_plan("0.1.0", "v0.1.0", [self.release(), old], self.metadata(),
                                    lambda item, maximum: json.dumps(bad).encode())

    def test_missing_previous_metadata_and_network_errors_never_become_a_first_release(self):
        old = self.release("v0.0.999")
        with self.assertRaisesRegex(ValueError, "Missing"):
            validation.release_plan("0.1.0", "v0.1.0", [self.release(), old], self.metadata(), lambda *args: None)
        old["assets"] = [dict(name="release.json", state="uploaded", id=12)]
        def unavailable(*args):
            raise OSError("Network unavailable")
        with self.assertRaisesRegex(OSError, "Network"):
            validation.release_plan("0.1.0", "v0.1.0", [self.release(), old], self.metadata(), unavailable)

    def test_the_highest_previous_formal_version_is_used_and_current_tag_is_excluded(self):
        low = self.release("v0.0.1", assets=[dict(name="release.json", state="uploaded", id=1)])
        high = self.release("v0.0.999", assets=[dict(name="release.json", state="uploaded", id=999)])
        seen = []
        def read(item, maximum):
            seen.append(item["id"])
            return json.dumps(self.metadata("0.0.999")).encode()
        validation.release_plan("0.1.0", "v0.1.0", [low, self.release(), high], self.metadata(), read)
        self.assertEqual(seen, [999])

    def test_a_retry_reuses_identical_assets_and_uploads_only_missing_ones(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in ("singverse-tv.apk", "release.json", "SHA256SUMS"):
                (directory / name).write_bytes(name.encode())
            existing = dict(name="singverse-tv.apk", state="uploaded", id=1, size=len(b"singverse-tv.apk"))
            result = validation.reusable_assets(self.release(assets=[existing]), directory, lambda *args: b"singverse-tv.apk")
            self.assertEqual([p.name for p in result], ["release.json", "SHA256SUMS"])
            with self.assertRaisesRegex(ValueError, "different content"):
                validation.reusable_assets(self.release(assets=[existing]), directory, lambda *args: b"x" * existing["size"])
            with self.assertRaisesRegex(ValueError, "ambiguous"):
                validation.reusable_assets(self.release(assets=[existing, existing]), directory, lambda *args: b"singverse-tv.apk")

    def test_android_code_boundaries_and_metadata_types(self):
        self.assertEqual(validation.version_code("1.2.3"), 1002003)
        for value in ("0.0.0", "1.1000.0", "1.0.1000", "2101.0.0", "01.0.0"):
            with self.subTest(value=value), self.assertRaises(ValueError): validation.version_code(value)
        wrong = self.metadata(); wrong["schemaVersion"] = True
        with self.assertRaises(ValueError): validation.check_metadata(wrong, "0.1.0")

    def test_corrupt_previous_metadata_is_rejected(self):
        for key, value in (("sha256", ""), ("size", True), ("size", 0), ("abis", []), ("abis", ["unknown"]),
                           ("abis", ["universal", "arm64-v8a"]), ("notes", "长" * 6000)):
            data = self.metadata()
            data[key] = value
            with self.subTest(key=key, value=str(value)[:20]), self.assertRaises(ValueError):
                validation.check_metadata(data, "0.1.0")
        for key in self.metadata():
            data = self.metadata(); del data[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                validation.check_metadata(data, "0.1.0")

    def test_http_failures_are_not_treated_as_an_empty_release_history(self):
        from urllib.error import HTTPError, URLError
        old = self.release("v0.0.999", assets=[dict(name="release.json", state="uploaded", id=12)])
        for failure in (HTTPError("https://api.github.com/x", status, "unavailable", None, None) for status in (401, 403, 404)):
            def unavailable(*args): raise failure
            with self.subTest(status=failure.code), self.assertRaises(HTTPError):
                validation.release_plan("0.1.0", "v0.1.0", [self.release(), old], self.metadata(), unavailable)

    def test_only_expected_https_destinations_are_allowed(self):
        for url in ("http://github.com/x", "https://github.com.evil/x", "https://user:pass@api.github.com/x"):
            with self.subTest(url=url), self.assertRaises(ValueError): validation.github_url(url)
        validation.github_url("https://release-assets.githubusercontent.com/x")


if __name__ == "__main__":
    unittest.main()
