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
    return 
