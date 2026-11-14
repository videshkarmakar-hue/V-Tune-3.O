# GitHub Release Automation (BitChord / V-Tune)

This repository includes a release automation agent and GitHub Actions workflow to prepare, validate, and (when explicitly requested) publish GitHub Releases with versioned APK assets.

## Components

- `scripts/release_agent.py` — core release management agent
  - Reads version metadata from root `build.gradle.kts` (`bitchordVersionCode`, `bitchordVersionName`, `bitchordBetaSuffix`)
  - Detects release APKs from build output directories
  - Filters/validates publishable APKs (release, signed, correct module/version)
  - Renames assets consistently (e.g. `V-Tune-1.0.0-arm64-v8a.apk`, `V-Tune-TV-1.0.0-universal.apk`)
  - Generates release notes and a machine-readable manifest
  - Checks for duplicate tags/releases before publish
  - Publishes tag + GitHub Release via `gh` (only with `--publish`)
  - Validates uploaded release assets after publish

- `.github/workflows/release.yml` — CI automation workflow
  - `workflow_dispatch`: build (optional), prepare/validate, upload prepared bundle, and optionally publish
  - `push` on tags `v*`: validates that a GitHub Release exists for the tag

## Versioning source of truth

Release versions are derived from `build.gradle.kts`:

- `extra["bitchordVersionCode"] = <int>`
- `extra["bitchordVersionName"] = "<semver>"`
- `extra["bitchordBetaSuffix"] = ""` (or e.g. `"beta2"`)

Computed values:
- Display version: `1.2.3` or `1.2.3-beta2`
- Tag: `v1.2.3` or `v1.2.3-beta2`
- Prerelease: automatically enabled when beta suffix is non-empty

> Keep `bitchordBetaSuffix` empty for final releases. Sideloaded test builds should use a suffix.

## Asset naming conventions

Prepared release assets are normalized to:

- Main app (phone): `V-Tune-<version>-<abi>.apk`
- Android TV: `V-Tune-TV-<version>-<abi>.apk`
- Wear OS: `V-Tune-WearOS-<version>-<abi>.apk`

`<abi>` is inferred from source APK names (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`). The main app prefix `V-Tune-` matches the in-app updater expectation.

## Local usage

### Prerequisites
- Python 3.x
- Git configured with `user.name` and `user.email` (for publish/tagging)
- GitHub CLI (`gh`) authenticated (for publish/validation against GitHub)
- Built **signed** release APKs in standard Gradle output locations, or pass `--search-dir`

### Prepare and validate (safe, no publish)
```bash
python3 scripts/release_agent.py \
  --modules app,tv,wearos \
  --dist-dir dist/release \
  --json-report dist/release/release-report.json
```

Outputs in `dist/release/`:
- normalized APK assets
- `RELEASE_NOTES.md`
- `release-manifest.json`

The agent fails if no valid signed release APKs are found, if assets/names are invalid, or if required app assets are missing.

### Publish (explicit)
```bash
python3 scripts/release_agent.py \
  --modules app,tv,wearos \
  --dist-dir dist/release-publish \
  --target main \
  --publish
```

Publish mode will:
1. Validate no duplicate local/remote tag or existing GitHub release exists
2. Create and push annotated tag `v<display-version>`
3. Create GitHub Release and upload prepared assets
4. Validate the published release contains all expected assets

### Useful flags
- `--search-dir <path>` (repeatable): add APK search locations
- `--modules app,tv,wearos`: limit included modules
- `--draft`: publish as draft release
- `--prerelease`: force prerelease
- `--base-ref origin/main`: include change diff context in detection/reporting
- `--allow-dirty`: allow dirty working tree for prepare-only runs

## GitHub Actions workflow

### Manual release run (recommended)
1. Ensure version fields in `build.gradle.kts` are correct and committed.
2. Go to **Actions → Release Automation → Run workflow**.
3. Choose:
   - `build=true` to build signed prod APKs in CI (requires signing secrets)
   - `publish=false` for prepare/validation only (recommended first), or `true` to publish
   - `modules`, `target`, `draft` as needed
4. Download the `release-bundle` artifact to inspect prepared assets/notes/manifest.
5. If prepare-only looked correct, rerun with `publish=true` (or publish from a trusted local environment).

### Required repository secrets for CI build+publish
- `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Optional build config: `LASTFM_API_KEY`, `LASTFM_SECRET`, `LISTEN_TOGETHER_SERVER`

`GITHUB_TOKEN` (provided by Actions) is used for `gh` release operations; repository permissions must allow `contents: write`.

### Tag push behavior
Pushing a `v*` tag triggers validation that a corresponding GitHub Release exists. It does **not** auto-create a release from a tag alone, to avoid publishing incomplete/invalid assets.

## Safeguards and guarantees

- **No duplicate releases:** refuses publish if tag/release already exists (local, origin, or GitHub)
- **No incomplete releases:** requires at least one valid main app release APK and version-matching asset names
- **No unsigned assets:** unsigned APKs are rejected for publishable assets
- **No destructive git ops:** agent does not reset/force-push/delete refs; publish creates/pushes a new annotated tag only
- **Approval before publish:** CI publish path requires explicit `workflow_dispatch` input `publish=true`
- **Post-publish validation:** verifies release tag and uploaded asset names via `gh release view`
- **Auditable outputs:** manifest/report/notes are generated for inspection

## Change detection and "release required" guidance

The agent reports changed paths (working tree and optional base diff) in logs/report. A release is typically required when:
- `bitchordVersionName` / `bitchordVersionCode` changed for a shippable build, and
- signed release APKs for intended modules are available and validated, and
- the computed tag/release does not already exist

The workflow does **not** auto-publish on ordinary `main` pushes to prevent accidental releases; use manual dispatch with explicit publish intent.

## Troubleshooting

- **"No valid release APK assets found"**: ensure signed `*Release*.apk` files exist under `app/tv/wearos/build/outputs/apk/...` or pass `--search-dir`.
- **"Duplicate release detected"**: tag/release already exists; bump version in `build.gradle.kts` (and rebuild) instead of reusing.
- **"Unsigned APK is not publishable"**: provide signing config/keystore and rebuild release APKs.
- **`gh` errors/auth failures**: authenticate locally (`gh auth login`) or ensure Actions `GITHUB_TOKEN` permissions include `contents: write`.
- **Dirty working tree on publish**: commit or stash changes first; publish from a clean, known state.
- **Missing module APKs**: adjust `--modules` intentionally (e.g. `--modules app`) if only phone app is being released, but do not claim other modules were included.

## Extending

- Add checksum files (`.sha256`) upload if desired by extending `prepare_assets`/publish steps.
- Integrate changelog sources (PR labels, conventional commits) by enhancing `generate_release_notes`.
- Add environment protection rules in GitHub for an additional manual approval gate on publish jobs.