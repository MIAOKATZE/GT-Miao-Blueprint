"""Clone a fresh production GTNH client and prepare an offline normal-jar launch.

This never launches Java, reads launcher accounts, or copies existing worlds.
The generated args file is local runtime data; only a compact receipt is printed.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import zipfile
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[2]
V008_BASELINE_SHA = '45c37b3f6a0fa83bbe11ddffddf3f2fb5ca8c8ef855dc9814dac7f559527d66f'
PRIVATE = re.compile(r'account|credential|password|token|secret|authlib|session', re.I)
PRIVATE_VALUE = re.compile(
    r'(?im)(?:^|[,{])[ \t]*["\']?[\w.:-]{0,64}'
    r'(?:access_?token|refresh_?token|client_?secret|password|api_?key)'
    r'[\w.:-]{0,32}["\']?[ \t]*[:=][ \t]*([^\r\n]+)')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def allowed_library(library):
    name = library['name'].lower()
    if any(part in name for part in ('natives-windows-arm64', 'natives-windows-x86')):
        return False
    if library.get('downloads') and not library['downloads'].get('artifact'):
        return False  # Extracted legacy platform DLLs are cloned from instance/natives.
    rules = library.get('rules', [])
    if not rules:
        return True
    allowed = False
    for rule in rules:
        platform = rule.get('os', {}).get('name')
        if platform is None or platform == 'windows':
            allowed = rule['action'] == 'allow'
    return allowed


def cached_library(library, instance, cache):
    name = library['name'].split(':')
    artifact = library.get('downloads', {}).get('artifact', {})
    filename = Path(urlsplit(artifact.get('url', '')).path).name
    if not filename:
        filename = '-'.join(name[1:3]) + ('-' + name[3] if len(name) > 3 else '') + '.jar'
    if library.get('MMC-hint') == 'local':
        result = instance / 'libraries' / filename
    else:
        result = cache / name[0].replace('.', '/') / name[1] / name[2] / filename
    if not result.is_file():
        # Prism stores renamed native coordinates under the original artifact
        # directory, and stores client.jar under its Maven coordinate filename.
        standard = '-'.join(name[1:3]) + ('-' + name[3] if len(name) > 3 else '') + '.jar'
        alternative = cache / name[0].replace('.', '/') / name[1] / name[2] / standard
        matches = list((cache / name[0].replace('.', '/')).rglob(filename))
        if alternative.is_file():
            result = alternative
        elif len(matches) == 1:
            result = matches[0]
        else:
            raise SystemExit('Missing cached production library: ' + str(result))
    return result


def clone_files(source, destination, suffixes=None):
    if not source.is_dir():
        return
    for item in source.rglob('*'):
        if not item.is_file() or item.is_symlink():
            continue
        relative = item.relative_to(source)
        if source.name == 'config' and relative.parts[0] == 'gtmb':
            continue  # Start with an empty probe library instead of user presets.
        if any(PRIVATE.search(part) for part in relative.parts):
            continue
        if suffixes is not None and item.suffix.lower() not in suffixes:
            continue
        target = destination / relative
        if target.is_file() and target.stat().st_size == item.stat().st_size and (
                target.stat().st_mtime_ns == item.stat().st_mtime_ns):
            continue  # A previously checked copy in an incomplete fixture needs no second scan/copy.
        if item.suffix.lower() in ('.cfg', '.conf', '.ini', '.json', '.toml', '.yaml', '.yml', '.zs'):
            contents = item.read_text(encoding='utf-8', errors='replace')
            lowered = contents.lower()
            potential = any(word in lowered for word in ('access_token', 'accesstoken', 'refresh_token',
                'refreshtoken', 'client_secret', 'clientsecret', 'password', 'api_key', 'apikey'))
            if potential and any(match.group(1).strip().strip('"\' ,;') not in
                   ('', 'false', 'true', '0', 'null', 'none', 'changeme')
                   for match in PRIVATE_VALUE.finditer(contents)):
                continue
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item, target)


def quote_arg(value):
    return '"' + str(value).replace('\\', '/').replace('"', '\\"') + '"'


def window_dimensions(width, height):
    """Keep the client plus window decorations within the Windows work area."""
    area = None
    try:
        import ctypes
        from ctypes import wintypes
        bounds = wintypes.RECT()
        if ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(bounds), 0):
            area = (bounds.right - bounds.left, bounds.bottom - bounds.top)
            width = min(width, max(320, area[0] - 64))
            height = min(height, max(240, area[1] - 96))
    except (AttributeError, OSError):
        pass
    return width, height, area


def options_text(width, height):
    return ('lang:zh_CN\nfullscreen:false\nguiScale:2\n'
            'soundCategory_master:0.0\nsoundCategory_music:0.0\nsoundCategory_record:0.0\n'
            f'overrideWidth:{width}\noverrideHeight:{height}\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance', type=Path, required=True)
    parser.add_argument('--prism-data', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--run', required=True, help='Fresh directory suffix, e.g. candidate-v008-01')
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--probe-addon', type=Path)
    parser.add_argument('--profile', choices=['native-v008', 'adjacent-v009', 'adjacent-v008-baseline', 'v016-gui'], default='native-v008')
    parser.add_argument('--width', type=int, default=1280)
    parser.add_argument('--height', type=int, default=800)
    parser.add_argument('--finish-incomplete', action='store_true',
                        help='Resume preparation only before any args, receipt or world exists')
    args = parser.parse_args()
    if args.width < 320 or args.height < 240:
        parser.error('Requested window dimensions must be at least 320 by 240')
    width, height, working_area = window_dimensions(args.width, args.height)
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.run):
        parser.error('Run suffix must contain only letters, digits, underscore and hyphen')
    instance, prism, normal = args.instance.resolve(), args.prism_data.resolve(), args.jar.resolve()
    if not args.java.is_file():
        parser.error('Java executable is missing')
    if not normal.is_file() or re.search(r'-(dev|sources)\.jar$', normal.name, re.I):
        parser.error('An existing normal reobfuscated jar is required')
    if args.profile == 'adjacent-v008-baseline' and digest(normal) != V008_BASELINE_SHA:
        parser.error('Adjacent v008 baseline requires the unchanged official normal jar SHA256')
    with zipfile.ZipFile(normal) as archive:
        normal_classes = {name for name in archive.namelist() if name.endswith('.class')}
        if 'com/miaokatze/gtmb/GTMB.class' not in archive.namelist() and not any(
                name.startswith('com/miaokatze/gtmb/') and name.endswith('.class') for name in archive.namelist()):
            parser.error('Normal jar does not contain GTMB classes')
    if args.probe_addon:
        with zipfile.ZipFile(args.probe_addon) as archive:
            duplicated = normal_classes.intersection(archive.namelist())
        if duplicated:
            parser.error('Probe addon must not replace any class from the selected normal jar')
    patch_files = sorted((instance / 'patches').glob('*.json'))
    patches = sorted((json.loads(path.read_text(encoding='utf-8')) for path in patch_files),
                     key=lambda patch: patch.get('order', 0))
    if not patches:
        parser.error('Production Prism instance patch metadata is required')
    libraries, selected, jvm, tweakers, main_class = [], {}, [], [], None
    for patch in patches:
        main_class = patch.get('mainClass', main_class)
        jvm.extend(patch.get('+jvmArgs', []))
        tweakers.extend(patch.get('+tweakers', []))
        for library in patch.get('libraries', []):
            if allowed_library(library):
                coordinate = library['name'].split(':')
                key = tuple(coordinate[:2] + coordinate[3:])
                selected[key] = library
        if patch.get('mainJar'):
            selected[('minecraft-main',)] = patch['mainJar']
    for library in selected.values():
        libraries.append(cached_library(library, instance, prism / 'libraries'))
    libraries = list(dict.fromkeys(libraries))
    if not main_class or not (prism / 'assets/indexes/1.7.10.json').is_file():
        parser.error('Production main class or cached Minecraft assets are missing')
    fixture = (ROOT / 'build' / ('hologram-packaged-' + args.run)).resolve()
    incomplete = args.finish_incomplete and fixture.is_dir() and not any(
        (fixture / name).exists() for name in ('java-args.txt', 'environment.json', 'client/saves'))
    if not fixture.is_relative_to((ROOT / 'build').resolve()) or (fixture.exists() and not incomplete):
        parser.error('Use a new workspace build fixture directory')
    client = fixture / 'client'
    client.mkdir(parents=True, exist_ok=incomplete)
    for directory in ('mods', 'coremods', 'nestedmods'):
        clone_files(instance / '.minecraft' / directory, client / directory, {'.jar'})
    clone_files(instance / '.minecraft/config', client / 'config')
    clone_files(instance / '.minecraft/scripts', client / 'scripts', {'.zs'})
    clone_files(instance / 'natives', client / 'natives')
    clone_files(instance / 'patches', fixture / 'patches', {'.json'})
    for old in (client / 'mods').rglob('*.jar'):
        with zipfile.ZipFile(old) as archive:
            is_gtmb = any(name.startswith('com/miaokatze/gtmb/') and name.endswith('.class')
                          for name in archive.namelist())
        if is_gtmb:
            old.unlink()
    shutil.copy2(normal, client / 'mods' / normal.name)
    if args.probe_addon:
        shutil.copy2(args.probe_addon, client / 'mods' / args.probe_addon.name)
    (client / 'options.txt').write_text(
        options_text(width, height), encoding='utf-8')
    baseline_profile = args.profile == 'adjacent-v008-baseline'
    gui_profile = args.profile == 'v016-gui'
    if gui_profile and not args.probe_addon:
        parser.error('v016-gui requires the test-only production GUI addon')
    profile_args = [] if args.profile in ('native-v008', 'v016-gui') else [
        '-Dgtmb.hologram.productionAdjacentPreview=true', '-Dgtmb.hologram.extendedCompatibilitySmoke=true']
    if gui_profile:
        (fixture / 'receipts').mkdir()
        profile_args.extend(['-Dgtmb.v016.productionGui=true', '-Dgtmb.v016.output=' + str(fixture / 'receipts')])
    if baseline_profile:
        profile_args.append('-Dgtmb.hologram.productionAdjacentBaselineWitness=true')
        profile_args.append('-Dgtmb.hologram.energyNetworkSmoke=false')
    launch = ['-Xms768M', '-Xmx3072M'] + jvm + args.jvm_arg + profile_args + [
        '-Dgtmb.hologram.packagedPreviewProbe=' + ('false' if gui_profile else 'true'),
        '-Dgtmb.hologram.productionPreviewSmoke=' + ('false' if baseline_profile or gui_profile else 'true'),
        '-Dgtmb.hologram.packagedProbeDir=' + str(fixture),
        '-Djava.library.path=' + str(client / 'natives'), '-cp',
        ';'.join(str(path) for path in libraries), main_class,
        '--username', 'GTMBPackagedProbe', '--version', '1.7.10', '--gameDir', str(client),
        '--assetsDir', str(prism / 'assets'), '--assetIndex', '1.7.10',
        '--uuid', '555cc6de02d43a6e8e8c4b79b202ef11', '--accessToken', '0',
        '--userProperties', '{}', '--userType', 'legacy', '--width', str(width), '--height', str(height)]
    for tweaker in tweakers:
        launch.extend(['--tweakClass', tweaker])
    arguments = fixture / 'java-args.txt'
    arguments.write_text('\n'.join(quote_arg(value) for value in launch) + '\n', encoding='utf-8')
    receipt = {'client': str(client), 'java': str(args.java.resolve()), 'args_file': str(arguments),
               'normal_jar': normal.name, 'normal_sha256': digest(normal), 'main_class': main_class,
               'library_count': len(libraries), 'mod_count': len(list((client / 'mods').rglob('*.jar'))),
               'window_size': [width, height], 'windows_working_area': working_area, 'gui_scale': 2,
               'profile': args.profile, 'expected_source_cells': 0 if gui_profile else 7 if args.profile == 'native-v008' else 57,
               'baseline_witness_addon_required': baseline_profile,
               'user_worlds_copied': False, 'launcher_accounts_read': False,
               'user_gtmb_presets_copied': False,
               'scripts': 'Only .zs files; credential paths and nonempty credential values excluded',
               'source_instance_modified': False}
    (fixture / 'environment.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    incomplete_marker = fixture / 'INCOMPLETE-DO-NOT-LAUNCH.txt'
    if incomplete_marker.is_file():
        incomplete_marker.unlink()
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
