import copy
import importlib.util
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_manifest import SUPPORTED_ABIS, manifest_packages, release_index


class ReleaseManifestTest(unittest.TestCase):
    def metadata(self, platform="mobile", abi="arm64-v8a"):
        return dict(schemaVersion=1, platform=platform, packageName="com.mynora.singverse." + platform,
                    versionName="0.1.0", versionCode=1000, minSdk=21 if platform == "tv" else 24,
                    abis=[abi], size=42, sha256="a" * 64, certificateSha256="b" * 64, notes="修复播放")

    def manifests(self, platform):
        return [self.metadata(platform, abi) for abi in SUPPORTED_ABIS[platform]]

    def test_all_existing_architectures_have_individual_assets_and_the_same_version(self):
        for platform in SUPPORTED_ABIS:
            index = release_index(self.manifests(platform))
            packages = manifest_packages(index, "0.1.0", platform)
            self.assertEqual(index["schemaVersion"], 2)
            self.assertEqual({metadata["abis"][0] for _, metadata in packages}, set(SUPPORTED_ABIS[platform]))
            for name, metadata in packages:
                self.assertEqual(name, f"singverse-{platform}-{metadata['abis'][0]}.apk")
                self.assertEqual(metadata["versionCode"], 1000)

    def test_legacy_metadata_remains_readable_for_signature_continuity_and_pinned_platform_builds(self):
        for platform in SUPPORTED_ABIS:
            metadata = self.metadata(platform, "universal")
            self.assertEqual(manifest_packages(metadata, "0.1.0", platform), [(f"singverse-{platform}.apk", metadata)])

    def test_missing_duplicate_universal_and_mixed_abi_packages_are_rejected(self):
        for platform in SUPPORTED_ABIS:
            good = self.manifests(platform)
            for packages in (good[:-1], good + good[:1], [self.metadata(platform, "universal")],
                             [dict(good[0], abis=["armeabi-v7a", "arm64-v8a"])] + good[1:]):
                with self.subTest(platform=platform, packages=packages), self.assertRaises(ValueError):
                    release_index(packages)

    def test_variant_versions_signatures_and_notes_cannot_diverge(self):
        for field, value in (("versionCode", 2000), ("certificateSha256", "c" * 64), ("notes", "不同说明")):
            packages = self.manifests("mobile")
            packages[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                release_index(packages)

    def test_malformed_variant_names_types_digests_and_abi_sets_fail_closed(self):
        good = release_index(self.manifests("tv"))
        for changes in ({"fileName": "../outside.apk"}, {"fileName": "singverse-tv.apk"},
                        {"size": True}, {"size": 268435457}, {"sha256": "invalid"}, {"abi": "universal"}):
            index = copy.deepcopy(good)
            index["variants"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                manifest_packages(index, "0.1.0", "tv")
        for schema in (True, 3):
            with self.assertRaises(ValueError):
                manifest_packages(dict(good, schemaVersion=schema), "0.1.0", "tv")

    def test_abi_version_code_offsets_are_rejected(self):
        packages = self.manifests("mobile")
        for item in packages:
            item["versionCode"] += 1000
        with self.assertRaisesRegex(ValueError, "metadata differs"):
            release_index(packages)


if __name__ == "__main__":
    unittest.main()
