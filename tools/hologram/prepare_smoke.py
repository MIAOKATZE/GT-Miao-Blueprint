"""Prepare fresh GTMB-only smoke worlds from explicitly selected runtime support jars."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import zipfile
from datetime import datetime


ROOT = Path(__file__).resolve().parents[2]
CACHE_MODULES = [
    ('NotEnoughItems', '2.8.155-GTNH'),
    ('CodeChickenCore', '1.4.22'),
    ('waila', '1.19.34'),
    ('Baubles-Expanded', '2.2.6-GTNH'),
]
# These are support jars of the established GTNH fixture, never GTIT or its business modules.
SUPPORT_PREFIXES = (
    'AE2FluidCraft-Rework-', 'chunkapi-', 'cofh-core-', 'endlessids-',
    'falsepatternlib-', 'GTNHExtLib-', 'industrialcraft-2-', 'Postea-',
    'smoke-fluid-placeholders-', 'CoFHLib-', 'cofh-lib-',
)


def cached_jar(module, version):
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/com.github.GTNewHorizons'
    matches = list((cache / module / version).glob('*/*-dev.jar'))
    if len(matches) != 1:
        raise SystemExit(f'Expected one cached {module} {version} dev jar, found {len(matches)}')
    return matches[0]


def coexistence_jars(gtit_jar, support_classpath):
    if not gtit_jar or not gtit_jar.is_file() or gtit_jar.suffix.lower() != '.jar':
        raise SystemExit('Coexistence requires an explicit existing GTIT dev jar')
    if not support_classpath or not support_classpath.is_file():
        raise SystemExit('Coexistence requires an explicit GTIT support-classpath.txt')
    support = [Path(line.strip()) for line in support_classpath.read_text(encoding='utf-8-sig').splitlines()
               if line.strip()]
    prefixes = ('vendingmachine-', 'VisualProspecting-', 'Thaumcraft-')
    if len(support) != 3 or any(not jar.is_file() for jar in support):
        raise SystemExit('GTIT support classpath must contain exactly three existing support jars')
    if any(sum(jar.name.startswith(prefix) for jar in support) != 1 for prefix in prefixes):
        raise SystemExit('GTIT support must be vendingmachine, VisualProspecting and Thaumcraft')
    return [gtit_jar] + support


def validate_support_jars(jars):
    """Reject duplicate FML identities and incompatible copies of the CoFH API before launch."""
    identities, cofh_api = {}, {}
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            if 'mcmod.info' in archive.namelist():
                try:
                    metadata = json.loads(archive.read('mcmod.info').decode('utf-8-sig'))
                except (ValueError, UnicodeDecodeError) as failure:
                    raise SystemExit(f'Invalid mcmod.info in {jar.name}: {failure}')
                entries = metadata if isinstance(metadata, list) else metadata.get('modList', [])
                for entry in entries:
                    identity = entry.get('modid', '').lower()
                    if not identity:
                        continue
                    if identity in identities:
                        raise SystemExit(f'Duplicate mod ID {identity}: {identities[identity]} and {jar.name}')
                    identities[identity] = jar.name
            for name in archive.namelist():
                if name.startswith('cofh/api/') and name.endswith('.class'):
                    payload = archive.read(name)
                    previous = cofh_api.get(name)
                    if previous and previous[1] != payload:
                        raise SystemExit(f'Incompatible CoFH API {name}: {previous[0]} and {jar.name}')
                    cofh_api[name] = (jar.name, payload)
    chicken = [jar.name for jar in jars if jar.name.lower().startswith('codechickencore-')]
    if len(chicken) > 1:
        raise SystemExit('Duplicate CodeChickenCore runtime support: ' + ', '.join(chicken))


def prepare(mode, source, coexistence=False, gtit_jar=None, support_classpath=None,
            extended_compatibility=False):
    if coexistence and mode not in ('smoke', 'interaction'):
        raise SystemExit('Coexistence is supported only for smoke and interaction fixtures')
    relative = ('hologram-coexistence/' + mode) if coexistence else {
        'smoke': 'hologram-smoke', 'interaction': 'hologram-interaction',
        'compatibility': 'hologram-compat'}[mode]
    server = ROOT / 'build' / relative / 'server'
    mods = server / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    selected = [p for p in source.glob('*.jar') if p.name.startswith(SUPPORT_PREFIXES)]
    if not any(p.name.startswith('industrialcraft-2-') for p in selected):
        raise SystemExit('The explicit support source must provide an industrialcraft-2 dev jar')
    for module, version in CACHE_MODULES:
        if extended_compatibility and module == 'CodeChickenCore':
            # The opt-in Gradle runtime supplies CCC 1.4.22. A second jar in mods would reuse its FML ID.
            continue
        explicit = list(source.glob(f'{module}-{version}*.jar'))
        if len(explicit) > 1:
            raise SystemExit(f'Expected one explicit {module} {version} jar, found {len(explicit)}')
        selected.append(explicit[0] if explicit else cached_jar(module, version))
    if extended_compatibility:
        # Gradle supplies the exact Postea API used by ArchitectureCraft; exclude older physical copies.
        selected = [jar for jar in selected
                    if not jar.name.lower().startswith(('cofhlib-', 'cofh-lib-', 'postea-'))]
    if coexistence:
        selected += coexistence_jars(gtit_jar, support_classpath)
    validate_support_jars(selected)
    unexpected = [p.name for p in mods.glob('*.jar') if p.name not in {j.name for j in selected}]
    if unexpected:
        raise SystemExit('Use a fresh fixture directory; unexpected mods: ' + ', '.join(unexpected))
    for jar in selected:
        shutil.copy2(jar, mods / jar.name)
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    library_archive = None
    library = server / 'config' / 'gtmb' / 'hologram-presets.json'
    if mode in ('smoke', 'interaction') and library.is_file():
        if not library.resolve().is_relative_to(server.resolve()):
            raise SystemExit('Preset library must remain inside the isolated server fixture')
        archived_library = server.parent / 'library-archive' / (stamp + '-hologram-presets.json')
        if not archived_library.resolve().is_relative_to(server.parent.resolve()):
            raise SystemExit('Preset library archive must remain inside the isolated fixture')
        if archived_library.exists():
            raise SystemExit('Preset library archive already exists: ' + str(archived_library))
        digest = hashlib.sha256(library.read_bytes()).hexdigest()
        archived_library.parent.mkdir(parents=True, exist_ok=True)
        library.rename(archived_library)
        library_archive = {'original_path': str(library), 'archive_path': str(archived_library),
                           'sha256': digest}
    world_prefix = {'smoke': 'hologram-smoke-', 'interaction': 'hologram-interaction-',
                    'compatibility': 'hologram-compat-'}[mode]
    world = world_prefix + stamp
    receipt_directory = None
    receipt_archive = None
    if mode == 'interaction':
        receipt_directory = server.parent / 'receipts'
        if receipt_directory.exists():
            if receipt_directory.resolve().parent != server.parent.resolve():
                raise SystemExit('Receipt directory must remain inside the isolated fixture')
            receipt_archive = server.parent / 'receipt-archive' / stamp
            if not receipt_archive.resolve().is_relative_to(server.parent.resolve()):
                raise SystemExit('Receipt archive must remain inside the isolated fixture')
            receipt_archive.parent.mkdir(parents=True, exist_ok=True)
            receipt_directory.rename(receipt_archive)
        receipt_directory.mkdir(parents=True, exist_ok=False)
    port = 25581 if mode == 'interaction' else 25579
    (server / 'eula.txt').write_text('eula=true\n', encoding='ascii')
    properties = [
        'level-name=' + world, 'level-type=FLAT', 'generator-settings=2;0;1;',
        'server-port=' + str(port), 'server-ip=127.0.0.1', 'online-mode=false',
        'white-list=' + ('false' if mode == 'interaction' else 'true'), 'spawn-protection=0',
        'spawn-monsters=false', 'spawn-animals=false', 'generate-structures=false',
        'allow-nether=false', 'max-tick-time=120000', 'view-distance=3', 'max-players=1',
        'enable-rcon=false', 'enable-query=false', 'snooper-enabled=false',
        'motd=GTMB isolated hologram fixture',
    ]
    (server / 'server.properties').write_text('\n'.join(properties) + '\n', encoding='ascii')
    receipt = {'working_directory': str(server), 'world': world, 'mode': mode,
               'support_source': str(source), 'mods': [p.name for p in selected],
               'gtit_required': coexistence, 'coexistence': coexistence, 'shared_world_used': False,
               'extended_compatibility': extended_compatibility,
               'runtime_managed_support': ['CodeChickenCore 1.4.22', 'CoFHLib 2388748', 'Postea 1.2.7']
               if extended_compatibility else [],
               'gtit_jar': str(gtit_jar.resolve()) if coexistence else None,
               'gtit_support_classpath': str(support_classpath.resolve()) if coexistence else None,
               'receipt_directory': str(receipt_directory) if receipt_directory else None,
               'previous_receipt_archive': str(receipt_archive) if receipt_archive else None,
               'previous_library_archive': library_archive,
               'binding': f'127.0.0.1:{port}'}
    (server.parent / 'environment.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mods-source', type=Path, required=True,
                        help='Directory of explicit GTNH fixture support jars; no worlds or configs are copied')
    parser.add_argument('--mode', choices=['smoke', 'interaction', 'compatibility'], default='smoke')
    parser.add_argument('--coexistence', action='store_true', help='Use a separate co-installed GTIT/GTMB fixture')
    parser.add_argument('--extended-compatibility', action='store_true',
                        help='Match -PhologramExtendedCompatibility; let Gradle supply CCC, CoFHLib and Postea 1.2.7 once')
    parser.add_argument('--gtit-jar', type=Path, help='Explicit current GTIT compatibility dev jar')
    parser.add_argument('--gtit-support-classpath', type=Path, help='GTIT build/gtmb-compat/support-classpath.txt')
    args = parser.parse_args()
    if not args.mods_source.is_dir():
        parser.error('--mods-source must be an existing directory')
    if args.coexistence and (not args.gtit_jar or not args.gtit_support_classpath):
        parser.error('--coexistence requires --gtit-jar and --gtit-support-classpath')
    if not args.coexistence and (args.gtit_jar or args.gtit_support_classpath):
        parser.error('GTIT arguments require --coexistence')
    print(json.dumps(prepare(args.mode, args.mods_source.resolve(), args.coexistence,
                             args.gtit_jar, args.gtit_support_classpath, args.extended_compatibility)))


if __name__ == '__main__':
    main()
