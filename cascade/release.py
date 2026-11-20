"""Resolve stable releases, verify downloaded inputs, and inspect the resulting APKs."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

from prepare import HERE, read_json


def request(url: str, *, api: bool = False) -> bytes:
    headers = {"User-Agent": "Cascade-VPN-build", "Accept": "application/vnd.github+json" if api else "*/*"}
    if api:
        if urllib.parse.urlsplit(url).hostname != "api.github.com":
            raise ValueError("GitHub authorization must remain on api.github.com")
        if os.environ.get("GH_TOKEN"):
            headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=90) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code < 500 or attempt == 2:
                raise
        except (TimeoutError, urllib.error.URLError):
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)
    raise RuntimeError("Download retries exhausted")


def api(path: str):
    return json.loads(request("https://api.github.com/" + path, api=True))


def stable_release(repository: str, tag: str = ""):
    suffix = "tags/" + urllib.parse.quote(tag, safe="") if tag else "latest"
    release = api(f"repos/{repository}/releases/{suffix}")
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("Only published stable upstream releases are supported")
    return release


def download_asset(release: dict, name: str, destination: Path) -> dict:
    matches = [item for item in release["assets"] if item["name"] == name]
    if len(matches) != 1:
        raise ValueError(f"Expected one upstream release asset: {name}")
    asset = matches[0]
    url = asset["browser_download_url"]
    if not url.startswith("https://github.com/"):
        raise ValueError("Unexpected release download host")
    content = request(url)
    digest = hashlib.sha256(content).hexdigest()
    if len(content) != asset["size"]:
        raise ValueError(f"Release asset size mismatch: {name}")
    expected = asset.get("digest")
    if expected and expected != "sha256:" + digest:
        raise ValueError(f"Release asset digest mismatch: {name}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".download")
    temporary.write_bytes(content)
    temporary.replace(destination)
    return {"name": name, "url": url, "sha256": digest, "size": len(content)}


def plan(tag: str, output: Path):
    config = read_json(HERE / "distribution.json")
    release = stable_release(config["upstream_repository"], tag)
    upstream_tag = release["tag_name"]
    if not re.fullmatch(r"v?\d+\.\d+\.\d+", upstream_tag):
        raise ValueError("Unsupported stable tag format")
    version = upstream_tag.removeprefix("v") + "." + str(config["revision"])
    release_tag = "v" + version
    build = True
    try:
        existing = api(f'repos/{config["repository"]}/releases/tags/{release_tag}')
        build = bool(existing.get("draft"))
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    result = {"upstream_tag": upstream_tag, "version": version, "release_tag": release_tag, "build": build}
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as handle:
            for key, value in result.items():
                handle.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    print(json.dumps(result))


def inputs(source: Path):
    config = read_json(HERE / "distribution.json")
    report = read_json(source / "cascade-build.json")
    report["upstream_commit"] = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    geo = stable_release(config["geo_repository"])
    metadata = []
    for name in ("geoip.dat", "geosite.dat"):
        destination = source / "V2rayNG/app/src/main/assets" / name
        asset = download_asset(geo, name, destination)
        checksum = download_asset(geo, name + ".sha256sum", source / (name + ".sha256sum"))
        checksum_text = (source / (name + ".sha256sum")).read_text(encoding="utf-8").strip()
        match = re.fullmatch(r"([a-fA-F0-9]{64})\s+\*?" + re.escape(name), checksum_text)
        if not match or match[1].lower() != asset["sha256"]:
            raise ValueError(f"Geodata checksum does not match {name}")
        metadata.extend((asset, checksum))
    report["geo_tag"] = geo["tag_name"]
    report["geo_assets"] = metadata

    native = source / "AndroidLibXrayLite"
    gitlink = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD:AndroidLibXrayLite"], text=True).strip()
    actual = subprocess.check_output(["git", "-C", str(native), "rev-parse", "HEAD"], text=True).strip()
    if gitlink != actual:
        raise ValueError("Native submodule does not match upstream gitlink")
    native_tag = subprocess.check_output(["git", "-C", str(native), "describe", "--tags", "--exact-match"], text=True).strip()
    native_release = api("repos/2dust/AndroidLibXrayLite/releases/tags/" + urllib.parse.quote(native_tag, safe=""))
    if native_release.get("draft"):
        raise ValueError("The pinned native release is still a draft")
    report["native_commit"] = gitlink
    report["native_tag"] = native_tag
    report["native_asset"] = download_asset(native_release, "libv2ray.aar", source / "V2rayNG/app/libs/libv2ray.aar")
    (source / "cascade-build.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"geo_tag": report["geo_tag"], "native_tag": native_tag, "native_commit": gitlink}))


def package(source: Path, output: Path):
    report = read_json(source / "cascade-build.json")
    output.mkdir(parents=True, exist_ok=True)
    abis = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
    expected = abis | {"universal"}
    apks = list((source / "V2rayNG/app/build/outputs/apk/playstore/release").glob("*.apk"))
    seen = set()
    for apk in apks:
        abi = next((item for item in sorted(expected, key=len, reverse=True) if apk.stem.endswith("_" + item)), None)
        if abi is None or abi in seen:
            raise ValueError(f"Unexpected or duplicate APK: {apk.name}")
        seen.add(abi)
        with zipfile.ZipFile(apk) as archive:
            members = set(archive.namelist())
            for architecture in abis if abi == "universal" else {abi}:
                for library in ("libgojni.so", "libhev-socks5-tunnel.so", "libhevsockstun.so"):
                    if f"lib/{architecture}/{library}" not in members:
                        raise ValueError(f"Missing native library {architecture}/{library}")
            for name in ("geoip.dat", "geosite.dat"):
                digest = hashlib.sha256(archive.read("assets/" + name)).hexdigest()
                expected_digest = next(item["sha256"] for item in report["geo_assets"] if item["name"] == name)
                if digest != expected_digest:
                    raise ValueError(f"Packaged geodata changed: {name}")
        shutil.copyfile(apk, output / f'CascadeVPN_{report["version"]}_{abi}.apk')
    if seen != expected:
        raise ValueError(f"APK set incomplete: {seen}")
    report["validation"] = {
        "unit_tests": "passed", "debug_assembly": "passed", "native_abis": sorted(abis),
        "packaged_geo_sha256": "passed", "live_vpn_connection": "Not run: requires a user's server profile and physical-device acceptance",
    }
    (output / "build-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    with zipfile.ZipFile(output / "source.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for root in (source / "V2rayNG",):
            for file in root.rglob("*"):
                if not file.is_file() or any(part in {".gradle", "build", "libs"} for part in file.relative_to(source).parts) or file.name == "local.properties":
                    continue
                archive.write(file, file.relative_to(source).as_posix())
        archive.write(source / "cascade-build.json", "cascade-build.json")
    checksums = []
    for file in sorted(output.iterdir()):
        if file.name == "SHA256SUMS" or not file.is_file():
            continue
        checksums.append(hashlib.sha256(file.read_bytes()).hexdigest() + "  " + file.name)
    (output / "SHA256SUMS").write_text("\n".join(checksums) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    plan_parser = commands.add_parser("plan")
    plan_parser.add_argument("--tag", default="")
    plan_parser.add_argument("--output", type=Path, default=Path("plan.json"))
    inputs_parser = commands.add_parser("inputs")
    inputs_parser.add_argument("--source", type=Path, required=True)
    package_parser = commands.add_parser("package")
    package_parser.add_argument("--source", type=Path, required=True)
    package_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "plan":
        plan(args.tag, args.output)
    elif args.command == "inputs":
        inputs(args.source.resolve())
    else:
        package(args.source.resolve(), args.output.resolve())
