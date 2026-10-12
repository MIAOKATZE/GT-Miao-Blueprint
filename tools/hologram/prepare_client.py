"""Prepare an isolated client using only the selected server fixture support jars."""
from pathlib import Path
import argparse
import json
import shutil

from prepare_smoke import cached_jar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['smoke', 'interaction'], default='smoke')
    parser.add_argument('--coexistence', action='store_true')
    parser.add_argument('--gtit-jar', type=Path, help='Optional check of the explicit jar used by the prepared server')
    parser.add_argument('--gtit-support-classpath', type=Path,
                        help='Optional check of the support classpath used by the prepared server')
    parser.add_argument('--navigator-jar', type=Path,
                        help='Explicit Navigator dev jar if Navigator 1.1.9 is not in the Gradle cache')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    relative = ('hologram-coexistence/' + args.mode) if args.coexistence else (
        'hologram-interaction' if args.mode == 'interaction' else 'hologram-smoke')
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
    for jar in jars:
        shutil.copy2(jar, mods / jar.name)
    (mods.parent / 'options.txt').write_text(
        'lang:zh_CN\nfullscreen:false\nguiScale:1\nsound:0.0\nmusic:0.0\n', encoding='utf-8')
    print(mods.parent)


if __name__ == '__main__':
    main()
