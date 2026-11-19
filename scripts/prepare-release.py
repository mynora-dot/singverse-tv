#!/usr/bin/env python3
"""Validate an APK using Android build tools and emit immutable release assets."""
import argparse
import hashlib
import json
import os
import sys
from pathlib import Path
import re
import shutil
import subprocess

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from build_version import build_version
from apk_signature import release_certificate
from release_manifest import release_index


def version_code(version):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("SINGVERSE_VERSION must be major.minor.patch")
    major, minor, patch = map(int, version.split("."))
    code = major * 1000000 + minor * 1000 + patch
    if minor > 999 or patch > 999 or not 1 <= code <= 2100000000:
        raise ValueError("SINGVERSE_VERSION is outside Android versionCode range")
    return code


def inspect_apk(apk, platform, version, tools, notes):
    code = version_code(version)
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
    signer = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    expected = "com.mynora.singverse." + platform
    if not package or package.group(1) != expected or int(package.group(2)) != code or package.group(3) != version:
        raise ValueError("APK package/version differs from the Actions release version")
    minimum = 24 if platform == "mobile" else 21
    if not sdk or int(sdk.group(1)) != minimum:
        raise ValueError("APK minSdk differs from platform contract")
    if "application-debuggable" in badging:
        raise ValueError("Debuggable APK cannot be published")
    certificate = release_certificate(signer)
    native = re.search(r"^native-code:(.*)$", badging, re.M)
    abis = sorted(re.findall(r"'([^']+)'", native.group(1))) if native else []
    if len(abis) != 1:
        raise ValueError("Release APK must contain exactly one supported ABI")
    data = apk.read_bytes()
    if not 0 < len(data) <= 268435456:
        raise ValueError("APK must be between 1 byte and 256 MiB")
    return dict(schemaVersion=1, platform=platform, versionCode=code, versionName=version,
                packageName=expected, minSdk=minimum, abis=abis, size=len(data), sha256=hashlib.sha256(data).hexdigest(),
                certificateSha256=certificate, notes=notes)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, action="append", required=True, help="Repeat for every supported ABI")
    parser.add_argument("--platform", choices=["mobile", "tv"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    version = build_version(required=True)
    tools = args.build_tools
    if tools is None:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            props = root / "local.properties"
            if props.exists():
                match = re.search(r"^sdk.dir=(.+)$", props.read_text(), re.M)
                sdk = match.group(1) if match else None
        if not sdk:
            raise SystemExit("Android SDK is required; pass --build-tools or configure ANDROID_HOME")
        candidates = [p for p in (Path(sdk) / "build-tools").iterdir() if (p / "aapt").is_file() and (p / "apksigner").is_file()]
        if not candidates:
            raise SystemExit("Android aapt/apksigner build tools are required")
        tools = sorted(candidates, key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])[-1]
    notes = args.notes.read_text().strip() if args.notes else os.environ.get("RELEASE_NOTES", "").strip()
    if len(notes.encode("utf-8")) > 16000:
        raise SystemExit("Release notes exceed 16000 UTF-8 bytes")
    manifests = [inspect_apk(apk, args.platform, version, tools, notes) for apk in args.apk]
    index = release_index(manifests)
    sources = {item["abis"][0]: apk for item, apk in zip(manifests, args.apk)}
    args.output.mkdir(parents=True, exist_ok=True)
    names = []
    for variant in index["variants"]:
        name = variant["fileName"]
        shutil.copyfile(sources[variant["abi"]], args.output / name)
        names.append(name)
    (args.output / "release.json").write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n")
    names.append("release.json")
    (args.output / "SHA256SUMS").write_text("".join(hashlib.sha256((args.output / name).read_bytes()).hexdigest() + "  " + name + "\n" for name in names))
    # An earlier local build may have staged the old fat APK in this generated directory.
    (args.output / ("singverse-" + args.platform + ".apk")).unlink(missing_ok=True)
    print("Validated per-ABI APKs and prepared release assets for " + args.platform + " " + version)


if __name__ == "__main__":
    main()
