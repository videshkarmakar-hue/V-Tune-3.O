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
  - Determines whether a release is required and reports a **definitive GO/NO-GO** decision with reasons/blockers
  - Publishes tag + GitHub Release via `gh` (only with `--publish`, and only when decision is GO)
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
  --base-ref origin/main \
  --dist-dir dist/release \
  --json-report dist/release/release-report.json
```

Use `--base-ref` (e.g. `origin/main`) so the agent can definitively evaluate relevant project/version changes vs the compared ref. Without it, relevance is based only on relevant working-tree changes and the decision will be NO-GO if changes cannot be confirmed.

Outputs in `dist/release/` (when assets can be prepared):
- normalized APK assets
- `RELEASE_NOTES.md`
- `release-manifest.json`

The agent always reports a **definitive release decision** (`GO` or `NO-GO`) in logs and in the JSON report. It does **not** publish in prepare-only mode.

Statuses:
- `go_ready` — release is required and ready; publish allowed only if explicitly requested with `--publish`
- `no_go` — release should not be published (missing/invalid APKs, duplicate/existing release, or relevant changes not confirmed/detected)
- `published` — explicit publish completed and validated
- `failed` — execution/publish error

### Publish (explicit)
```bash
python3 scripts/release_agent.py \
  --modules app,tv,wearos \
  --base-ref origin/main \
  --dist-dir dist/release-publish \
  --target main \
  --publish
```

Publish is **strictly opt-in** (`--publish`) and will be refused unless the decision is **GO**. Publish mode will:
1. Validate no duplicate local/remote tag or existing GitHub release exists
2. Create and push annotated tag `v<display-version>`
3. Create GitHub Release and upload prepared assets
4. Validate the published release contains all expected assets

Safety notes:
- Duplicate-prevention checks cannot be skipped for publish (publishing with `--skip-duplicate-check` is refused).
- The agent never auto-publishes incomplete, invalid, or duplicate releases.

### Useful flags
- `--search-dir <path>` (repeatable): add APK search locations
- `--modules app,tv,wearos`: limit included modules
- `--draft`: publish as draft release
- `--prerelease`: force prerelease
- `--base-ref origin/main`: compare `origin/main...HEAD` for relevant change detection and go/no-go evaluation (recommended)
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
- **Definitive go/no-go before publish:** publish is allowed only when decision is GO (version/tag not already released, valid signed APKs present, and relevant project/version changes detected/confirmed)
- **No destructive git ops:** agent does not reset/force-push/delete refs; publish creates/pushes a new annotated tag only
- **Approval before publish:** CI publish path requires explicit `workflow_dispatch` input `publish=true` (strictly opt-in)
- **Post-publish validation:** verifies release tag and uploaded asset names via `gh release view`
- **Auditable outputs:** manifest/report/notes and decision details (criteria, reasons, blockers) are generated for inspection

## Change detection and "release required" guidance

The agent reports changed paths and emits a definitive **GO/NO-GO** decision in logs/report. A release is considered required/ready (**GO**) only when all are true:
- Target version/tag is **not already released** (no local tag, no origin tag, no existing GitHub release)
- **Valid signed release APKs** are present/prepared (release-only, signed, correctly named/versioned, and including the main app APK)
- **Relevant project/version changes are detected** vs `--base-ref` (recommended, e.g. `origin/main...HEAD`), or confirmed from relevant working-tree changes when no base ref is provided

If any criterion is not met, the decision is **NO-GO** with explicit blockers and no publish occurs. The workflow does **not** auto-publish on ordinary `main` pushes; publishing requires manual dispatch with explicit `publish=true` and a GO decision.

## Troubleshooting

- **"No valid release APK assets found"**: ensure signed `*Release*.apk` files exist under `app/tv/wearos/build/outputs/apk/...` or pass `--search-dir`.
- **"Duplicate release detected"**: tag/release already exists; bump version in `build.gradle.kts` (and rebuild) instead of reusing.
- **Decision is NO-GO due to change detection**: provide `--base-ref origin/main` (or an appropriate comparison ref) so relevant changes can be evaluated definitively; ensure version/project changes are committed in the compared range.
- **Publish refused (NO-GO or skip-duplicate-check)**: fix reported blockers first; publish is only allowed on an explicit GO with duplicate checks enforced.
- **"Unsigned APK is not publishable"**: provide signing config/keystore and rebuild release APKs.
- **`gh` errors/auth failures**: authenticate locally (`gh auth login`) or ensure Actions `GITHUB_TOKEN` permissions include `contents: write`.
- **Dirty working tree on publish**: commit or stash changes first; publish from a clean, known state.
- **Missing module APKs**: adjust `--modules` intentionally (e.g. `--modules app`) if only phone app is being released, but do not claim other modules were included.

## Extending

- Add checksum files (`.sha256`) upload if desired by extending `prepare_assets`/publish steps.
- Integrate changelog sources (PR labels, conventional commits) by enhancing `generate_release_notes`.
- Add environment protection rules in GitHub for an additional manual approval gate on publish jobs.
- Tune "relevant change" paths/patterns in `evaluate_release_relevance` if release scope changes.