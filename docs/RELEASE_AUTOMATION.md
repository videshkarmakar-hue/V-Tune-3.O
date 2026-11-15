# GitHub Release Automation (BitChord / V-Tune)

This repository includes a release automation agent and GitHub Actions workflow that detect project/version/APK changes, prepare and validate release assets, generate release notes, and determine a **definitive GO/NO-GO** on every change. Publishing is **manually gated** (explicit `workflow_dispatch` with `publish=true`) and is allowed only when the decision is **GO** and all safety checks pass.

## Components

- `scripts/release_agent.py` — core release management agent
  - Reads version metadata from root `build.gradle.kts` (`bitchordVersionCode`, `bitchordVersionName`, `bitchordBetaSuffix`)
  - Detects release APKs from build output directories
  - Filters/validates publishable APKs (release, signed, correct module/version)
  - Renames assets consistently (e.g. `V-Tune-1.0.0-arm64-v8a.apk`, `V-Tune-TV-1.0.0-universal.apk`)
  - Generates release notes and a machine-readable manifest/report
  - Checks for duplicate tags/releases before publish
  - Determines whether a release is required and reports a **definitive GO/NO-GO** decision with criteria/reasons/blockers
  - Publishes tag + GitHub Release via `gh` **only on explicit publish intent and only when the decision is GO**
  - Validates uploaded release assets after publish

- `.github/workflows/release.yml` — CI automation workflow
  - Triggers on **every push** (any branch), **pull requests**, **`v*` tag pushes**, and **manual dispatch**
  - Runs detection, signed-release build (when signing secrets are available in trusted contexts), asset preparation/validation, and GO/NO-GO reporting
  - **Publishes only via manual dispatch** (`publish=true`) **and only when the decision is GO**; never publishes incomplete, invalid, or duplicate releases
  - Uploads the prepared bundle/report as a workflow artifact for inspection

## Triggers and automated behavior

- **Push (any branch, any file add/modify/delete):** runs immediately to detect changes, evaluate version/APK/release state, and report a definitive **GO/NO-GO**. This provides automated tracking/validation on every project change (no publish).
- **Pull request:** runs detection/validation/decision (no publish).
- **Push tag `v*`:** validates that a corresponding GitHub Release exists for the tag (and reports if missing).
- **Manual dispatch (`workflow_dispatch`) — publish gate:** builds/prepares/validates and, when **`publish=true` and the decision is GO**, creates/pushes the tag and publishes the GitHub Release to the chosen target (draft optional). This is the only CI publish path (explicit manual intent required).

> Publishing is never automatic on push. A **GO** decision means the release is required and ready *if explicitly approved*; **NO-GO** or any safety failure blocks publishing.

## Release process review (gate)

**Auto-publishing every GO release on pushes to `main` (no manual approval):** convenient, but any push/merge to `main` that reaches a valid, non-duplicate **GO** state (version bumped, relevant changes, signed release APKs available/built) would publish immediately without a human check on timing, notes, or assets. That is risky if `main` can reach GO unintentionally or if releases should be deliberate, reviewed events.

**Current choice (gated):** keep **GO/NO-GO detection on all changes**, but **restrict publishing to manual `workflow_dispatch` with `publish=true` and GO**. For an additional GitHub-side approval gate, configure a protected GitHub **Environment** (e.g. `release`) with required reviewers and attach `environment: release` to the publish step/job in `.github/workflows/release.yml`.

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

## Definitive GO/NO-GO decision

The agent always reports a **definitive release decision** in logs and in the JSON report (`decision` with `criteria`, `reasons`, `blockers`, `summary`, plus `release_availability` and `release_relevance`).

A release is **GO** only when all are true:
- **Version/tag not already released:** no local tag, no origin tag, and no existing GitHub release for the target version/tag.
- **Valid signed APKs present:** prepared release APKs are signed, correctly named/versioned, and include at least one **main app** APK (package must not be incomplete).
- **Relevant project/version changes detected:** confirmed vs `--base-ref` (recommended, e.g. previous commit or `origin/main...HEAD`) or from relevant working-tree changes when no base ref is provided. If relevance cannot be confirmed, decision is **NO-GO**.

If any criterion fails, decision is **NO-GO** and publishing is refused.

Statuses:
- `go_ready` — GO, ready; not published in prepare-only runs
- `no_go` — not releasable/publishable (blockers reported), no publish
- `published` — publish completed and post-publish assets validated
- `failed` — execution/publish error

## Local usage

### Prerequisites
- Python 3.x
- Git configured with `user.name` and `user.email` (for tagging/publish)
- GitHub CLI (`gh`) authenticated (for duplicate/release checks and publish)
- Built **signed** release APKs in standard Gradle output locations, or pass `--search-dir`

### Prepare and validate (safe, no publish)
```bash
python3 scripts/release_agent.py \
  --modules app,tv,wearos \
  --base-ref origin/main \
  --dist-dir dist/release \
  --json-report dist/release/release-report.json
```

Use `--base-ref` so relevant project/version changes are evaluated definitively. The agent reports GO/NO-GO and does **not** publish in this mode.

Outputs in `dist/release/` (when assets can be prepared): normalized APK assets, `RELEASE_NOTES.md`, `release-manifest.json`, and decision details in the report.

### Publish (explicit / automated-safe)
```bash
python3 scripts/release_agent.py \
  --modules app,tv,wearos \
  --base-ref origin/main \
  --dist-dir dist/release-publish \
  --target main \
  --publish
```

Publish is allowed **only** when the decision is **GO**. On GO, publish mode validates duplicates, creates/pushes annotated tag `v<display-version>`, creates the GitHub Release with prepared assets, and validates uploaded assets.

### Useful flags
- `--search-dir <path>` (repeatable): add APK search locations
- `--modules app,tv,wearos`: limit included modules
- `--base-ref <ref>`: compare `<ref>...HEAD` for relevant change detection / GO-NO-GO (recommended)
- `--target <branch/sha>`: release target for publish
- `--draft`: publish as draft release
- `--prerelease`: force prerelease
- `--allow-dirty`: allow dirty working tree for prepare-only/non-publish runs

## GitHub Actions configuration

### Required for automated build/publish on `main`
Repository secrets (for signed, publishable APKs and release creation):
- `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Optional build config: `LASTFM_API_KEY`, `LASTFM_SECRET`, `LISTEN_TOGETHER_SERVER`

`GITHUB_TOKEN` is used for `gh` release operations; repository/workflow permissions must allow `contents: write`.

Without signing secrets, the workflow still runs detection/validation/decision on pushes/PRs but cannot produce valid signed APKs, so release decisions will typically be **NO-GO** and nothing will be published (by design).

### Publish path (gated)
The workflow builds/prepares/validates and computes **GO/NO-GO** on relevant triggers but does **not** auto-publish on push. To publish: review the artifact/report and confirm a **GO** decision, then run **Actions → Release Automation → Run workflow** with `publish=true` (and desired target/modules/draft). The agent re-validates and publishes only if GO and all safety checks pass. Prepared bundles/reports are uploaded as artifacts for auditability.

## Safeguards and guarantees

- **No duplicate releases:** refuses publish if tag/release already exists (local, origin, or GitHub); duplicate checks cannot be bypassed for publish.
- **No incomplete/invalid releases:** requires valid **signed** release APKs, correct versioned naming, and a main app APK; refuses unsafe/missing assets.
- **GO-gated publishing:** automatic or explicit publish proceeds only on a definitive **GO** decision (not already released + valid signed APKs + relevant changes confirmed/detected).
- **No destructive git ops:** agent does not reset/force-push/delete refs; publish creates/pushes a new annotated tag only.
- **Post-publish validation:** verifies release tag and uploaded asset names via `gh release view`.
- **Auditable outputs:** manifest, release notes, and JSON decision/report (availability, relevance, criteria, reasons, blockers) are generated for inspection.

## Troubleshooting

- **"No valid release APK assets found" / NO-GO on APKs**: ensure signed `*Release*.apk` files exist (CI signing secrets configured, or local build) or pass `--search-dir`.
- **"Duplicate release detected"**: tag/release already exists; bump `bitchordVersionName`/`bitchordVersionCode` in `build.gradle.kts` and rebuild.
- **NO-GO due to change detection**: provide a correct `--base-ref` so relevant changes can be evaluated definitively.
- **No publish on push**: expected by design. Publishing requires manual `workflow_dispatch` with `publish=true` **and** a GO decision with valid signed assets and no duplicates.
- **`gh` errors/auth failures**: authenticate locally (`gh auth login`) or ensure Actions permissions include `contents: write`.
- **Dirty working tree on publish**: publish from a clean, known state.

## Extending

- Add checksum files (`.sha256`) upload by extending asset preparation/publish.
- Integrate richer changelog sources (PR labels, conventional commits) in `generate_release_notes`.
- Add GitHub environment protection rules for an additional approval gate if desired.
- Tune "relevant change" paths/patterns in `evaluate_release_relevance` to match release scope.
