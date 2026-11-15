#!/usr/bin/env python3
"""
BitChord / V-Tune GitHub Release Automation Agent

Responsibilities:
- Detect project version from root Gradle configuration.
- Discover and validate release APK artifacts.
- Prepare consistently named release assets.
- Generate release notes from git history (since previous tag when available).
- Check for existing tags/releases to prevent duplicates.
- Determine whether a release is required and report a definitive go/no-go decision.
- Optionally create/push tag and publish a GitHub Release via `gh`.
- Validate release state after publish.

Safety defaults:
- Dry-run by default (no tag/release mutation unless --publish).
- Publish is strictly opt-in and refused unless the release decision is GO.
- Never overwrites existing tags/releases/assets.
- Refuses to publish incomplete, invalid, or duplicate releases.
- Does not perform destructive git operations.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Sequence

ROOT = Path(__file__).resolve().parent.parent
BUILD_GRADLE = ROOT / "build.gradle.kts"
DEFAULT_DIST_DIR = ROOT / "dist" / "release"
APK_PREFIX = "V-Tune-"  # must match app/build.gradle.kts UPDATE_APK_PREFIX


class ReleaseError(RuntimeError):
    pass


@dataclass
class VersionInfo:
    version_name: str
    version_code: int
    beta_suffix: str = ""
    tag: str = ""
    display_version: str = ""

    def __post_init__(self) -> None:
        suffix = f"-{self.beta_suffix}" if self.beta_suffix else ""
        self.display_version = f"{self.version_name}{suffix}"
        if not self.tag:
            self.tag = f"v{self.display_version}"


@dataclass
class ApkAsset:
    module: str
    source_path: str
    release_name: str
    abi: str
    build_type: str
    size_bytes: int
    sha256: str


def log(level: str, msg: str) -> None:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    print(f"[{ts}] [{level}] {msg}", flush=True)


def run(cmd: Sequence[str], cwd: Optional[Path] = None, check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
    log("CMD", " ".join(cmd))
    cp = subprocess.run(
        list(cmd),
        cwd=str(cwd or ROOT),
        text=True,
        capture_output=capture,
    )
    if capture:
        if cp.stdout:
            sys.stdout.write(cp.stdout if cp.stdout.endswith("\n") else cp.stdout + "\n")
        if cp.stderr:
            sys.stderr.write(cp.stderr if cp.stderr.endswith("\n") else cp.stderr + "\n")
    if check and cp.returncode != 0:
        raise ReleaseError(f"Command failed ({cp.returncode}): {' '.join(cmd)}")
    return cp


def _read_gradle_default_version() -> VersionInfo:
    if not BUILD_GRADLE.exists():
        raise ReleaseError(f"Missing {BUILD_GRADLE}")
    text = BUILD_GRADLE.read_text(encoding="utf-8")

    def grab(pattern: str, cast=int, default=None):
        m = re.search(pattern, text)
        if not m:
            if default is not None:
                return default
            raise ReleaseError(f"Could not parse version field with pattern: {pattern}")
        return cast(m.group(1))

    # Support both hardcoded and property-overridden forms.
    version_code = grab(r'extra\["bitchordVersionCode"\]\s*=\s*(?:\(findProperty\([^\n]*?:\s*)?(\d+)\)?', int)
    version_name = grab(r'extra\["bitchordVersionName"\]\s*=\s*(?:\(findProperty\([^\n]*?:\s*)?"([^"]+)"\)?', str)
    beta_m = re.search(r'extra\["bitchordBetaSuffix"\]\s*=\s*(?:\(findProperty\([^\n]*?:\s*)?"([^"]*)"\)?', text)
    beta_suffix = beta_m.group(1).strip() if beta_m else ""
    return VersionInfo(version_name=version_name, version_code=version_code, beta_suffix=beta_suffix)


def read_version(
    override_name: Optional[str] = None,
    override_code: Optional[int] = None,
    override_suffix: Optional[str] = None,
    auto_version: bool = False,
    auto_build_id: Optional[str] = None,
) -> VersionInfo:
    base = _read_gradle_default_version()
    version_name = (override_name or base.version_name).strip()
    version_code = int(override_code) if override_code is not None else int(base.version_code)
    beta_suffix = base.beta_suffix if override_suffix is None else str(override_suffix).strip()

    if auto_version:
        build_id = (auto_build_id or os.getenv("GITHUB_RUN_NUMBER") or os.getenv("GITHUB_RUN_ID") or "").strip()
        if not build_id:
            build_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        try:
            sha = (git("rev-parse", "--short", "HEAD", check=False).stdout or "").strip()
        except Exception:
            sha = ""
        # Unique per-build version while keeping base lineage.
        version_name = f"{base.version_name}.{build_id}"
        try:
            run_num = int(re.sub(r"\D", "", str(build_id)) or "0")
            if run_num > 0:
                version_code = max(version_code, run_num, 1)
        except Exception:
            version_code = max(version_code, 1)
        suffix_id = f"ci.{build_id}" + (f".{sha}" if sha else "")
        if override_suffix is None:
            beta_suffix = suffix_id if not beta_suffix else f"{beta_suffix}.{build_id}" + (f".{sha}" if sha else "")

    if not re.match(r"^\d+\.\d+\.\d+(?:[.\-][0-9A-Za-z.\-]+)?$", version_name):
        raise ReleaseError(f"Invalid versionName format: {version_name}")
    if version_code <= 0:
        raise ReleaseError(f"Invalid versionCode: {version_code}")
    if beta_suffix and not re.match(r"^[0-9A-Za-z.\-]+$", beta_suffix):
        raise ReleaseError(f"Invalid beta suffix: {beta_suffix}")

    info = VersionInfo(version_name=version_name, version_code=version_code, beta_suffix=beta_suffix)
    mode = "auto" if auto_version else "configured"
    log("INFO", f"Resolved version ({mode}): name={info.display_version} code={version_code} tag={info.tag}")
    return info


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def infer_abi(filename: str) -> str:
    name = filename.lower()
    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64", "universal"):
        if abi in name:
            return abi
    return "unknown"


def find_apks(search_dirs: List[Path], modules: List[str]) -> List[Path]:
    found: List[Path] = []
    for d in search_dirs:
        if not d.exists():
            continue
        for p in d.rglob("*.apk"):
            if p.is_file():
                found.append(p)
    uniq = sorted({p.resolve() for p in found})
    if modules:
        module_set = {m.lower() for m in modules}
        filtered = []
        for p in uniq:
            parts = {part.lower() for part in p.parts}
            if module_set.intersection(parts):
                filtered.append(p)
            elif detect_module(p) in module_set:
                filtered.append(p)
        uniq = sorted(set(filtered))
    return uniq


def detect_module(path: Path) -> str:
    name = path.name
    if re.search(rf"^{re.escape(APK_PREFIX)}WearOS-", name):
        return "wearos"
    if re.search(rf"^{re.escape(APK_PREFIX)}TV-", name):
        return "tv"
    if name.startswith(APK_PREFIX):
        return "app"
    parts = [p.lower() for p in path.parts]
    for m in ("app", "tv", "wearos", "wear"):
        if m in parts:
            return "wearos" if m == "wear" else m
    return "unknown"


def detect_build_type(path: Path) -> str:
    s = str(path).lower()
    if "release" in s:
        return "release"
    if "debug" in s:
        return "debug"
    if "benchmark" in s:
        return "benchmark"
    return "unknown"


def make_release_name(version: VersionInfo, module: str, abi: str, source_name: str) -> str:
    label = {
        "app": "",
        "tv": "TV-",
        "wearos": "WearOS-",
    }.get(module, f"{module}-")
    return f"{APK_PREFIX}{label}{version.display_version}-{abi}.apk"


def prepare_assets(
    version: VersionInfo,
    apk_paths: List[Path],
    dist_dir: Path,
    include_modules: List[str],
    require_signed: bool = True,
    allowed_build_types: Optional[List[str]] = None,
) -> List[ApkAsset]:
    if dist_dir.exists():
        shutil.rmtree(dist_dir)
    dist_dir.mkdir(parents=True, exist_ok=True)

    allowed = {b.lower() for b in (allowed_build_types or ["release"])}
    assets: List[ApkAsset] = []
    seen_names: Dict[str, Path] = {}
    include_set = {m.lower() for m in include_modules}

    for src in apk_paths:
        module = detect_module(src)
        build_type = detect_build_type(src)
        if module.lower() not in include_set:
            continue
        if build_type.lower() not in allowed:
            log("SKIP", f"Skipping APK due to build type ({build_type} not in {sorted(allowed)}): {src}")
            continue
        name_lower = src.name.lower()
        if require_signed and "unsigned" in name_lower:
            raise ReleaseError(f"Unsigned APK is not publishable: {src}")
        abi = infer_abi(src.name)
        if abi == "unknown":
            log("WARN", f"Could not infer ABI for {src.name}; asset naming may be ambiguous")

        rel_name = make_release_name(version, module, abi, src.name)
        if rel_name in seen_names and seen_names[rel_name] != src:
            raise ReleaseError(f"Asset name collision for {rel_name}: {seen_names[rel_name]} vs {src}")
        seen_names[rel_name] = src

        dst = dist_dir / rel_name
        shutil.copy2(src, dst)
        digest = sha256_file(dst)
        size = dst.stat().st_size
        assets.append(
            ApkAsset(
                module=module,
                source_path=str(src),
                release_name=rel_name,
                abi=abi,
                build_type=build_type,
                size_bytes=size,
                sha256=digest,
            )
        )
        log("ASSET", f"Prepared {rel_name} ({size} bytes) from {src}")

    if not assets:
        raise ReleaseError("No valid APK assets found")
    return sorted(assets, key=lambda a: (a.module, a.abi, a.release_name))


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return run(["git", *args], check=check)


def tag_exists(tag: str) -> bool:
    cp = git("tag", "--list", tag, check=False)
    return (cp.stdout or "").strip() == tag


def remote_tag_exists(tag: str) -> bool:
    cp = git("ls-remote", "--tags", "origin", f"refs/tags/{tag}", check=False)
    return bool((cp.stdout or "").strip())


def gh_release_exists(tag: str) -> bool:
    cp = run(["gh", "release", "view", tag], check=False)
    return cp.returncode == 0


def check_release_availability(version: VersionInfo) -> Dict[str, object]:
    local_tag = tag_exists(version.tag)
    remote_tag = remote_tag_exists(version.tag)
    gh_release = gh_release_exists(version.tag)
    return {
        "tag": version.tag,
        "local_tag_exists": local_tag,
        "remote_tag_exists": remote_tag,
        "github_release_exists": gh_release,
        "not_already_released": not (local_tag or remote_tag or gh_release),
    }


def get_previous_tag(current_tag: str) -> Optional[str]:
    cp = git("tag", "--sort=-creatordate", check=False)
    tags = [t.strip() for t in (cp.stdout or "").splitlines() if t.strip()]
    for t in tags:
        if t != current_tag:
            return t
    return None


def generate_release_notes(version: VersionInfo, previous_tag: Optional[str]) -> str:
    header = [f"# V-Tune {version.display_version}", ""]
    meta = [
        f"- Version name: `{version.display_version}`",
        f"- Version code: `{version.version_code}`",
        f"- Tag: `{version.tag}`",
        "",
    ]

    changes: List[str] = []
    if previous_tag:
        log("INFO", f"Generating notes from {previous_tag}..HEAD")
        cp = git("log", "--pretty=format:- %s (%h)", f"{previous_tag}..HEAD", check=False)
    else:
        log("INFO", "No previous tag found; using recent commits for notes")
        cp = git("log", "--pretty=format:- %s (%h)", "-n", "30", check=False)
    for line in (cp.stdout or "").splitlines():
        s = line.strip()
        if s:
            changes.append(s)
    if not changes:
        changes = ["- Maintenance release."]

    features, fixes, improvements, other = [], [], [], []
    for c in changes:
        l = c.lower()
        if re.search(r"\b(feat|add|added|new)\b", l):
            features.append(c)
        elif re.search(r"\b(fix|fixed|bug|hotfix|patch)\b", l):
            fixes.append(c)
        elif re.search(r"\b(improve|improved|refactor|perf|update|updated|tweak)\b", l):
            improvements.append(c)
        else:
            other.append(c)

    sections: List[str] = []

    def add_section(title: str, items: List[str]) -> None:
        if items:
            sections.append(f"## {title}")
            sections.extend(items)
            sections.append("")

    add_section("Highlights", features[:20])
    add_section("Improvements", improvements[:30])
    add_section("Bug Fixes", fixes[:30])
    if not (features or improvements or fixes):
        sections.append("## Changes")
        sections.extend(other[:50])
        sections.append("")
    elif other:
        add_section("Other Changes", other[:30])

    return "\n".join(header + meta + sections).strip() + "\n"


def write_manifest(dist_dir: Path, version: VersionInfo, assets: List[ApkAsset], notes_path: Path) -> Path:
    manifest = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "version": asdict(version),
        "assets": [asdict(a) for a in assets],
        "notes_file": str(notes_path),
    }
    path = dist_dir / "release-manifest.json"
    path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return path


def ensure_clean_release_state(version: VersionInfo, allow_existing: bool = False) -> None:
    errors = []
    if tag_exists(version.tag):
        errors.append(f"Local git tag already exists: {version.tag}")
    if remote_tag_exists(version.tag):
        errors.append(f"Remote git tag already exists on origin: {version.tag}")
    if gh_release_exists(version.tag):
        errors.append(f"GitHub release already exists: {version.tag}")
    if errors and not allow_existing:
        raise ReleaseError("Duplicate release detected:\n- " + "\n- ".join(errors))
    for e in errors:
        log("WARN", e)


def validate_assets_for_publish(assets: List[ApkAsset], version: VersionInfo) -> None:
    if not assets:
        raise ReleaseError("No assets to publish")
    names = [a.release_name for a in assets]
    if len(names) != len(set(names)):
        raise ReleaseError("Duplicate asset names detected")
    for a in assets:
        if a.size_bytes <= 0:
            raise ReleaseError(f"Empty asset: {a.release_name}")
        if not re.match(r"^[A-Za-z0-9._+-]+\.apk$", a.release_name):
            raise ReleaseError(f"Unsafe asset filename: {a.release_name}")
    app_assets = [a for a in assets if a.module == "app"]
    if not app_assets:
        raise ReleaseError("No main app release APK found; refusing to publish incomplete release")
    if not any(
        a.release_name.startswith(APK_PREFIX) and "TV-" not in a.release_name and "WearOS-" not in a.release_name
        for a in app_assets
    ):
        raise ReleaseError("Main app APK naming/prefix validation failed")
    for a in assets:
        if version.display_version not in a.release_name:
            raise ReleaseError(f"Asset does not contain version {version.display_version}: {a.release_name}")


def ensure_gh_available() -> None:
    cp = run(["gh", "--version"], check=False)
    if cp.returncode != 0:
        raise ReleaseError("GitHub CLI `gh` is required for publish/validation but was not found")


def ensure_git_identity() -> None:
    name = git("config", "user.name", check=False).stdout.strip()
    email = git("config", "user.email", check=False).stdout.strip()
    if not name or not email:
        raise ReleaseError("Git user.name/user.email must be configured for tagging operations")


def publish_release(
    version: VersionInfo,
    dist_dir: Path,
    assets: List[ApkAsset],
    notes_path: Path,
    target: str,
    prerelease: bool,
    draft: bool,
) -> None:
    ensure_gh_available()
    ensure_git_identity()
    ensure_clean_release_state(version)

    git("tag", "-a", version.tag, "-m", f"Release {version.tag}")
    try:
        git("push", "origin", version.tag)
    except Exception:
        log("ERROR", "Failed to push tag to origin; local tag remains. Resolve manually to avoid duplicate tag state.")
        raise

    cmd = [
        "gh",
        "release",
        "create",
        version.tag,
        "--title",
        f"V-Tune {version.display_version}",
        "--notes-file",
        str(notes_path),
        "--target",
        target,
    ]
    if prerelease:
        cmd.append("--prerelease")
    if draft:
        cmd.append("--draft")
    for a in assets:
        cmd.append(str(dist_dir / a.release_name))
    run(cmd)

    validate_published_release(version, assets)


def validate_published_release(version: VersionInfo, assets: List[ApkAsset]) -> None:
    ensure_gh_available()
    cp = run(
        ["gh", "release", "view", version.tag, "--json", "tagName,name,assets,isDraft,isPrerelease,url"],
        check=True,
    )
    data = json.loads(cp.stdout or "{}")
    if data.get("tagName") != version.tag:
        raise ReleaseError(f"Published release tag mismatch: {data.get('tagName')} != {version.tag}")
    remote_assets = {a.get("name") for a in data.get("assets", []) if a.get("name")}
    missing = [a.release_name for a in assets if a.release_name not in remote_assets]
    if missing:
        raise ReleaseError("Release published but missing assets: " + ", ".join(missing))
    log("INFO", f"Release validated successfully: {data.get('url', version.tag)}")


def detect_repo_changes(base_ref: Optional[str]) -> Dict[str, object]:
    status = git("status", "--porcelain", check=False).stdout or ""
    changed = [line[3:].strip() for line in status.splitlines() if line.strip()]
    result: Dict[str, object] = {"working_tree_changed": changed, "diff_vs_base": []}
    if base_ref:
        cp = git("diff", "--name-status", f"{base_ref}...HEAD", check=False)
        result["diff_vs_base"] = [l.strip() for l in (cp.stdout or "").splitlines() if l.strip()]
    return result


def _normalize_change_path(entry: str) -> str:
    parts = [p.strip() for p in entry.split("\t") if p.strip()]
    if not parts:
        return ""
    path = parts[-1] if len(parts) >= 2 else parts[0]
    if len(parts) == 1 and " " in parts[0]:
        path = parts[0].split(None, 1)[-1]
    return path.replace("\\", "/").lstrip("./")


def evaluate_release_relevance(changes: Dict[str, object], base_ref: Optional[str]) -> Dict[str, object]:
    diff_entries = changes.get("diff_vs_base") or []
    working = changes.get("working_tree_changed") or []
    diff_paths = [_normalize_change_path(str(e)) for e in diff_entries if _normalize_change_path(str(e))]
    working_paths = [str(p).replace("\\", "/").strip().lstrip("./") for p in working if str(p).strip()]

    relevant_prefixes = (
        "app/",
        "tv/",
        "wearos/",
        "shared/",
        "sharedUi/",
        "native/",
        "gradle/",
        "backend/",
        "web/",
        "docs/",
        "scripts/release_agent.py",
        ".github/workflows/release.yml",
        ".github/workflows/android.yml",
        ".github/workflows/tv.yml",
        ".github/workflows/check.yml",
    )
    relevant_exact = {
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "keystore.properties.example",
    }

    def is_relevant(path: str) -> bool:
        p = path.lstrip("./")
        if p in relevant_exact:
            return True
        return any(p == prefix.rstrip("/") or p.startswith(prefix) for prefix in relevant_prefixes)

    relevant_diff = sorted({p for p in diff_paths if is_relevant(p)})
    relevant_working = sorted({p for p in working_paths if is_relevant(p)})
    version_diff = sorted({p for p in diff_paths if p == "build.gradle.kts"})
    version_working = sorted({p for p in working_paths if p == "build.gradle.kts"})

    if base_ref:
        relevant_changes_detected = len(relevant_diff) > 0
        evidence_source = f"git diff {base_ref}...HEAD"
        relevant_paths = relevant_diff
        version_paths = version_diff
    else:
        relevant_changes_detected = len(relevant_working) > 0
        evidence_source = "working tree (no --base-ref provided)"
        relevant_paths = relevant_working
        version_paths = version_working

    return {
        "base_ref": base_ref,
        "evidence_source": evidence_source,
        "relevant_changes_detected": relevant_changes_detected,
        "version_config_changed": "build.gradle.kts" in version_paths,
        "relevant_paths": relevant_paths,
        "version_paths": version_paths,
        "relevant_working_tree_paths": relevant_working,
    }


def evaluate_release_decision(
    version: VersionInfo,
    availability: Dict[str, object],
    relevance: Dict[str, object],
    assets: List[ApkAsset],
    asset_error: Optional[str],
    publish_requested: bool,
) -> Dict[str, object]:
    reasons: List[str] = []
    blockers: List[str] = []

    not_already_released = bool(availability.get("not_already_released"))
    if not_already_released:
        reasons.append(f"Target version/tag {version.tag} is not already released (no local/remote tag or GitHub release found).")
    else:
        existing = []
        if availability.get("local_tag_exists"):
            existing.append("local tag")
        if availability.get("remote_tag_exists"):
            existing.append("origin tag")
        if availability.get("github_release_exists"):
            existing.append("GitHub release")
        blockers.append(f"Version/tag {version.tag} already exists: {', '.join(existing) or 'existing release detected'}.")

    if asset_error:
        blockers.append(f"Valid signed release APKs are not ready: {asset_error}")
    elif assets:
        modules_present = sorted({a.module for a in assets})
        reasons.append(f"Valid signed release APK(s) prepared: {len(assets)} asset(s) for module(s): {', '.join(modules_present)}.")
        if "app" not in modules_present:
            blockers.append("Main app APK is missing; release package would be incomplete.")
    else:
        blockers.append("Valid signed release APKs are not present.")

    relevant_changes_detected = bool(relevance.get("relevant_changes_detected"))
    version_config_changed = bool(relevance.get("version_config_changed"))
    if relevant_changes_detected:
        reasons.append(f"Relevant project/version changes detected ({relevance.get('evidence_source')}).")
        if version_config_changed:
            reasons.append("Root version configuration (build.gradle.kts) changed in detected scope.")
    else:
        if relevance.get("base_ref"):
            blockers.append(f"No relevant project/version changes detected vs {relevance.get('base_ref')}.")
        else:
            blockers.append("Relevant project/version changes could not be confirmed (no --base-ref provided and no relevant working-tree changes detected).")

    go = len(blockers) == 0
    decision = "GO" if go else "NO-GO"
    summary = (
        f"GO: release {version.tag} is required and ready to publish when explicitly requested."
        if go
        else f"NO-GO: release {version.tag} should not be published automatically."
    )
    if publish_requested and not go:
        blockers.append("Publish requested but decision is NO-GO; publish is strictly opt-in and will not proceed.")

    return {
        "decision": decision,
        "go": go,
        "summary": summary,
        "publish_requested": publish_requested,
        "criteria": {
            "version_tag_not_already_released": not_already_released,
            "valid_signed_apks_present": bool(assets) and not asset_error and "app" in {a.module for a in assets},
            "relevant_project_or_version_changes_detected": relevant_changes_detected,
        },
        "reasons": reasons,
        "blockers": blockers,
    }


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Automated GitHub release management agent")
    p.add_argument("--search-dir", action="append", default=[], help="Directory to search for APKs (repeatable). Defaults to common build output dirs.")
    p.add_argument("--dist-dir", default=str(DEFAULT_DIST_DIR), help="Directory for prepared release assets")
    p.add_argument("--modules", default="app,tv,wearos", help="Comma-separated modules to include (app,tv,wearos)")
    p.add_argument("--base-ref", help="Optional git base ref for change detection reporting (e.g. origin/main)")
    p.add_argument("--target", default="main", help="Target branch/commit for GitHub release")
    p.add_argument("--notes-file", help="Write generated release notes to this path")
    p.add_argument("--prepare-only", action="store_true", help="Detect/validate/prepare assets and notes only (default behavior)")
    p.add_argument("--publish", action="store_true", help="Publish tag and GitHub release (requires explicit intent)")
    p.add_argument("--allow-dirty", action="store_true", help="Allow dirty working tree for prepare-only mode")
    p.add_argument("--draft", action="store_true", help="Create GitHub release as draft when publishing")
    p.add_argument("--prerelease", action="store_true", help="Force prerelease flag (auto-enabled when beta suffix is set)")
    p.add_argument("--skip-duplicate-check", action="store_true", help="Not recommended; only for diagnostics")
    p.add_argument("--auto-version", action="store_true", help="Generate a unique version for every automated build (e.g. CI per-change APK)")
    p.add_argument("--auto-build-id", default="", help="Build identifier for auto-version (defaults to GITHUB_RUN_NUMBER/GITHUB_RUN_ID or UTC timestamp)")
    p.add_argument("--version-name", default="", help="Override version name")
    p.add_argument("--version-code", type=int, default=None, help="Override version code")
    p.add_argument("--beta-suffix", default=None, help="Override beta suffix (empty string allowed)")
    p.add_argument("--build-types", default="release", help="Comma-separated APK build types to accept (e.g. release,debug)")
    p.add_argument("--allow-unsigned", action="store_true", help="Allow unsigned APKs when preparing artifact bundles")
    p.add_argument("--skip-app-required", action="store_true", help="Do not require main app APK (for partial/module artifact bundles)")
    p.add_argument("--always-produce-artifacts", action="store_true", help="For non-publish runs, treat prepared assets as successful artifact output even if publish decision is NO-GO")
    p.add_argument("--json-report", help="Write machine-readable report JSON to path")
    return p.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    dist_dir = Path(args.dist_dir)
    if not dist_dir.is_absolute():
        dist_dir = (ROOT / dist_dir).resolve()
    modules = [m.strip() for m in args.modules.split(",") if m.strip()]
    search_dirs = [Path(s) if os.path.isabs(s) else (ROOT / s) for s in args.search_dir] or [
        ROOT / "app" / "build" / "outputs" / "apk",
        ROOT / "tv" / "build" / "outputs" / "apk",
        ROOT / "wearos" / "build" / "outputs" / "apk",
        ROOT / "dist",
    ]

    report: Dict[str, object] = {"status": "started", "errors": [], "warnings": []}
    try:
        version = read_version(
            override_name=(args.version_name or None),
            override_code=args.version_code,
            override_suffix=args.beta_suffix,
            auto_version=bool(getattr(args, "auto_version", False)),
            auto_build_id=(getattr(args, "auto_build_id", "") or None),
        )
        report["version"] = asdict(version)
        # Expose Gradle property overrides for CI/build steps.
        report["gradle_properties"] = {
            "bitchordVersionName": version.version_name,
            "bitchordVersionCode": version.version_code,
            "bitchordBetaSuffix": version.beta_suffix,
        }

        changes = detect_repo_changes(args.base_ref)
        report["changes"] = changes
        if changes["working_tree_changed"]:
            log("INFO", f"Working tree has {len(changes['working_tree_changed'])} changed path(s)")
            if args.publish and not args.allow_dirty:
                raise ReleaseError("Refusing to publish from a dirty working tree. Commit/stash changes or use prepare-only.")
            if not args.allow_dirty and not args.prepare_only and not args.publish:
                log("WARN", "Working tree is dirty; proceeding in non-publish mode")

        availability = check_release_availability(version)
        report["release_availability"] = availability
        relevance = evaluate_release_relevance(changes, args.base_ref)
        report["release_relevance"] = relevance

        apks = find_apks(search_dirs, modules)
        log("INFO", f"Found {len(apks)} APK candidate(s)")
        for p in apks:
            log("INFO", f" - {p}")

        assets: List[ApkAsset] = []
        asset_error: Optional[str] = None
        notes_path: Optional[Path] = None
        try:
            build_types = [m.strip() for m in str(getattr(args, "build_types", "release")).split(",") if m.strip()] or ["release"]
            assets = prepare_assets(
                version,
                apks,
                dist_dir,
                include_modules=modules,
                require_signed=(not bool(getattr(args, "allow_unsigned", False))),
                allowed_build_types=build_types,
            )
            if not bool(getattr(args, "skip_app_required", False)):
                validate_assets_for_publish(assets, version)
            else:
                names = [a.release_name for a in assets]
                if len(names) != len(set(names)):
                    raise ReleaseError("Duplicate asset names detected")
                for a in assets:
                    if a.size_bytes <= 0:
                        raise ReleaseError(f"Empty asset: {a.release_name}")
                    if version.display_version not in a.release_name:
                        raise ReleaseError(f"Asset does not contain version {version.display_version}: {a.release_name}")
            report["assets"] = [asdict(a) for a in assets]

            prev_tag = get_previous_tag(version.tag)
            notes = generate_release_notes(version, prev_tag)
            notes_path = Path(args.notes_file) if args.notes_file else (dist_dir / "RELEASE_NOTES.md")
            if not notes_path.is_absolute():
                notes_path = (ROOT / notes_path).resolve()
            notes_path.parent.mkdir(parents=True, exist_ok=True)
            notes_path.write_text(notes, encoding="utf-8")
            log("INFO", f"Wrote release notes: {notes_path}")
            report["notes_file"] = str(notes_path)
            report["previous_tag"] = prev_tag

            manifest_path = write_manifest(dist_dir, version, assets, notes_path)
            report["manifest"] = str(manifest_path)
        except ReleaseError as e:
            asset_error = str(e)
            report["asset_validation_error"] = asset_error
            log("ERROR", asset_error)

        prerelease = args.prerelease or bool(version.beta_suffix)
        report["prerelease"] = prerelease

        decision = evaluate_release_decision(
            version=version,
            availability=availability,
            relevance=relevance,
            assets=assets,
            asset_error=asset_error,
            publish_requested=bool(args.publish),
        )
        report["decision"] = decision

        log("INFO", f"RELEASE DECISION: {decision['decision']}")
        log("INFO", decision["summary"])
        for r in decision.get("reasons", []):
            log("INFO", f"GO reason: {r}")
        for b in decision.get("blockers", []):
            log("WARN" if not args.publish else "ERROR", f"NO-GO blocker: {b}")

        if not decision["go"]:
            if args.publish:
                report["status"] = "no_go"
                raise ReleaseError("Publish refused: " + "; ".join(decision.get("blockers") or ["release decision is NO-GO"]))
            # For automated per-change artifact builds, keep assets/report as successful output.
            if bool(getattr(args, "always_produce_artifacts", False)) and assets and notes_path is not None:
                report["status"] = "artifacts_ready"
                log("INFO", "NO-GO for publishing, but prepared versioned APK artifacts/report successfully (--always-produce-artifacts).")
                return 0
            report["status"] = "no_go"
            log("INFO", "No publish performed (decision is NO-GO).")
            return 0

        if args.publish:
            if args.skip_duplicate_check:
                raise ReleaseError("Cannot publish with --skip-duplicate-check: duplicate prevention is required")
            if notes_path is None:
                raise ReleaseError("Release notes are missing; cannot publish")
            ensure_clean_release_state(version)
            publish_release(version, dist_dir, assets, notes_path, target=args.target, prerelease=prerelease, draft=args.draft)
            report["status"] = "published"
            log("INFO", "Publish completed")
            return 0

        report["status"] = "go_ready"
        log("INFO", "GO: release is required and ready. No publish performed (publish is opt-in via --publish).")
        return 0
    except Exception as e:
        log("ERROR", str(e))
        report["status"] = "failed"
        report["errors"].append(str(e))
        return 1
    finally:
        if args.json_report:
            rp = Path(args.json_report)
            if not rp.is_absolute():
                rp = (ROOT / rp).resolve()
            rp.parent.mkdir(parents=True, exist_ok=True)
            rp.write_text(json.dumps(report, indent=2), encoding="utf-8")
            log("INFO", f"Wrote report: {rp}")

        decision = report.get("decision") if isinstance(report, dict) else None
        if isinstance(decision, dict):
            log("INFO", f"FINAL RELEASE DECISION: {decision.get('decision')} - {decision.get('summary')}")


if __name__ == "__main__":
    sys.exit(main())
