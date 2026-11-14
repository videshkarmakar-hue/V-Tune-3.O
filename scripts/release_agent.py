#!/usr/bin/env python3
"""
BitChord / V-Tune GitHub Release Automation Agent

Responsibilities:
- Detect project version from root Gradle configuration.
- Discover and validate release APK artifacts.
- Prepare consistently named release assets.
- Generate release notes from git history (since previous tag when available).
- Check for existing tags/releases to prevent duplicates.
- Optionally create/push tag and publish a GitHub Release via `gh`.
- Validate release state after publish.

Safety defaults:
- Dry-run by default (no tag/release mutation unless --publish).
- Never overwrites existing tags/releases/assets.
- Refuses to publish incomplete/invalid/duplicate releases.
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
import tempfile
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

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


def read_version() -> VersionInfo:
    if not BUILD_GRADLE.exists():
        raise ReleaseError(f"Missing {BUILD_GRADLE}")
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    def grab(pattern: str, cast=int):
        m = re.search(pattern, text)
        if not m:
            raise ReleaseError(f"Could not parse version field with pattern: {pattern}")
        return cast(m.group(1))

    version_code = grab(r'extra\["bitchordVersionCode"\]\s*=\s*(\d+)', int)
    version_name = grab(r'extra\["bitchordVersionName"\]\s*=\s*"([^"]+)"', str)
    beta_m = re.search(r'extra\["bitchordBetaSuffix"\]\s*=\s*"([^"]*)"', text)
    beta_suffix = beta_m.group(1).strip() if beta_m else ""
    # basic semver-ish validation
    if not re.match(r"^\d+\.\d+\.\d+(?:[.-][0-9A-Za-z.-]+)?$", version_name):
        raise ReleaseError(f"Invalid versionName format: {version_name}")
    if version_code <= 0:
        raise ReleaseError(f"Invalid versionCode: {version_code}")
    if beta_suffix and not re.match(r"^[0-9A-Za-z.-]+$", beta_suffix):
        raise ReleaseError(f"Invalid beta suffix: {beta_suffix}")
    info = VersionInfo(version_name=version_name, version_code=version_code, beta_suffix=beta_suffix)
    log("INFO", f"Detected version: name={info.display_version} code={version_code} tag={info.tag}")
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
    # de-dup
    uniq = sorted({p.resolve() for p in found})
    if modules:
        filtered = []
        for p in uniq:
            parts = {part.lower() for part in p.parts}
            if any(m.lower() in parts for m in modules):
                filtered.append(p)
        uniq = filtered
    return uniq


def detect_module(path: Path) -> str:
    name = path.name
    # Prepared/normalized asset names
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
    # Keep main app assets compatible with in-app updater prefix.
    label = {
        "app": "",
        "tv": "TV-",
        "wearos": "WearOS-",
    }.get(module, f"{module}-")
    # Preserve unsigned marker info if present? no, release assets should be signed.
    return f"{APK_PREFIX}{label}{version.display_version}-{abi}.apk"


def prepare_assets(version: VersionInfo, apk_paths: List[Path], dist_dir: Path, include_modules: List[str], require_signed: bool = True) -> List[ApkAsset]:
    if dist_dir.exists():
        shutil.rmtree(dist_dir)
    dist_dir.mkdir(parents=True, exist_ok=True)

    assets: List[ApkAsset] = []
    seen_names: Dict[str, Path] = {}

    for src in apk_paths:
        module = detect_module(src)
        build_type = detect_build_type(src)
        if module not in include_modules:
            continue
        if build_type != "release":
            log("SKIP", f"Skipping non-release APK: {src}")
            continue
        name_lower = src.name.lower()
        if require_signed and "unsigned" in name_lower:
            raise ReleaseError(f"Unsigned APK is not publishable: {src}")
        abi = infer_abi(src.name)
        if abi == "unknown":
            log("WARN", f"Could not infer ABI for {src.name}; using 'universal/unknown' naming may be ambiguous")

        rel_name = make_release_name(version, module, abi, src.name)
        if rel_name in seen_names and seen_names[rel_name] != src:
            raise ReleaseError(f"Asset name collision for {rel_name}: {seen_names[rel_name]} vs {src}")
        seen_names[rel_name] = src

        dst = dist_dir / rel_name
        shutil.copy2(src, dst)
        digest = sha256_file(dst)
        size = dst.stat().st_size
        assets.append(ApkAsset(
            module=module,
            source_path=str(src),
            release_name=rel_name,
            abi=abi,
            build_type=build_type,
            size_bytes=size,
            sha256=digest,
        ))
        log("ASSET", f"Prepared {rel_name} ({size} bytes) from {src}")

    if not assets:
        raise ReleaseError("No valid release APK assets found to publish")
    return sorted(assets, key=lambda a: (a.module, a.abi, a.release_name))


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return run(["git", *args], check=check)


def get_repo_slug() -> str:
    cp = git("remote", "get-url", "origin", check=False)
    url = (cp.stdout or "").strip()
    m = re.search(r"github\.com[:/](?P<slug>[^/]+/[^/.]+)(?:\.git)?$", url)
    if not m:
        raise ReleaseError(f"Could not determine GitHub repo slug from origin URL: {url!r}")
    return m.group("slug")


def tag_exists(tag: str) -> bool:
    cp = git("tag", "--list", tag, check=False)
    return tag in {(cp.stdout or "").strip(), tag} and bool((cp.stdout or "").strip())


def remote_tag_exists(tag: str) -> bool:
    cp = git("ls-remote", "--tags", "origin", tag, check=False)
    return bool((cp.stdout or "").strip())


def gh_release_exists(tag: str) -> bool:
    cp = run(["gh", "release", "view", tag], check=False)
    return cp.returncode == 0


def get_previous_tag(current_tag: str) -> Optional[str]:
    cp = git("tag", "--sort=-creatordate", check=False)
    tags = [t.strip() for t in (cp.stdout or "").splitlines() if t.strip()]
    for t in tags:
        if t != current_tag:
            return t
    return None


def generate_release_notes(version: VersionInfo, previous_tag: Optional[str]) -> str:
    header = [f"# {APK_PREFIX.rstrip('-')} {version.display_version}", ""]
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
        if not s:
            continue
        # skip noisy auto commits if mixed? keep but can be filtered later; keep transparent
        changes.append(s)
    if not changes:
        changes = ["- Maintenance release."]

    # naive categorization
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

    sections = []
    def add_section(title: str, items: List[str]):
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

    body = "\n".join(header + meta + sections).strip() + "\n"
    return body


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
        p = Path(ROOT / a.source_path) if not os.path.isabs(a.source_path) else Path(a.source_path)
        # source may be copied to dist; validate dist copy by name in caller context
        if a.size_bytes <= 0:
            raise ReleaseError(f"Empty asset: {a.release_name}")
        if not re.match(r"^[A-Za-z0-9._+-]+\.apk$", a.release_name):
            raise ReleaseError(f"Unsafe asset filename: {a.release_name}")
    # For main app, require at least one primary asset (universal or arm64) matching prefix.
    app_assets = [a for a in assets if a.module == "app"]
    if not app_assets:
        raise ReleaseError("No main app release APK found; refusing to publish incomplete release")
    if not any(a.release_name.startswith(APK_PREFIX) and "TV-" not in a.release_name and "WearOS-" not in a.release_name for a in app_assets):
        raise ReleaseError("Main app APK naming/prefix validation failed")
    # Version in filename must match
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


def publish_release(version: VersionInfo, dist_dir: Path, assets: List[ApkAsset], notes_path: Path, target: str, prerelease: bool, draft: bool) -> None:
    ensure_gh_available()
    ensure_git_identity()
    ensure_clean_release_state(version)

    # Create annotated tag locally and push it explicitly (no force).
    git("tag", "-a", version.tag, "-m", f"Release {version.tag}")
    try:
        git("push", "origin", version.tag)
    except Exception:
        # best-effort local cleanup only if remote push failed before release creation; keep safe
        log("ERROR", "Failed to push tag to origin; local tag remains. Resolve manually to avoid duplicate tag state.")
        raise

    cmd = [
        "gh", "release", "create", version.tag,
        "--title", f"{APK_PREFIX.rstrip('-')} {version.display_version}",
        "--notes-file", str(notes_path),
        "--target", target,
    ]
    if prerelease:
        cmd.append("--prerelease")
    if draft:
        cmd.append("--draft")
    for a in assets:
        cmd.append(str(dist_dir / a.release_name))
    run(cmd)

    # Post-publish validation
    validate_published_release(version, assets)


def validate_published_release(version: VersionInfo, assets: List[ApkAsset]) -> None:
    ensure_gh_available()
    cp = run(["gh", "release", "view", version.tag, "--json", "tagName,name,assets,isDraft,isPrerelease,url"], check=True)
    data = json.loads(cp.stdout or "{}")
    if data.get("tagName") != version.tag:
        raise ReleaseError(f"Published release tag mismatch: {data.get('tagName')} != {version.tag}")
    remote_assets = {a.get("name") for a in data.get("assets", []) if a.get("name")}
    missing = [a.release_name for a in assets if a.release_name not in remote_assets]
    if missing:
        raise ReleaseError("Release published but missing assets: " + ", ".join(missing))
    log("INFO", f"Release validated successfully: {data.get('url', version.tag)}")


def detect_repo_changes(base_ref: Optional[str]) -> Dict[str, List[str]]:
    # Reports working tree and optionally diff against base ref.
    status = git("status", "--porcelain", check=False).stdout or ""
    changed = [line[3:].strip() for line in status.splitlines() if line.strip()]
    result = {"working_tree_changed": changed, "diff_vs_base": []}
    if base_ref:
        cp = git("diff", "--name-status", f"{base_ref}...HEAD", check=False)
        result["diff_vs_base"] = [l.strip() for l in (cp.stdout or "").splitlines() if l.strip()]
    return result


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

    report: Dict[str, object] = {"status": "started", "errors": []}
    try:
        version = read_version()
        report["version"] = asdict(version)

        changes = detect_repo_changes(args.base_ref)
        report["changes"] = changes
        if changes["working_tree_changed"]:
            log("INFO", f"Working tree has {len(changes['working_tree_changed'])} changed path(s)")
            if args.publish and not args.allow_dirty:
                raise ReleaseError("Refusing to publish from a dirty working tree. Commit/stash changes or use prepare-only.")
            if not args.allow_dirty and not args.prepare_only and not args.publish:
                log("WARN", "Working tree is dirty; proceeding in non-publish mode")

        apks = find_apks(search_dirs, modules)
        log("INFO", f"Found {len(apks)} APK candidate(s)")
        for p in apks:
            log("INFO", f" - {p}")

        assets = prepare_assets(version, apks, dist_dir, include_modules=modules, require_signed=True)
        validate_assets_for_publish(assets, version)
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

        prerelease = args.prerelease or bool(version.beta_suffix)
        report["prerelease"] = prerelease

        if args.publish:
            if not args.skip_duplicate_check:
                ensure_clean_release_state(version)
            else:
                log("WARN", "Duplicate checks skipped by flag")
            publish_release(version, dist_dir, assets, notes_path, target=args.target, prerelease=prerelease, draft=args.draft)
            report["status"] = "published"
            log("INFO", "Publish completed")
        else:
            if not args.skip_duplicate_check:
                try:
                    ensure_clean_release_state(version)
                except ReleaseError as e:
                    log("WARN", str(e))
                    report["duplicate_warning"] = str(e)
            report["status"] = "prepared"
            log("INFO", "Prepare-only run completed (no tag/release published)")

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


if __name__ == "__main__":
    sys.exit(main())
