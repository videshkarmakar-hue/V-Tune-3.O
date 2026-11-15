from pathlib import Path
import re

path = Path('scripts/release_agent.py')
text = path.read_text(encoding='utf-8')

old = r'''def read_version() -> VersionInfo:
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

    if not re.match(r"^\d+\.\d+\.\d+(?:[.-][0-9A-Za-z.-]+)?$", version_name):
        raise ReleaseError(f"Invalid versionName format: {version_name}")
    if version_code <= 0:
        raise ReleaseError(f"Invalid versionCode: {version_code}")
    if beta_suffix and not re.match(r"^[0-9A-Za-z.-]+$", beta_suffix):
        raise ReleaseError(f"Invalid beta suffix: {beta_suffix}")

    info = VersionInfo(version_name=version_name, version_code=version_code, beta_suffix=beta_suffix)
    log("INFO", f"Detected version: name={info.display_version} code={version_code} tag={info.tag}")
    return info
'''

new = r'''def _read_gradle_default_version() -> VersionInfo:
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
        build_id = (auto_build_id or os.getenv("GITHUB_RUN_NUMBER") or "").strip()
        if not build_id:
            build_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        try:
            sha = (git("rev-parse", "--short", "HEAD", check=False).stdout or "").strip()
        except Exception:
            sha = ""
        version_name = f"{base.version_name}.{build_id}"
        if version_code <= 0:
            version_code = 1
        try:
            run_num = int(re.sub(r"\D", "", str(build_id)) or "0")
            version_code = max(version_code, run_num)
        except Exception:
            pass
        if override_suffix is None and not beta_suffix:
            beta_suffix = f"ci.{build_id}" + (f".{sha}" if sha else "")
        elif override_suffix is None and beta_suffix:
            beta_suffix = f"{beta_suffix}.{build_id}" + (f".{sha}" if sha else "")

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
'''

if old not in text:
    raise SystemExit('read_version block not found')
text = text.replace(old, new)

old_pa = r'''def prepare_assets(
    version: VersionInfo,
    apk_paths: List[Path],
    dist_dir: Path,
    include_modules: List[str],
    require_signed: bool = True,
) -> List[ApkAsset]:
    if dist_dir.exists():
        shutil.rmtree(dist_dir)
    dist_dir.mkdir(parents=True, exist_ok=True)

    assets: List[ApkAsset] = []
    seen_names: Dict[str, Path] = {}
    include_set = {m.lower() for m in include_modules}

    for src in apk_paths:
        module = detect_module(src)
        build_type = detect_build_type(src)
        if module.lower() not in include_set:
            continue
        if build_type != "release":
            log("SKIP", f"Skipping non-release APK: {src}")
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
        raise ReleaseError("No valid release APK assets found to publish")
    return sorted(assets, key=lambda a: (a.module, a.abi, a.release_name))
'''

new_pa = r'''def prepare_assets(
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
'''

if old_pa not in text:
    raise SystemExit('prepare_assets block not found')
text = text.replace(old_pa, new_pa)

old_args = r'''    p.add_argument("--allow-existing", action="store_true", help="Allow existing tags/releases (for validation/reporting only; publish still unsafe)")
    p.add_argument("--json-report", default="", help="Write decision/report JSON to this path")
    args = p.parse_args(argv)
'''

new_args = r'''    p.add_argument("--allow-existing", action="store_true", help="Allow existing tags/releases (for validation/reporting only; publish still unsafe)")
    p.add_argument("--auto-version", action="store_true", help="Generate a unique version for this automated build (CI-friendly)")
    p.add_argument("--auto-build-id", default="", help="Build identifier for auto-version (defaults to GITHUB_RUN_NUMBER or UTC timestamp)")
    p.add_argument("--version-name", default="", help="Override version name")
    p.add_argument("--version-code", type=int, default=None, help="Override version code")
    p.add_argument("--beta-suffix", default=None, help="Override beta suffix (empty string allowed)")
    p.add_argument("--build-types", default="release", help="Comma-separated APK build types to accept when preparing assets (e.g. release,debug)")
    p.add_argument("--allow-unsigned", action="store_true", help="Allow unsigned APKs when preparing non-publish artifact bundles")
    p.add_argument("--skip-app-required", action="store_true", help="Do not require main app APK in validation (for partial/module artifact builds)")
    p.add_argument("--json-report", default="", help="Write decision/report JSON to this path")
    args = p.parse_args(argv)
'''

if old_args not in text:
    raise SystemExit('args block not found')
text = text.replace(old_args, new_args)

old_main_v = '    version = read_version()\n'
new_main_v = '''    version = read_version(
        override_name=(args.version_name or None),
        override_code=args.version_code,
        override_suffix=args.beta_suffix,
        auto_version=bool(args.auto_version),
        auto_build_id=(args.auto_build_id or None),
    )
'''

if old_main_v not in text:
    raise SystemExit('version read call not found')
text = text.replace(old_main_v, new_main_v)

old_prep = r'''    assets = prepare_assets(
        version,
        apk_paths,
        dist_dir,
        modules,
        require_signed=True,
    )
'''

new_prep = r'''    build_types = [m.strip() for m in str(args.build_types).split(",") if m.strip()]
    assets = prepare_assets(
        version,
        apk_paths,
        dist_dir,
        modules,
        require_signed=(not args.allow_unsigned),
        allowed_build_types=build_types or ["release"],
    )
'''

if old_prep not in text:
    raise SystemExit('prepare call not found')
text = text.replace(old_prep, new_prep)

old_val = r'''    try:
        validate_assets_for_publish(assets, version)
    except Exception as e:
        reasons.append(f"Invalid or incomplete release assets: {e}")
'''

new_val = r'''    try:
        if not args.skip_app_required:
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
    except Exception as e:
        reasons.append(f"Invalid or incomplete release assets: {e}")
'''

if old_val not in text:
    raise SystemExit('validate block not found')
text = text.replace(old_val, new_val)

path.write_text(text, encoding='utf-8')
print('patched')
