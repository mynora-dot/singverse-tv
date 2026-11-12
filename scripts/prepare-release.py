#!/usr/bin/env python3
"""Validate an APK using Android build tools and emit immutable release assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def version_code(version):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("VERSION must be major.minor.patch")
    major, minor, patch = map(int, version.split("."))
    code = major * 1000000 + minor * 1000 + patch
    if minor > 999 or patch > 999 or not 1 <= code <= 2100000000:
        raise ValueError("VERSION is outside Android versionCode range")
    return code


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--platform", choices=["mobile", "tv"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    version = (root / "VERSION").read_text().strip()
    code = version_code(version)
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
        tools = sorted(candidates, key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])[-1]
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(args.apk)], check=True, capture_output=True, text=True).stdout
    signer = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$", signer, re.M)
    expected = "com.mynora.singverse." + args.platform
    if not package or package.group(1) != expected or int(package.group(2)) != code or package.group(3) != version:
        raise SystemExit("APK package/version differs from VERSION")
    minimum = 24 if args.platform == "mobile" else 23
    if not sdk or int(sdk.group(1)) != minimum:
        raise SystemExit("APK minSdk differs from platform contract")
    if len(certificates) != 1 or not re.fullmatch(r"[a-fA-F0-9]{64}", certificates[0]):
        raise SystemExit("APK must have one valid release certificate")
    if re.search(r"^Signer #\d+ certificate DN:.*CN=Android Debug", signer, re.M):
        raise SystemExit("Debug-signed APK cannot be published")
    native = re.search(r"^native-code:(.*)$", badging, re.M)
    abis = sorted(re.findall(r"'([^']+)'", native.group(1))) if native else ["universal"]
    data = args.apk.read_bytes()
    if not 0 < len(data) <= 268435456:
        raise SystemExit("APK must be between 1 byte and 256 MiB")
    notes = args.notes.read_text().strip() if args.notes else os.environ.get("RELEASE_NOTES", "").strip()
    if len(notes.encode("utf-8")) > 16000:
        raise SystemExit("Release notes must be at most 16000 UTF-8 bytes")
    name = "singverse-" + args.platform + ".apk"
    manifest = dict(schemaVersion=1, platform=args.platform, versionCode=code, versionName=version,
                    packageName=expected, minSdk=minimum, abis=abis, size=len(data), sha256=hashlib.sha256(data).hexdigest(),
                    certificateSha256=certificates[0].lower(), notes=notes)
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.apk, args.output / name)
    (args.output / "release.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    files = [name, "release.json"]
    (args.output / "SHA256SUMS").write_text("".join(hashlib.sha256((args.output / f).read_bytes()).hexdigest() + "  " + f + "\n" for f in files))
    print("Validated APK and prepared release assets for " + args.platform + " " + version)


if __name__ == "__main__":
    main()
