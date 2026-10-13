"""Prepare an isolated client using only the selected server fixture support jars."""
from pathlib import Path
import argparse
import json

from prepare_smoke import cached_jar, copy_support_jar, validate_support_jars


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['smoke', 'interaction', 'reference'], default='smoke')
    parser.add_argument('--coexistence', action='store_true')
    parser.add_argument('--extended-compatibility', action='store_true')
    parser.add_argument('--gtit-jar', type=Path, help='Optional check of the explicit jar used by the prepared server')
    parser.add_argument('--gtit-support-classpath', type=Path,
                        help='Optional check of the support classpath used by the prepared server')
    parser.add_argument('--navigator-jar', type=Path,
                        help='Explicit Navigator dev jar if Navigator 1.1.9 is not in the Gradle cache')
    args = parser.parse_args()
    if args.mode == 'reference' and (args.coexistence or args.extended_compatibility):
        parser.error('Machine references require their separate GTMB-only profile')
    root = Path(__file__).resolve().parents[2]
    relative = ('hologram-coexistence/' + args.mode) if args.coexistence else (
        {'smoke': 'hologram-smoke', 'interaction': 'hologram-interaction', 'reference': 'hologram-reference'}[args.mode])
    fixture = root / 'build' / relative
    source = fixture / 'server/mods'
    if not source.is_dir():
        parser.error('Prepare the corresponding fresh server fixture first')
    environment = fixture / 'environment.json'
    if not environment.is_file():
        parser.error('The corresponding server environment receipt is required')
    receipt = json.loads(environment.read_text(encoding='utf-8'))
    if bool(receipt.get('coexistence')) != args.coexistence or receipt.get('mode') != args.mode:
        parser.error('Client mode must match its prepared server fixture')
    if bool(receipt.get('extended_compatibility')) != args.extended_compatibility:
        parser.error('Extended compatibility mode must match its prepared server fixture')
    for option, key in [(args.gtit_jar, 'gtit_jar'), (args.gtit_support_classpath, 'gtit_support_classpath')]:
        if option and (not args.coexistence or str(option.resolve()) != receipt.get(key)):
            parser.error('Explicit GTIT inputs must match the prepared coexistence server')
    if args.mode == 'interaction':
        if not environment.is_file() or not (fixture / 'receipts').is_dir():
            parser.error('Prepare a fresh interaction server and receipt directory first')
        if receipt.get('mode') != 'interaction' or not receipt.get('world', '').startswith('hologram-interaction-'):
            parser.error('Interaction client requires the matching fresh interaction server fixture')
    mods = fixture / 'client/mods'
    mods.mkdir(parents=True, exist_ok=True)
    jars = list(source.glob('*.jar'))
    if args.coexistence:
        # VisualProspecting's client JourneyMap bridge resolves Navigator during construction.
        navigator = args.navigator_jar or cached_jar('Navigator', '1.1.9')
        if not navigator.is_file() or not navigator.name.startswith('Navigator-') or navigator.suffix != '.jar':
            parser.error('The explicit Navigator input must be an existing Navigator dev jar')
        jars.append(navigator)
    elif args.navigator_jar:
        parser.error('--navigator-jar is only needed for the coexistence VisualProspecting client')
    unexpected = [p.name for p in mods.glob('*.jar') if p.name not in {j.name for j in jars}]
    if unexpected:
        raise SystemExit('Use a fresh fixture directory; unexpected client mods: ' + ', '.join(unexpected))
    validate_support_jars(jars)
    cache_invalidations = []
    for jar in jars:
        invalidation = copy_support_jar(jar, mods / jar.name)
        if invalidation:
            cache_invalidations.append(invalidation)
    (mods.parent / 'options.txt').write_text(
        'lang:zh_CN\nfullscreen:false\nguiScale:1\nsoundCategory_master:0.0\nsoundCategory_music:0.0\nsoundCategory_record:0.0\n', encoding='utf-8')
    (fixture / 'client-environment.json').write_text(json.dumps({
        'working_directory': str(mods.parent), 'world': receipt['world'], 'mode': args.mode,
        'extended_compatibility': args.extended_compatibility, 'mods': [jar.name for jar in jars],
        'server_environment': str(environment), 'shared_world_used': False,
        'negative_dependency_scan_cache_invalidations': cache_invalidations,
    }, indent=2), encoding='utf-8')
    print(mods.parent)


if __name__ == '__main__':
    main()
