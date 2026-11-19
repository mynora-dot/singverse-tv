"""The Android per-ABI release index, also readable alongside legacy releases."""
import re


SUPPORTED_ABIS = {
    "mobile": ("armeabi-v7a", "arm64-v8a", "x86_64"),
    "tv": ("armeabi-v7a", "arm64-v8a", "x86", "x86_64"),
}
COMMON_FIELDS = ("platform", "versionCode", "versionName", "packageName", "minSdk", "certificateSha256", "notes")
HEX = re.compile(r"[a-f0-9]{64}")


def version_code(version):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Release versions must use major.minor.patch")
    major, minor, patch = map(int, version.split("."))
    code = major * 1000000 + minor * 1000 + patch
    if minor > 999 or patch > 999 or not 1 <= code <= 2100000000:
        raise ValueError("Release version is outside the Android versionCode range")
    return code


def apk_name(platform, abi):
    if abi not in SUPPORTED_ABIS[platform]:
        raise ValueError("Unsupported release ABI")
    return f"singverse-{platform}-{abi}.apk"


def check_apk_metadata(metadata, version, platform):
    expected = dict(schemaVersion=1, platform=platform, packageName="com.mynora.singverse." + platform,
                    versionName=version, versionCode=version_code(version), minSdk=21 if platform == "tv" else 24)
    if not isinstance(metadata, dict) or any(type(metadata.get(k)) is not type(value) or metadata.get(k) != value for k, value in expected.items()):
        raise ValueError("Published release metadata differs from its tag")
    for field in ("certificateSha256", "sha256"):
        if not isinstance(metadata.get(field), str) or not HEX.fullmatch(metadata[field]):
            raise ValueError("Published release digest or certificate is invalid")
    if type(metadata.get("size")) is not int or not 0 < metadata["size"] <= 256 << 20:
        raise ValueError("Published release APK size is invalid")
    abis = metadata.get("abis")
    allowed = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal"}
    if not isinstance(abis, list) or not abis or any(type(abi) is not str or abi not in allowed for abi in abis) or len(set(abis)) != len(abis) or ("universal" in abis and len(abis) != 1):
        raise ValueError("Published release APK architectures are invalid")
    if not isinstance(metadata.get("notes"), str) or len(metadata["notes"].encode("utf-8")) > 16000:
        raise ValueError("Published release notes are invalid")


def manifest_packages(metadata, version, platform):
    """Return fixed, safe asset names and complete APK metadata for schema 1 or 2."""
    if not isinstance(metadata, dict) or type(metadata.get("schemaVersion")) is not int:
        raise ValueError("Invalid release manifest")
    if metadata["schemaVersion"] == 1:
        check_apk_metadata(metadata, version, platform)
        return [("singverse-" + platform + ".apk", metadata)]
    if metadata["schemaVersion"] != 2:
        raise ValueError("Unsupported release manifest schema")
    variants = metadata.get("variants")
    if not isinstance(variants, list) or any(not isinstance(item, dict) for item in variants):
        raise ValueError("Invalid per-ABI release variants")
    abis = [item.get("abi") for item in variants]
    if any(type(abi) is not str for abi in abis) or sorted(abis) != sorted(SUPPORTED_ABIS[platform]):
        raise ValueError("Per-ABI release must contain every supported ABI exactly once")
    packages = []
    for variant in variants:
        name = apk_name(platform, variant["abi"])
        if variant.get("fileName") != name:
            raise ValueError("Per-ABI release asset name is invalid")
        item = {key: metadata.get(key) for key in COMMON_FIELDS}
        item.update(schemaVersion=1, abis=[variant["abi"]], size=variant.get("size"), sha256=variant.get("sha256"))
        check_apk_metadata(item, version, platform)
        packages.append((name, item))
    return sorted(packages)


def release_index(manifests):
    first = manifests[0]
    index = {key: first[key] for key in COMMON_FIELDS}
    index.update(schemaVersion=2, variants=[])
    for item in sorted(manifests, key=lambda item: item["abis"]):
        if any(item[key] != first[key] for key in COMMON_FIELDS):
            raise ValueError("Per-ABI APKs must share version, package, SDK, notes and signing certificate")
        if len(item["abis"]) != 1 or item["abis"][0] not in SUPPORTED_ABIS[first["platform"]]:
            raise ValueError("Release APK must contain exactly one supported ABI")
        abi = item["abis"][0]
        index["variants"].append(dict(abi=abi, fileName=apk_name(first["platform"], abi), size=item["size"], sha256=item["sha256"]))
    manifest_packages(index, first["versionName"], first["platform"])
    return index
