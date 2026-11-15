#!/usr/bin/env python3
"""
Clean debug APK agent.

Performs a clean, fresh debug APK build for V-Tune 3.O on every run:
1. Stops Gradle daemons
2. Removes module build outputs (app/shared/sharedUi)
3. Builds :app:assembleProdDebug (without lint)
4. Reports generated APK paths

Usage:
  python scripts/clean_debug_apk_agent.py
  python scripts/clean_debug_apk_agent.py --no-clean   # build without deleting outputs (not "clean fresh")
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "prod" / "debug"
CLEAN_DIRS = [ROOT / "app" / "build", ROOT / "shared" / "build", ROOT / "sharedUi" / "build"]


def run(cmd, cwd: Path) -> int:
    print("+ " + " ".join(cmd), flush=True)
    return subprocess.call(cmd, cwd=str(cwd))


def main() -> int:
    parser = argparse.ArgumentParser(description="Create a fresh clean debug APK.")
    parser.add_argument("--no-clean", action="store_true", help="Skip daemon stop/build-dir cleanup.")
    args = parser.parse_args()

    # Prefer the POSIX gradlew script when available (use bash on Windows for .sh).
    gradlew_sh = ROOT / "gradlew"
    gradlew_bat = ROOT / "gradlew.bat"
    if gradlew_sh.exists():
        if os.name == "nt":
            gradlew = ["bash", str(gradlew_sh)]
        else:
            gradlew = [str(gradlew_sh)]
    elif gradlew_bat.exists():
        gradlew = [str(gradlew_bat)]
    else:
        gradlew = ["bash", str(gradlew_sh)] if os.name == "nt" else [str(gradlew_sh)]

    if not args.no_clean:
        print("==> Stopping Gradle daemons...", flush=True)
        run(gradlew + ["--stop"], ROOT)
        print("==> Removing previous build outputs...", flush=True)
        for d in CLEAN_DIRS:
            if d.exists():
                print(f"    removing {d}", flush=True)
                shutil.rmtree(d, ignore_errors=True)

    print("==> Building fresh debug APK (:app:assembleProdDebug -x lint)...", flush=True)
    rc = run(gradlew + [":app:assembleProdDebug", "-x", "lint", "--console=plain"], ROOT)
    if rc != 0:
        print(f"ERROR: build failed with exit code {rc}", flush=True)
        return rc

    apks = sorted(APK_DIR.glob("*.apk")) if APK_DIR.exists() else []
    if not apks:
        print(f"ERROR: build succeeded but no APKs found in {APK_DIR}", flush=True)
        return 1

    print("\n==> Fresh debug APK(s) ready:", flush=True)
    for apk in apks:
        print(f" - {apk}", flush=True)

    universal = APK_DIR / "app-prod-universal-debug.apk"
    if universal.exists():
        print(f"\nRecommended APK: {universal}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
