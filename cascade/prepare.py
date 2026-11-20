"""Apply the distribution to an exact upstream checkout; incompatible source stops the build."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path
from xml.sax.saxutils import escape

HERE = Path(__file__).resolve().parent
JAVA = Path("V2rayNG/app/src/main/java/com/v2ray/ang")
TEST = Path("V2rayNG/app/src/test/java/com/v2ray/ang")


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def kotlin_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) != 1:
        raise ValueError(f"Upstream changed {label}; review required (expected one anchor)")
    return text.replace(old, new, 1)


def replace_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0 or text.count(signature) != 1:
        raise ValueError(f"Upstream changed {signature}; review required")
    opening = text.find("{", start)
    depth = 1
    end = opening + 1
    # The upstream functions matched here contain no braces in string literals.
    while depth and end < len(text):
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    if depth:
        raise ValueError(f"Unbalanced upstream function: {signature}")
    return text[:start] + replacement + text[end:]


def load_policy():
    policy = read_json(HERE / "policy/policy.json")
    if policy.get("schema") != 1:
        raise ValueError("Unsupported policy schema")
    for name, digest in policy["files"].items():
        if name not in {"routing.json", "apps.json"}:
            raise ValueError("Unexpected policy file")
        if hashlib.sha256((HERE / "policy" / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"Shared policy checksum mismatch: {name}")
    rules = read_json(HERE / "policy/routing.json")
    apps = read_json(HERE / "policy/apps.json")
    settings = read_json(HERE / "policy/settings.json")
    expected_settings = read_json(HERE / "distribution.json")["settings_sha256"]
    if hashlib.sha256((HERE / "policy/settings.json").read_bytes()).hexdigest() != expected_settings:
        raise ValueError("Android settings checksum mismatch")
    if len(rules) != policy["routing_count"] or len({r["id"] for r in rules}) != len(rules):
        raise ValueError("Invalid routing rule count or identity")
    if rules[-1].get("outboundTag") != "direct" or rules[-1].get("port") != "0-65535":
        raise ValueError("Unexpected routing fallback")
    if len(set(apps)) != len(apps) or not apps:
        raise ValueError("Invalid application list")
    if any(not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", app) for app in apps):
        raise ValueError("Invalid Android package")
    for key, item in settings.items():
        if not re.fullmatch(r"pref_[a-z0-9_]+", key) or any(word in key for word in ("password", "username", "subscription", "webdav")):
            raise ValueError("Personal data is not a distribution default")
        expected = {"b": bool, "s": str, "i": int}.get(item["type"])
        if expected is None or type(item["value"]) is not expected:
            raise ValueError(f"Invalid typed setting: {key}")
    return policy, rules, apps, settings


def prepare(source: Path, upstream_tag: str, config_path: Path | None = None) -> dict:
    config = read_json(config_path or HERE / "distribution.json")
    if config.get("schema") != 1 or type(config["revision"]) is not int or not 1 <= config["revision"] <= 999:
        raise ValueError("Distribution revision must be between 1 and 999")
    if not re.fullmatch(r"v?\d+\.\d+\.\d+", upstream_tag):
        raise ValueError("Expected a stable upstream tag")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", config["repository"]):
        raise ValueError("Invalid fork repository")
    for key in ("upstream_repository", "geo_repository"):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", config[key]):
            raise ValueError(f"Invalid {key}")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", config["application_id"]):
        raise ValueError("Invalid application ID")
    policy, rules, apps, settings = load_policy()
    version = upstream_tag.removeprefix("v") + "." + str(config["revision"])
    gradle_path = source / "V2rayNG/app/build.gradle.kts"
    gradle = gradle_path.read_text(encoding="utf-8")
    version_match = re.search(r'versionName = "([^"]+)"', gradle)
    code_match = re.search(r"versionCode = (\d+)", gradle)
    if not version_match or version_match[1] != upstream_tag.removeprefix("v") or not code_match:
        raise ValueError("Source version does not match the selected stable release")
    version_code = int(code_match[1]) * 1000 + config["revision"]
    gradle = replace_once(gradle, 'applicationId = "com.v2ray.ang"', f'applicationId = {kotlin_string(config["application_id"])}', "application ID")
    gradle = replace_once(gradle, version_match[0], f'versionName = "{version}"', "versionName")
    gradle = replace_once(gradle, code_match[0], f"versionCode = {version_code}", "versionCode")

    generated_settings = []
    for key, item in settings.items():
        value = kotlin_string(item["value"]) if item["type"] == "s" else json.dumps(item["value"])
        generated_settings.append(f"        {kotlin_string(key)} to {value},")
    generated_settings.append('        "pref_routing_ruleset" to ' + kotlin_string(json.dumps(rules, ensure_ascii=False, separators=(",", ":"))) + ",")
    generated_settings.append('        "pref_per_app_proxy_set" to setOf(' + ", ".join(map(kotlin_string, apps)) + "),")
    geo_base = f'https://github.com/{config["geo_repository"]}/releases/latest/download/'
    asset_lines = [f'        "{name}" to "{geo_base}{name}",' for name in ("geoip.dat", "geosite.dat")]
    defaults = (HERE / "kotlin/CascadeDefaults.kt.in").read_text(encoding="utf-8")
    defaults = defaults.replace("@SETTINGS@", "\n".join(generated_settings)).replace("@ASSETS@", "\n".join(asset_lines))

    mmkv_path = source / JAVA / "handler/MmkvManager.kt"
    mmkv = mmkv_path.read_text(encoding="utf-8")
    if "ensureCascadeDefaults" in mmkv:
        raise ValueError("Distribution has already been applied")
    closing = mmkv.rfind("}")
    if closing < 0 or "private val assetStorage" not in mmkv or "private val settingsStorage" not in mmkv:
        raise ValueError("Upstream MMKV ownership changed; review required")
    mmkv = mmkv[:closing] + (HERE / "kotlin/MmkvCascade.kt.inc").read_text(encoding="utf-8") + mmkv[closing:]

    settings_path = source / JAVA / "handler/SettingsManager.kt"
    manager = settings_path.read_text(encoding="utf-8")
    manager = replace_once(manager, "fun initApp(context: Context) {", "fun initApp(context: Context) {\n        MmkvManager.ensureCascadeDefaults()", "settings initialization")
    manager = replace_function(manager, "    fun initAssets(context: Context, assets: AssetManager)", '''    @Synchronized
    fun initAssets(context: Context, assets: AssetManager) {
        val extFolder = Utils.userAssetPath(context)
        MmkvManager.ensureCascadeAssets()
        val geo = setOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)
        assets.list("")?.filter { it in geo }?.forEach { name ->
            try {
                CascadeDefaults.copyMissingAsset(File(extFolder, name)) { assets.open(name) }
            } catch (error: Exception) {
                LogUtil.e(ANG_PACKAGE, "Distribution asset repair failed: $name", error)
            }
        }
    }''')

    app_config_path = source / JAVA / "AppConfig.kt"
    app_config = app_config_path.read_text(encoding="utf-8")
    app_config = replace_once(app_config, 'const val APP_URL = "$GITHUB_URL/2dust/v2rayNG"', f'const val APP_URL = "$GITHUB_URL/{config["repository"]}"', "project URL")
    app_config = replace_once(app_config, 'const val APP_API_URL = "https://api.github.com/repos/2dust/v2rayNG/releases"', f'const val APP_API_URL = "https://api.github.com/repos/{config["repository"]}/releases"', "update URL")

    service_path = source / JAVA / "service/CoreVpnService.kt"
    service = service_path.read_text(encoding="utf-8")
    service = replace_once(service, "        configurePerAppProxy(builder)", "        if (!configurePerAppProxy(builder)) return false", "per-app start guard")
    service = replace_function(service, "    private fun configurePerAppProxy(builder: Builder)", '''    private fun configurePerAppProxy(builder: Builder): Boolean {
        fun addApplication(name: String, allowed: Boolean): Boolean = try {
            if (allowed) builder.addAllowedApplication(name) else builder.addDisallowedApplication(name)
            true
        } catch (error: PackageManager.NameNotFoundException) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: package unavailable: $name", error)
            false
        }
        val configured = configureSelectedApplications(
            enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY),
            bypass = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS),
            selected = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty(),
            self = BuildConfig.APPLICATION_ID,
            addAllowed = { addApplication(it, true) },
            addDisallowed = { addApplication(it, false) }
        )
        if (!configured) LogUtil.e(AppConfig.TAG, "StartCore-VPN: no usable application routing selection")
        return configured
    }''')

    # Validate all anchors before touching the checkout.
    for path, content in ((gradle_path, gradle), (mmkv_path, mmkv), (settings_path, manager), (app_config_path, app_config), (service_path, service)):
        path.write_text(content, encoding="utf-8", newline="\n")
    (source / JAVA / "handler/CascadeDefaults.kt").write_text(defaults, encoding="utf-8", newline="\n")
    for template, destination in (
        ("PerAppRouting.kt", JAVA / "service/PerAppRouting.kt"),
        ("CascadeDefaultsTest.kt", TEST / "handler/CascadeDefaultsTest.kt"),
        ("PerAppRoutingTest.kt", TEST / "service/PerAppRoutingTest.kt"),
        ("CascadeBootstrapTest.kt", Path("V2rayNG/app/src/androidTest/java/com/v2ray/ang/handler/CascadeBootstrapTest.kt")),
    ):
        target = source / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(HERE / "kotlin" / template, target)
    for strings in (source / "V2rayNG/app/src/main/res").glob("values*/strings.xml"):
        original = strings.read_text(encoding="utf-8")
        updated = re.sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)', lambda m: m[1] + escape(config["name"]) + m[2], original)
        if updated != original:
            strings.write_text(updated, encoding="utf-8", newline="\n")
    report = {
        "schema": 1, "name": config["name"], "application_id": config["application_id"],
        "version": version, "base_version_code": version_code, "upstream_tag": upstream_tag,
        "policy_version": policy["version"], "routing_count": len(rules), "application_count": len(apps),
        "routing_sha256": policy["files"]["routing.json"], "apps_sha256": policy["files"]["apps.json"],
        "settings_sha256": config["settings_sha256"],
        "profile_count": 0, "asset_repair": "missing files and entries only; preserve existing contents",
    }
    (source / "cascade-build.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--upstream-tag", required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.source.resolve(), args.upstream_tag), ensure_ascii=False))
