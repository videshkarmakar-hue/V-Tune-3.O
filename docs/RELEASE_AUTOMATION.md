# GitHub Release Automation (BitChord / V-Tune)

This repository includes release automation agents and GitHub Actions workflows that detect project/version/APK changes, generate unique per-build versions, prepare and validate APK assets, generate release notes/manifests, and determine a **definitive GO/NO-GO** for publishing. Publishing remains **manually gated** and is allowed only when the decision is **GO** and all safety checks pass.

## Components

- `scripts/release_agent.py` — core release management agent
  - Reads version metadata from root `build.gradle.kts` (`bitchordVersionCode`, `bitchordVersionName`, `bitchordBetaSuffix`) and supports Gradle property overrides / CLI overrides
  - **Auto-versioning (new):** `--auto-version` generates a unique version per automated build (e.g. every CI run/change) using a build/run identifier and commit SHA, and emits Gradle property overrides in the JSON report
  - Detects APKs from build output directories and can prepare **release** and/or **debug** artifacts (configurable build types/signing requirements)
  - Filters/validates publishable APKs (release, signed, correct module/version) for publish flows; supports artifact-only validation for per-change APK bundles
  - Renames assets consistently (e.g. `V-Tune-<version>-<abi>.apk`, `V-Tune-TV-<version>-universal.apk`)
  - Generates release notes and a machine-readable manifest/report (including resolved version and `gradle_properties`)
  - Checks for duplicate tags/releases before publish
  - Determines whether a release is required and reports a **definitive GO/NO-GO** decision with criteria/reasons/blockers
  - Publishes tag + GitHub Release via `gh` **only on explicit publish intent and only when the decision is GO** (for signed release assets)
  - Validates uploaded release assets after publish
  - **Artifact mode:** `--always-produce-artifacts` allows a non-publish run to succeed with prepared versioned APK artifacts/report even when publish decision is NO-GO

- `.github/workflows/release.yml` — release validation/publish workflow
  - Triggers on pushes (any branch), pull requests, `v*` tag pushes, and manual dispatch
  - Runs detection, signed-release build (when signing secrets are available in trusted contexts), asset preparation/validation, and GO/NO-GO reporting
  - **Publishes only via manual dispatch** (`publish=true`) **and only when the decision is GO**; never publishes incomplete, invalid, or duplicate releases
  - Uploads the prepared bundle/report as a workflow artifact for inspection

- `.github/workflows/auto-apk-agent.yml` — **Auto APK Agent (every change)**
  - Triggers on **every push** (all branches), **pull requests**, and **manual dispatch**
  - Computes a unique per-run version (`versionName`, `versionCode`, CI suffix/tag metadata) for that change/run
  - Builds a versioned **prod debug APK** with Gradle property overrides so every update produces an installable, uniquely versioned APK artifact
  - Packages assets/notes/manifest/report with `scripts/release_agent.py` in artifact mode and uploads them as a workflow artifact
  - Designed to produce a versioned APK artifact on every change automatically (subject to CI/build success and runner environment)

## Triggers and automated behavior

- **Every change (push/PR via Auto APK Agent):** builds and packages a uniquely versioned APK artifact bundle for that run/change and uploads it (no publishing).
- **Push/PR/tag in Release Automation workflow:** runs detection, validation, and definitive **GO/NO-GO** reporting; uploads prepared release bundle/report for inspection (no publish on push).
- **Manual dispatch (`workflow_dispatch`) — publish gate:** `release.yml` can build/prep/validate and, when **`publish=true` and the decision is GO**, create/push the tag and publish the GitHub Release. This is the only CI publish path (explicit manual intent required).

> Publishing is never automatic on push. A **GO** decision means the release is required and ready *if explicitly approved*; **NO-GO** or any safety failure blocks publishing.

## Versioning

### Source of truth and overrides
Release/app versions are derived from `build.gradle.kts`:
- `extra["bitchordVersionCode"] = <int>` (or `-PbitchordVersionCode=<int>`)
- `extra["bitchordVersionName"] = "<version>"` (or `-PbitchordVersionName=<version>`)
- `extra["bitchordBetaSuffix"] = ""` (or `-PbitchordBetaSuffix=<suffix>`)

The in-app updater reads the numeric/base version; the `v` prefix is for release tags.

### Per-change auto-versioning
For automated "every change/update" APK builds, the agent/workflow generates a unique version per run:
- Build identifier defaults to CI run number/id (or UTC timestamp fallback)
- Version name/code and CI suffix are derived to avoid collisions and keep artifacts identifiable
- `scripts/release_agent.py --auto-version` resolves the effective version and records `gradle_properties` in the JSON report for build reproducibility
- The Auto APK Agent workflow passes matching Gradle properties to the build and to the agent so APK metadata, asset names, manifest, and notes stay consistent

## Asset naming conventions

Prepared assets are normalized to:
- Main app (phone): `V-Tune-<display-version>-<abi>.apk`
- Android TV: `V-Tune-TV-<display-version>-<abi>.apk`
- Wear OS: `V-Tune-WearOS-<display-version>-<abi>.apk`

`<abi>` is inferred from source APK names (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`). The main app prefix `V-Tune-` matches the in-app updater expectation.

## Definitive GO/NO-GO decision (publishing)

The agent reports a definitive publish decision in logs and JSON (`decision` with `criteria`, `reasons`, `blockers`, `summary`, plus `release_availability` and `release_relevance`).

A release publish is **GO** only when all are true:
- **Version/tag not already released:** no local tag, no origin tag, and no existing GitHub release for the target version/tag.
- **Valid signed APKs present:** prepared release APKs are signed, correctly named/versioned, and include at least one **main app** APK when required.
- **Relevant project/version changes detected:** confirmed vs `--base-ref` (recommended) or from relevant working-tree changes when no base ref is provided.

Statuses include `go_ready`, `no_go`, `published`, `failed`, and `artifacts_ready` (artifact-only runs completed with prepared outputs).

## Local usage

### Prerequisites
- Python 3.x
- JDK/Android SDK for APK builds (Gradle wrapper in repo)
- Git configured for tagging/publish operations when publishing
- GitHub CLI (`gh`) authenticated for duplicate/release checks and publish

### Build a uniquely versioned APK artifact (per-change style, local)
```bash
# Example: unique version for this build
./gradlew :app:assembleProdDebug \
  -PbitchordVersionName=1.0.0.123 \
  -PbitchordVersionCode=123 \
  -PbitchordBetaSuffix=ci.123.local

python3 scripts/release_agent.py \
  --version-name 1.0.0.123 --version-code 123 --beta-suffix ci.123.local \
  --search-dir app/build/outputs/apk \
  --dist-dir dist/auto-apk \
  --modules app --build-types debug --allow-unsigned --skip-app-required \
  --always-produce-artifacts --allow-dirty \
  --json-report dist/auto-apk/auto-release-report.json
```

### Prepare and validate release (safe, no publish)
```bash
python3 scripts/release_agent.py \
  --modules app,tv,wearos \
  --base-ref origin/main \
  --dist-dir dist/release \
  --json-report dist/release/release-report.json
```

### Publish (explicit / GO-gated)
```bash
python3 scripts/release_agent.py \
  --modules app,tv,wearos \
  --base-ref origin/main \
  --dist-dir dist/release-publish \
  --target main \
  --publish
```

Publish is allowed **only** when the decision is **GO** and safety checks pass (no duplicates, valid signed release assets as required).

### Useful flags
- `--auto-version [--auto-build-id <id>]`: generate unique per-build version metadata
- `--version-name`, `--version-code`, `--beta-suffix`: explicit version overrides
- `--search-dir <path>` (repeatable), `--dist-dir`, `--modules`
- `--build-types <csv>` (e.g. `release,debug`), `--allow-unsigned`, `--skip-app-required`: control artifact preparation/validation
- `--always-produce-artifacts`: succeed with prepared artifacts on non-publish NO-GO runs
- `--base-ref <ref>`, `--publish`, `--target`, `--draft`, `--prerelease`, `--allow-dirty`, `--json-report`

## GitHub Actions configuration

### Auto APK Agent
- No signing secrets required for the default debug-artifact flow
- Produces and uploads versioned APK bundle artifacts on every push/PR/run (when build succeeds)
- For distributable signed release APKs in CI, configure keystore/signing secrets and adapt the build/package steps to `release` build types and signing requirements

### Release publishing
Repository secrets for signed, publishable APKs and release creation:
- `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Optional: `LASTFM_API_KEY`, `LASTFM_SECRET`, `LISTEN_TOGETHER_SERVER`
- `GITHUB_TOKEN` with `contents: write` for publish operations

Without signing secrets, release publish decisions will typically be **NO-GO** (by design).

## Safeguards and notes

- **No duplicate releases:** publish refused if tag/release already exists.
- **No incomplete/invalid releases:** publish requires valid signed release APKs and required modules/assets.
- **GO-gated publishing:** publish proceeds only on a definitive **GO** decision and explicit intent.
- **Auditable outputs:** manifest, release notes, and JSON decision/report are generated for inspection.
- **Build constraints:** "every change produces an APK" depends on CI/build environment health, dependency availability, and successful Gradle builds; failures are reported in workflow runs. Signed, store/update-channel-ready release APKs additionally require valid signing configuration.

## Extending

- Add signed-release auto-artifact/publish variants with protected environments and required approvals.
- Upload checksum files (`.sha256`) alongside assets.
- Enrich changelog generation from PR labels/conventional commits.
- Tune relevant-change detection patterns in `evaluate_release_relevance`.
