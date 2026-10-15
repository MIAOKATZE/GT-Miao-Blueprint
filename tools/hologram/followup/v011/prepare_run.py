"""Prepare one fresh isolated follow-up role from a current launch; never start Java."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import uuid
from nbt_reader import read_nbt

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'gradle.properties').is_file() and (parent / 'src').is_dir())
FIXTURES = ROOT / 'build/hologram-followup'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def under(path, parent):
    path, parent = path.resolve(), parent.resolve()
    if not path.is_relative_to(parent):
        raise ValueError('Path escaped isolated build fixture: ' + str(path))
    return path


def require(value, message):
    if not value:
        raise ValueError(message)


def prepare(args):
    require(re.fullmatch(r'[a-z0-9-]+', args.run), 'Fresh --run must contain lowercase letters, digits or hyphens')
    require(args.run.startswith('v011-'), 'Current fixtures require an explicit fresh v011- run name')
    require(1024 <= args.port <= 65535, 'Use an explicit non-privileged loopback port')
    spec = json.loads(args.launch.read_text(encoding='utf-8-sig'))
    require(spec.get('release_version') == '0.1.1', 'Export a current v0.1.1 descriptor')
    require(spec.get('source_hashes') and spec.get('engine_jar_hashes'), 'Launch must bind sources and actual engine bytes')
    for name, checksum in spec['source_hashes'].items():
        require(digest(ROOT / name) == checksum, 'Source changed after export: ' + name)
    for name, checksum in spec['engine_jar_hashes'].items():
        require(digest(Path(name)) == checksum, 'Engine jar changed after export: ' + name)
    for name, checksum in spec.get('reviewed_runtime_hashes', {}).items():
        require(digest(Path(name)) == checksum, 'Reviewed runtime dependency changed after export: ' + name)
    profile, role = spec['profile'], spec['role']
    require(profile in ('area', 'decor', 'essentia', 'workbench', 'sulfur') and role in ('client', 'server'), 'Unknown profile or role')
    require(profile in ('area', 'workbench', 'sulfur') or role == 'server', 'World-only profiles have no separate client')
    original = under(args.support_dir or Path(spec['workingDirectory']), ROOT / 'build')
    require(original.is_dir(), 'Prepared project build support fixture is missing')
    target = under(FIXTURES / args.run, ROOT / 'build')
    manifest = target / 'environment.json'
    if target.exists():
        require(profile in ('area', 'workbench', 'sulfur') and manifest.is_file(), 'Never overwrite an existing fixture')
        environment = json.loads(manifest.read_text(encoding='utf-8'))
        require(environment['profile'] == profile and environment['port'] == args.port, 'Paired roles must use identical profile/port')
        if profile == 'sulfur':
            require(environment.get('sulfur_phase') == args.phase, 'Paired sulfur roles must use the same source/target phase')
            require(environment.get('sulfur_intent') == args.intent, 'Paired sulfur roles must use the same actual tendency')
        require(not (target / role).exists(), 'This role already exists; use a fresh run')
    else:
        environment = dict(release_version='0.1.1', profile=profile, run=args.run, port=args.port,
            created_utc=datetime.now(timezone.utc).isoformat(),
            world=('hologram-sulfur-' if profile == 'sulfur' else 'hologram-followup-') + args.run,
            user_worlds_copied=False, user_configs_copied=False, accounts_read=False,
            shared_running_instances_touched=False, roles={})
        target.mkdir(parents=True, exist_ok=False)
        (target / 'receipts').mkdir()
    working = target / role
    mods = working / 'mods'
    jars = sorted((original / 'mods').glob('*.jar'))
    if profile == 'sulfur':
        require(len(spec.get('reviewed_runtime_hashes', {})) == 2, 'Sulfur descriptor must bind both reviewed runtime dependency SHA256 values')
        classpath = spec['arguments'][spec['arguments'].index('-cp') + 1]
        require('AE2FluidCraft-Rework-1.5.113-gtnh-dev.jar' in classpath,
                'Sulfur launch requires the reviewed native AE2FC 1.5.113 runtime')
        require('gtswn-v1.8.66-dev.jar' in classpath,
                'Sulfur launch requires the reviewed GTSWN 1.8.66 runtime')
        jars = [jar for jar in jars if not jar.name.startswith(('AE2FluidCraft-Rework-', 'gtswn-'))]
    require(jars, 'Prepared support fixture has no mod jars')
    require(all(not jar.is_symlink() for jar in jars), 'Support jar symlinks are not allowed')
    mods.mkdir(parents=True, exist_ok=False)
    for jar in jars:
        shutil.copy2(jar, mods / jar.name)
    if role == 'client':
        (working / 'options.txt').write_text('lang:zh_CN\nfullscreen:false\nguiScale:2\npauseOnLostFocus:false\n'
            'soundCategory_master:0.0\nsoundCategory_music:0.0\n', encoding='utf-8')
    else:
        (working / 'eula.txt').write_text('eula=true\n', encoding='ascii')
        properties = [
            'level-name=' + environment['world'], 'level-type=FLAT', 'generator-settings=2;0;1;',
            'server-port=' + str(args.port), 'server-ip=127.0.0.1', 'online-mode=false',
            'spawn-protection=0', 'spawn-monsters=false', 'spawn-animals=false',
            'generate-structures=false', 'allow-nether=false', 'view-distance=3',
            'max-players=1', 'enable-rcon=false', 'enable-query=false', 'snooper-enabled=false',
            'motd=GTMB v0.1.1 isolated follow-up fixture']
        (working / 'server.properties').write_text('\n'.join(properties) + '\n', encoding='ascii')
    arguments = [value for value in spec['arguments'] if not value.startswith((
        '-Dgtmb.hologram.followupRoot=', '-Dgtmb.hologram.followupReceiptDir=', '-Dgtmb.hologram.followupAddress='))]
    arguments[:0] = ['-Dgtmb.hologram.followupRoot=' + str(target),
                    '-Dgtmb.hologram.followupReceiptDir=' + str(target / 'receipts'),
                    '-Dgtmb.hologram.followupAddress=127.0.0.1:' + str(args.port)]
    if profile == 'sulfur':
        arguments[:0] = ['-Dgtmb.hologram.sulfurLinePhase=' + args.phase,
                        '-Dgtmb.hologram.sulfurLineIntent=' + args.intent,
                        '-Dgtmb.hologram.sulfurLineDimension=' + str(args.dimension if args.dimension is not None else
                            (0 if args.phase == 'source' else 777 if args.intent == 'self' else 778)),
                        '-Dgtmb.hologram.sulfurLineRole=' + ('author' if args.phase == 'source' else 'placer'),
                        '-Dgtmb.hologram.sulfurLineAddress=127.0.0.1:' + str(args.port)]
        environment['sulfur_phase'] = args.phase
        environment['sulfur_intent'] = args.intent
        environment['sulfur_dimension'] = args.dimension if args.dimension is not None else (0 if args.phase == 'source'
            else 777 if args.intent == 'self' else 778)
        if args.phase == 'target':
            require(args.source_fixture is not None, 'Target must import an explicit actual v0.1.1 source fixture')
            source = under(args.source_fixture, FIXTURES)
            source_env = json.loads((source / 'environment.json').read_text(encoding='utf-8'))
            require(source_env['release_version'] == '0.1.1' and source_env['profile'] == 'sulfur'
                    and source_env['sulfur_phase'] == 'source' and source_env['sulfur_intent'] == args.intent,
                    'Target cannot substitute another version, role, or tendency source')
            transfer = source / 'receipts/sulfur-author-transfer.nbt'
            require(transfer.is_file() and not transfer.is_symlink(), 'Actual source author transfer is missing')
            proof = read_nbt(transfer)
            require(proof.get('passed') and proof.get('phase') == 'SOURCE_COMPLETE' and proof.get('intent') == args.intent
                    and (source / 'receipts/sulfur-server-pass.txt').is_file()
                    and not (source / 'receipts/sulfur-server-fail.txt').exists(), 'Source did not finish this actual tendency')
            destination = target / 'receipts/sulfur-author-transfer.nbt'
            if destination.exists():
                require(digest(destination) == digest(transfer), 'Paired target roles changed their source transfer bytes')
            else:
                shutil.copy2(transfer, destination)
            environment['source_fixture'] = str(source)
            environment['source_transfer_sha256'] = digest(transfer)
    if role == 'client':
        if '--gameDir' in arguments:
            arguments[arguments.index('--gameDir') + 1] = str(working)
        else:
            arguments.extend(['--gameDir', str(working)])
    if role == 'client':
        username = args.username or (arguments[arguments.index('--username') + 1]
                                     if '--username' in arguments else 'GTMBFollowup')
        require(re.fullmatch(r'[A-Za-z0-9_]{3,16}', username), 'Invalid offline fixture player name')
        if '--username' in arguments:
            arguments[arguments.index('--username') + 1] = username
        else:
            arguments.extend(['--username', username])
        offline_uuid = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + username).encode('utf-8')).digest(), version=3))
        if '--uuid' in arguments:
            arguments[arguments.index('--uuid') + 1] = offline_uuid
        else:
            arguments.extend(['--uuid', offline_uuid])
        environment['offline_fixture_username'] = username
        environment['offline_fixture_uuid'] = offline_uuid
    spec.update(workingDirectory=str(working), arguments=arguments)
    launch = target / (role + '-launch.json')
    launch.write_text(json.dumps(spec, indent=2), encoding='utf-8')
    environment['roles'][role] = dict(working_directory=str(working), launch=str(launch), launch_sha256=digest(launch),
        support_source=str(original), support_jars={jar.name: digest(jar) for jar in jars})
    manifest.write_text(json.dumps(environment, indent=2), encoding='utf-8')
    return dict(fixture=str(target), role=role, launch=str(launch), java_started=False,
                user_worlds_or_credentials_copied=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launch', required=True, type=Path)
    parser.add_argument('--run', required=True)
    parser.add_argument('--port', type=int, default=25592)
    parser.add_argument('--phase', choices=('source', 'target'), default='source')
    parser.add_argument('--intent', choices=('self', 'share'), default='self')
    parser.add_argument('--source-fixture', type=Path, help='Exact completed current source fixture for target transfer')
    parser.add_argument('--dimension', type=int, help='Fresh fixture dimension; defaults source=0, target-self=777, target-share=778')
    parser.add_argument('--username', help='Explicit offline fixture player; never a launcher account')
    parser.add_argument('--support-dir', type=Path, help='Already cloned project build fixture; no external/user instance path')
    args = parser.parse_args()
    print(json.dumps(prepare(args), ensure_ascii=False))


if __name__ == '__main__':
    main()
