#!/usr/bin/env python3
"""Check release versions, signing continuity and immutable, retryable assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
HEX = re.compile(r"[a-f0-9]{64}")


def parts(version):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Release versions must use major.minor.patch")
    value = tuple(map(int, version.split(".")))
    code = value[0] * 1000000 + value[1] * 1000 + value[2]
    if value[1] > 999 or value[2] > 999 or not 1 <= code <= 2100000000:
        raise ValueError("Release version is outside the Android versionCode range")
    return value


def version_code(version):
    major, minor, patch = parts(version)
    return major * 1000000 + minor * 1000 + patch


def github_url(url):
    value = urlparse(url)
    if value.scheme != "https" or value.username or value.password or not (
        value.hostname in ("api.github.com", "github.com") or
        (value.hostname or "").endswith(".githubusercontent.com") or
        (value.hostname or "").endswith(".github.com")
    ):
        raise ValueError("Unexpected GitHub release destination")


class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        github_url(newurl)
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and urlparse(req.full_url).netloc != urlparse(newurl).netloc:
            redirected.remove_header("Authorization")
        return redirected


def fetch(url, maximum, binary=False):
    github_url(url)
    headers = {"Accept": "application/octet-stream" if binary else "application/vnd.github+json",
               "User-Agent": "SingVerse-TV-release"}
    if urlparse(url).hostname == "api.github.com":
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    with build_opener(SafeRedirect()).open(Request(url, headers=headers), timeout=30) as response:
        payload = response.read(maximum + 1)
    if len(payload) > maximum:
        raise ValueError("GitHub release response is too large")
    return payload


def asset(release, name):
    matches = [item for item in release.get("assets", []) if item.get("name") == name]
    if len(matches) != 1 or matches[0].get("state") != "uploaded" or type(matches[0].get("id")) is not int:
        raise ValueError("Missing, incomplete or ambiguous published asset: " + name)
    return matches[0]


def check_metadata(metadata, version, platform="tv"):
    expected = dict(schemaVersion=1, platform=platform, packageName="com.mynora.singverse." + platform,
                    versionName=version, versionCode=version_code(version))
    if any(type(metadata.get(k)) is not type(value) or metadata.get(k) != value for k, value in expected.items()):
        raise ValueError("Published release metadata differs from its tag")
    if type(metadata.get("minSdk")) is not int or metadata["minSdk"] < (21 if platform == "tv" else 24):
        raise ValueError("Published release minSdk is invalid")
    if not isinstance(metadata.get("certificateSha256"), str) or not HEX.fullmatch(metadata["certificateSha256"]):
        raise ValueError("Published release certificate is invalid")
    if not isinstance(metadata.get("sha256"), str) or not HEX.fullmatch(metadata["sha256"]):
        raise ValueError("Published APK digest is invalid")
    if type(metadata.get("size")) is not int or not 0 < metadata["size"] <= 256 << 20:
        raise ValueError("Published APK size is invalid")
    abis = metadata.get("abis")
    known = {"universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
    if not isinstance(abis, list) or not abis or any(type(abi) is not str or abi not in known for abi in abis) or len(set(abis)) != len(abis) or ("universal" in abis and len(abis) != 1):
        raise ValueError("Published APK ABIs are invalid")
    if not isinstance(metadata.get("notes"), str) or len(metadata["notes"].encode("utf-8")) > 16000:
        raise ValueError("Published release notes are invalid")


def release_plan(version, tag, releases, candidate=None, read_asset=None, platform="tv"):
    if tag != "v" + version:
        raise ValueError("Release tag must equal v + root VERSION")
    current_version = parts(version)
    formal = [item for item in releases if not item.get("draft") and not item.get("prerelease")]
    current = [item for item in formal if item["tag_name"] == tag]
    if len(current) != 1:
        raise ValueError("Current published release is missing or ambiguous")
    previous = []
    for item in formal:
        if item["tag_name"] == tag:
            continue
        number = parts(item["tag_name"].removeprefix("v"))
        if number >= current_version:
            raise ValueError("VERSION must be greater than every previous formal release")
        previous.append((number, item))
    if candidate is not None:
        check_metadata(candidate, version, platform)
        if previous:
            old = max(previous, key=lambda item: item[0])[1]
            metadata = json.loads(read_asset(asset(old, "release.json"), 65536))
            check_metadata(metadata, old["tag_name"].removeprefix("v"), platform)
            if candidate["versionCode"] <= metadata["versionCode"]:
                raise ValueError("Android versionCode must increase from the previous formal release")
            if candidate["certificateSha256"] != metadata["certificateSha256"]:
                raise ValueError("Release certificate must match the previous formal release")
    return current[0]


def reusable_assets(release, directory, read_asset, platform="tv"):
    """Check every existing byte before uploading any missing asset."""
    missing = []
    for name in ("singverse-" + platform + ".apk", "release.json", "SHA256SUMS"):
        local = directory / name
        if not local.is_file():
            raise ValueError("Prepared release asset missing: " + name)
        existing = [item for item in release.get("assets", []) if item.get("name") == name]
        if not existing:
            missing.append(local)
            continue
        item = asset(release, name)
        maximum = 256 << 20 if name.endswith(".apk") else 65536
        remote = read_asset(item, maximum)
        if len(remote) != item.get("size") or len(remote) != local.stat().st_size or hashlib.sha256(remote).digest() != hashlib.sha256(local.read_bytes()).digest():
            raise ValueError("Immutable release asset has different content: " + name)
    return missing


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("tv", "mobile"), default="tv")
    parser.add_argument("--assets", type=Path, help="Verify prepared files and certificate continuity")
    parser.add_argument("--upload", action="store_true", help="Upload missing files, reuse byte-identical published files")
    args = parser.parse_args()
    if args.upload and args.assets is None:
        parser.error("--upload requires --assets")
    version = (ROOT / "VERSION").read_text().strip()
    tag = os.environ["RELEASE_TAG"]
    if tag != "v" + version:
        raise ValueError("Release tag must equal v + root VERSION")
    parts(version)
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid release repository")
    base = "https://api.github.com/repos/" + repository
    releases = []
    page = 1
    while True:
        values = json.loads(fetch(base + f"/releases?per_page=100&page={page}", 4 << 20))
        if not isinstance(values, list):
            raise ValueError("Invalid GitHub release listing")
        if not values:
            break
        releases.extend(values)
        page += 1
    def read_asset(item, maximum):
        raw = fetch(base + "/releases/assets/" + str(item["id"]), maximum, binary=True)
        if len(raw) != item.get("size"):
            raise ValueError("Published asset download is incomplete")
        return raw
    candidate = json.loads((args.assets / "release.json").read_text()) if args.assets else None
    current = release_plan(version, tag, releases, candidate, read_asset, args.platform)
    if args.assets:
        missing = reusable_assets(current, args.assets, read_asset, args.platform)
        if args.upload and missing:
            subprocess.run(["gh", "release", "upload", tag, *map(str, missing)], check=True)
        print("Validated signing continuity and immutable release assets")
    else:
        print("Validated release version " + version)


if __name__ == "__main__":
    main()
