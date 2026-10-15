"""Verify and archive the current 0.1.1 paired AREA/placement input matrix; never start Java."""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import struct
import zlib

from nbt_reader import read_nbt

ROOT = Path(__file__).resolve().parents[4]
LABELS = [
    'begin-ack', 'right-corner-first-ack', 'other-slot-paused-no-input-consumption',
    'resume-left-completes-original-add-mode', 'alt-zero-current-red-target-feet',
    'alt-current-red-target-equals-server-corner', 'held-projector-escape-ack', 'other-slot-escape-ack',
    'no-alt-hit-red-cursor-server-corner', 'zero-distance-no-alt-hit-retains-block', 'no-hit-default-air',
    'alt-hit-forced-air', 'alt-zero-with-hit-feet', 'release-alt-zero-restores-hit-block',
    'ctrl-shift-right-first-subtract-ack', 'plain-left-completes-subtract-mode', 'ordinary-wheel-pauses-selection',
    'moving-no-alt-hit-face', 'moving-alt-hit-forced-air', 'moving-alt-right-locks-forced-air',
    'moving-zero-distance-no-alt-hit-face', 'moving-alt-zero-feet', 'moving-escape-cancel-ack',
]
OFFSETS = [(0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0)]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def actual_run(fixture, role, log):
    launch_path = fixture / (role + '-launch.json')
    spec = json.loads(launch_path.read_text(encoding='utf-8-sig'))
    receipt_path = Path(str(log) + '.receipt.json')
    run = json.loads(receipt_path.read_text(encoding='utf-8-sig'))
    require(spec.get('release_version') == '0.1.1' and spec.get('profile') == 'area' and spec.get('role') == role,
            'Current 0.1.1 AREA role launch required: ' + role)
    require(run['exit_code'] == 0 and run['log_sha256'] == sha(log)
            and run['launch_sha256'] == sha(launch_path), 'Actual Java exit/launch/log integrity failed: ' + role)
    working = (fixture / role).resolve()
    require(Path(spec['workingDirectory']).resolve() == Path(run['working_directory']).resolve() == working,
            'Actual run escaped its fresh role directory: ' + role)
    arguments = spec['arguments']
    flag = '-Dgtmb.hologram.followupArea' + ('Client' if role == 'client' else 'Server') + '=true'
    require(arguments.count(flag) == 1 and '-Dgtmb.hologram.realInteractionSmoke=true' not in arguments,
            'Selected AREA driver must run alone: ' + role)
    if role == 'client':
        indices = [index for index, value in enumerate(arguments)
                   if value == '--gameDir' or value.startswith('--gameDir=')]
        require(len(indices) == 1 and arguments[indices[0]] == '--gameDir'
                and indices[0] + 1 < len(arguments)
                and Path(arguments[indices[0] + 1]).is_absolute()
                and Path(arguments[indices[0] + 1]).resolve() == working,
                'Actual client --gameDir must equal its fresh cwd')
    require(spec.get('source_hashes') and spec.get('engine_jar_hashes'), 'Launch lacks source/engine hashes')
    for name, checksum in spec['source_hashes'].items():
        path = (ROOT / name).resolve()
        require(path.is_relative_to(ROOT.resolve()) and sha(path) == checksum,
                'Source changed since actual launch: ' + name)
    for name, checksum in spec['engine_jar_hashes'].items():
        require(sha(Path(name)) == checksum, 'Actual engine jar changed since launch: ' + name)
    raw = log.read_bytes()
    text = raw.decode('utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')
    require('FINAL FAIL' not in text and 'follow-up fixture: ' not in text,
            'Actual role log contains a fixture assertion failure: ' + role)
    return spec, run


def bound(row, role, launches, fixture):
    require(row['releaseVersion'] == '0.1.1' and row['sourceRole'] == role
            and row['sourceLaunchSha256'] == sha(fixture / (role + '-launch.json'))
            and isinstance(row['session'], str) and isinstance(row['planRevision'], int),
            'Receipt source/session/revision binding differs from the actual launch: ' + role)
    require(launches[role]['source_hashes'] == launches['client']['source_hashes'],
            'Client/server launched different source inventories')


def recent(path, runs):
    require(path.is_file(), 'Missing new receipt: ' + str(path))
    modified = path.stat().st_mtime
    require(any(datetime.fromisoformat(run['started_utc']).timestamp() - 2 <= modified
                <= datetime.fromisoformat(run['finished_utc']).timestamp() + 2 for run in runs),
            'Receipt is outside the selected actual processes: ' + str(path))


def normalized_inventory(rows):
    result = copy.deepcopy(rows)
    for item in result:
        if item['slot'] == 0:
            tags = item.get('tag', {})
            for field in ('gtitHologramAreaSelecting', 'gtitHologramPreset'):
                tags.pop(field, None)
            # The native import/save path persists these exact existing defaults on the same projector.
            defaults = {'gtitHologramMain': 1, 'gtitHologramMainExplicit': 0, 'gtitHologramDowngrade': 0,
                        'gtitHologramMaterials': {'main': 1, 'containers': 1, 'me': 1,
                                                 'priority': 0, 'recoveryDestination': 0}}
            for field, default in defaults.items():
                if tags.get(field) == default:
                    tags.pop(field)
            if not tags:
                item.pop('tag', None)
    return result


def floating(aim):
    distance = aim['distance']
    require(0 <= distance <= 64, 'Native local distance escaped 0..64')
    if distance == 0:
        return aim['feet']
    look = [aim['look' + axis] for axis in 'XYZ']
    length = math.sqrt(sum(component * component for component in look))
    require(abs(length - 1) < 1e-5, 'Actual camera look vector is not unit length')
    return [math.floor(aim['eye' + axis] + look[index] * distance / length)
            for index, axis in enumerate('XYZ')]


def attached(aim):
    require(aim['nativeBlockHit'] and 0 <= aim['hitSide'] <= 5, 'Native block face required')
    return [value + offset for value, offset in zip(aim['hitBlock'], OFFSETS[aim['hitSide']])]


def saved_pair(fixture, local_id, runs):
    json_path = fixture / 'client/MiaoBlueprints' / (local_id + '.json')
    png_path = json_path.with_suffix('.png')
    recent(json_path, runs)
    recent(png_path, runs)
    require(json_path.stat().st_size <= 8 * 1024 * 1024 and png_path.stat().st_size <= 4 * 1024 * 1024,
            'Actual saved native pair exceeds its artifact budget')
    document = json.loads(json_path.read_text(encoding='utf-8-sig'))
    blueprint = document.get('blueprint', document)
    require(blueprint['id'] == local_id and blueprint['name'] == 'Area v0.1.1 native stones'
            and blueprint['nativeBlueprint']['gtmbVersion'] == '0.1.1'
            and len(blueprint['cells']) == 2
            and sorted((cell['x'], cell['y'], cell['z']) for cell in blueprint['cells']) == [(0, 0, 0), (1, 0, 0)]
            and all(cell['block'] == 'minecraft:stone' and cell['item'] == 'minecraft:stone'
                    and cell['metadata'] == cell['damage'] == 0 and not cell.get('excluded')
                    for cell in blueprint['cells']),
            'Actual saved placement source differs from the two native imported stones')
    raw = png_path.read_bytes()
    require(raw[:16] == b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR'
            and struct.unpack('>II', raw[16:24]) == (640, 640), 'Actual private saved preview is not native 640x640 PNG')
    offset, sources = 8, []
    while offset < len(raw):
        require(offset + 12 <= len(raw), 'Truncated actual native PNG chunk')
        length = struct.unpack('>I', raw[offset:offset + 4])[0]
        kind = raw[offset + 4:offset + 8]
        end = offset + 12 + length
        require(end <= len(raw), 'Actual native PNG chunk exceeds the file')
        payload = raw[offset + 8:offset + 8 + length]
        require(struct.unpack('>I', raw[end - 4:end])[0] == zlib.crc32(kind + payload) & 0xffffffff,
                'Actual native saved PNG has an invalid chunk CRC')
        if kind == b'tEXt' and payload.startswith(b'gtmb.sourceJsonSha256\x00'):
            sources.append(payload.split(b'\x00', 1)[1].decode('ascii'))
        offset = end
    require(sources == [sha(json_path)], 'Actual saved PNG is not bound to its exact generated JSON bytes')
    return [json_path, png_path]


def packet(row):
    native = row['nativeInput']
    require(native['actionCountAfter'] == native['actionCountBefore'] + 1,
            'Native input did not emit exactly one real Netty Action')
    value = native['packet']
    require(value['session'] == row['session'] and value['planRevision'] <= row['planRevision'],
            'Actual packet belongs to another Service session or a later revision')
    return native, value


def verify_rows(receipts, launches, runs, fixture):
    observed, selected, initial_inventory, last_frame = {}, [], None, -1
    for sequence, label in enumerate(LABELS, 1):
        client_path = receipts / f'area-client-step-{sequence}.nbt'
        server_path = receipts / f'area-server-step-{sequence}.nbt'
        client, server = read_nbt(client_path), read_nbt(server_path)
        bound(client, 'client', launches, fixture)
        bound(server, 'server', launches, fixture)
        require(client['passed'] and server['passed'] and client['sequence'] == server['sequence'] == sequence
                and client['label'] == server['label'] == label, 'Missing exact matrix row: ' + label)
        for key in ('session', 'planRevision', 'heldSlot', 'selecting', 'points', 'virtualPlacement', 'virtualLocked', 'serverAnchor'):
            require(client[key] == server[key], 'Actual client/server state differs for ' + label + ': ' + key)
        require(server['clientSourceLaunchSha256'] == client['sourceLaunchSha256'] and server['sourceUnchanged'],
                'Independent server source/client-launch corroboration failed: ' + label)
        require(client['capturePhase'] == 'RenderTick.END'
                and client['actualRenderFrame'] > max(last_frame, client['requestedAfterRenderFrame']),
                'Missing a fresh actual END framebuffer for ' + label)
        last_frame = client['actualRenderFrame']
        snapshot = client['nettySnapshot']
        require(snapshot['session'] == client['session'] and snapshot['planRevision'] == client['planRevision']
                and snapshot['areaPoints'] == client['points']
                and re.fullmatch('[a-f0-9]{64}', client['snapshotSha256']),
                'Captured Netty snapshot identity differs from the observed row: ' + label)
        png = receipts / client['png']
        require(png.parent == receipts and png.name == f'area-{sequence}-{label}.png'
                and sha(png) == client['pngSha256'], 'Actual framebuffer PNG identity/hash differs: ' + label)
        header = png.read_bytes()[:24]
        require(header[:16] == b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR'
                and all(dimension >= 480 for dimension in struct.unpack('>II', header[16:24])),
                'Actual world framebuffer has invalid or placeholder dimensions: ' + label)
        inventory = normalized_inventory(server['inventory'])
        if initial_inventory is None:
            initial_inventory = inventory
        require(inventory == initial_inventory, 'Inventory changed beyond native tool selection metadata: ' + label)
        if client['cursorRequired']:
            require(len(client.get('drawnCurrentRedTarget', [])) == 3
                    and client['drawnCurrentRedTarget'] == client['expectedTarget'],
                    'Actual drawn red cursor differs from the requested native target: ' + label)
        if sequence in (3, 8, 17):
            require(client['heldSlot'] == 1 and not client.get('drawnCurrentRedTarget'),
                    'Paused context consumed or drew an active cursor: ' + label)
        else:
            require(client['heldSlot'] == 0, 'Actual native input has the wrong held projector slot: ' + label)
        if sequence == 2:
            require(client['points'] == [51, 80, 44] and snapshot['areaPendingCorner'] == 2
                    and snapshot['areaPointMode'] == 'add', 'Right-first Ctrl did not lock add mode')
        if sequence == 3:
            require(server['worldTick'] - observed[2][1]['worldTick'] >= 8
                    and client['points'] == observed[2][0]['points']
                    and client['actionCount'] == observed[2][0]['actionCount'],
                    'Eight-tick native C09 suspension consumed input or lost corners')
        if sequence == 4:
            require(client['points'] == [50, 80, 44, 51, 80, 44]
                    and len(server['regions']) == 1 and server['regions'][0]['mode'] == 'add',
                    'Plain left did not complete the original reversed add pair')
        if sequence in (5, 6):
            require(60 <= client['actualCameraPitch'] <= 85 and client['actualNativeAim']['alt'],
                    'Original Alt-zero frame lost its downward actual camera/modifier')
        if sequence == 6:
            require(client['points'] == client['drawnCurrentRedTarget'] == observed[5][0]['drawnCurrentRedTarget'],
                    'Original actual Alt-zero red target and server corner differ')
        if sequence in (7, 8):
            require(not client['selecting'] and client['points'] == [], 'Held/suspended Esc retained an area operation')
        if 9 <= sequence <= 16:
            native, sent = packet(client)
            require(sent == server['observedNativePacket'] and sent['action'] == 'areaPoint'
                    and [sent[axis] for axis in 'xyz'] == client['expectedTarget'],
                    'Actual packet and independent server target disagree: ' + label)
            if sequence in (9, 10, 14):
                require(native['nativeBlockHit'] and not native['alt'] and not sent['atPlayer']
                        and client['expectedTarget'] == native['hitBlock'],
                        'Non-Alt hit was replaced by floating/feet aiming: ' + label)
            if sequence in (10, 14):
                require(native['distance'] == 0, 'Zero-distance precedence row did not actually use zero distance')
            if sequence == 11:
                require(not native['nativeBlockHit'] and not native['alt'] and native['distance'] == 6
                        and client['expectedTarget'] == floating(native) and not sent['atPlayer'],
                        'Native sky/no-hit input did not default to the floating ray')
            if sequence == 12:
                require(native['nativeBlockHit'] and native['alt'] and native['distance'] == 6
                        and client['expectedTarget'] == floating(native) != native['hitBlock'] and not sent['atPlayer'],
                        'Actual Alt hit did not force the floating ray')
            if sequence == 13:
                require(native['nativeBlockHit'] and native['alt'] and native['distance'] == 0 and sent['atPlayer']
                        and client['expectedTarget'] == native['feet'] == server['feet'],
                        'Actual Alt-zero hit did not use independent player feet')
            if sequence == 15:
                require(native['control'] and native['shift'] and sent['point'] == 2 and sent['mode'] == 'subtract'
                        and snapshot['areaPendingCorner'] == 2 and snapshot['areaPointMode'] == 'subtract',
                        'Actual Ctrl+Shift right-first did not lock subtract mode')
            if sequence == 16:
                require(not native['control'] and not native['shift'] and sent['point'] == 1
                        and len(server['regions']) == 2 and server['regions'][-1]['mode'] == 'subtract',
                        'Plain left did not preserve first-corner subtract mode')
        if sequence == 17:
            wheel = client['nativeWheel']
            require(wheel['delta'] == -120 and not wheel['alt'] and not wheel['canceled']
                    and wheel['slotBefore'] == 0 and wheel['slotAfter'] == 1
                    and wheel['nativeBranch'] == 'InventoryPlayer.changeCurrentItem'
                    and client['points'] == observed[16][0]['points']
                    and server['worldTick'] - observed[16][1]['worldTick'] >= 8,
                    'Ordinary wheel did not take the uncanceled native hotbar/C09 suspension path')
        if 18 <= sequence <= 22:
            drawn, aim = client['drawnWorldPreview'], client['nativeAim']
            require(drawn['session'] == client['session'] and drawn['planRevision'] == client['planRevision']
                    and drawn['generation'] > client['requestedAfterWorldGeneration'] and drawn['submittedLists'] > 0
                    and drawn['anchor'] == client['expectedTarget'] and client['virtualPlacement'],
                    'Actual submitted world geometry uses another source, frame or anchor: ' + label)
            locked = sequence == 20
            require(bool(client['virtualLocked']) == locked and bool(drawn['moving']) != locked
                    and drawn['outlineColor'] == (0x66d9ef if locked else 0x70e69a),
                    'Actual green/cyan placement phase or outline differs: ' + label)
            if sequence in (18, 21):
                require(not aim['alt'] and aim['nativeBlockHit'] and drawn['anchor'] == attached(aim),
                        'Unmodified moving preview lost actual native face attachment')
            if sequence == 21:
                require(aim['distance'] == 0, 'Moving zero-distance face row did not use zero distance')
            if sequence == 19:
                require(aim['alt'] and aim['nativeBlockHit'] and aim['distance'] == 6
                        and drawn['anchor'] == floating(aim) != attached(aim),
                        'Alt moving preview failed to force air despite native block hit')
            if sequence == 20:
                native, sent = packet(client)
                require(sent['action'] == 'confirmVirtualPosition' and native['alt'] and native['nativeBlockHit']
                        and [sent[axis] for axis in 'xyz'] == drawn['anchor'] == server['serverAnchor']
                        == observed[19][0]['drawnWorldPreview']['anchor'],
                        'Actual Alt-right packet/server ACK did not lock the previously drawn forced-air preview')
            if sequence == 22:
                require(aim['alt'] and aim['nativeBlockHit'] and aim['distance'] == 0
                        and drawn['anchor'] == aim['feet'] == server['feet'],
                        'Actual Alt-zero green preview did not use independent player feet')
        if sequence == 23:
            require(not client['virtualPlacement'] and not client['selecting'], 'Final native Esc retained a placement/area operation')
        observed[sequence] = (client, server)
        selected.extend([client_path, server_path, png])
    for path in selected:
        recent(path, runs)
    return observed, selected, initial_inventory


def collect(args):
    fixture = args.fixture.resolve()
    require(fixture.is_relative_to((ROOT / 'build/hologram-followup').resolve()), 'Only a fresh isolated follow-up fixture is accepted')
    environment = json.loads((fixture / 'environment.json').read_text(encoding='utf-8-sig'))
    require(environment['profile'] == 'area' and environment['release_version'] == '0.1.1'
            and environment.get('user_worlds_copied') is False
            and environment.get('shared_running_instances_touched') is False,
            'Current isolated AREA environment/version required')
    launches, runs = {}, []
    for role, log in [('server', args.server_log), ('client', args.client_log)]:
        launches[role], run = actual_run(fixture, role, log.resolve())
        runs.append(run)
    receipts = fixture / 'receipts'
    require(not list(receipts.glob('*fail*')), 'Actual fixture contains failure evidence; archive it as FAIL')
    observed, selected, inventory = verify_rows(receipts, launches, runs, fixture)
    artifact_paths = []
    for role in ('client', 'server'):
        final_path = receipts / ('area-' + role + '-final.nbt')
        final = read_nbt(final_path)
        bound(final, role, launches, fixture)
        require(final['passed'] and final['observations'] == len(LABELS)
                and final['session'] == observed[23][0]['session']
                and final['planRevision'] == observed[23][0]['planRevision'], 'Final current role proof is incomplete: ' + role)
        if role == 'server':
            require(final['sourceUnchanged'] and normalized_inventory(final['inventory']) == inventory,
                    'Final independent source/inventory conservation failed')
        else:
            require(final['actualNettyClient'] and not final['syntheticSnapshotsUsed']
                    and re.fullmatch('local_[a-fA-F0-9-]{36}', final['savedLocalId']),
                    'Placement source was not saved through the actual private Netty workflow')
            controls = final['nativeUiInputs']
            ids = [entry['controlId'] for entry in controls if 'controlId' in entry]
            require(ids[:2] == [149, 140] and ids[-1] == 141 and set(ids) <= {149, 140, 181, 141}
                    and all(entry['renderGeneration'] > 0 and entry['session'] and entry['planRevision'] >= 0 for entry in controls),
                    'Actual minimal native import/save/place control chain is missing')
            names = [entry for entry in controls if entry.get('kind') == 'native-keyboard-name']
            require(len(names) == 1 and names[0]['text'] == 'Area v0.1.1 native stones'
                    and names[0]['focusedAfterNativeClick'] and names[0]['ctrlASelectedAll'],
                    'Actual private blueprint name was not entered with native keyboard input')
            placed = controls[-1]['nettySnapshot']
            require(placed['blueprintSaved'] and not placed['blueprintDirty']
                    and placed['libraryLocalId'] == final['savedLocalId'] and len(placed['cells']) == 2,
                    'Native placement did not use the actual acknowledged two-stone save')
            artifact_paths = saved_pair(fixture, final['savedLocalId'], runs)
        selected.extend([final_path, receipts / ('area-' + role + '-pass.txt')])
    ready = receipts / 'area-server-ready.nbt'
    bound(read_nbt(ready), 'server', launches, fixture)
    selected.append(ready)
    for path in selected:
        recent(path, runs)
    output = args.output.resolve()
    require(output.is_relative_to((ROOT / 'plan/evidence/v0.1.1').resolve()) and not output.exists(),
            'Use a fresh 0.1.1 evidence archive; old evidence must remain immutable')
    copies = [(path, 'receipts/' + path.name) for path in selected]
    copies.extend((path, 'saved/' + path.name) for path in artifact_paths)
    copies.append((fixture / 'environment.json', 'environment.json'))
    for role, log in [('server', args.server_log), ('client', args.client_log)]:
        copies.extend([(fixture / (role + '-launch.json'), role + '-launch.json'), (log, role + '.log'),
                       (Path(str(log) + '.receipt.json'), role + '-actual-run.json')])
    copies.extend((Path(__file__).parent / name, 'tooling/' + name) for name in ('collect_area.py', 'nbt_reader.py'))
    output.mkdir(parents=True, exist_ok=False)
    files = {}
    for source, destination in copies:
        target = output / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        files[destination] = {'bytes': target.stat().st_size, 'sha256': sha(target)}
    result = {'release_version': '0.1.1', 'profile': 'area', 'status': 'PASS_FOR_LISTED_SCOPE',
              'collected_utc': datetime.now(timezone.utc).isoformat(), 'fixture': str(fixture),
              'historical_pass_reused': False, 'tests_not_run_by_collector': True,
              'observations': len(LABELS), 'human_frame_review_required': True,
              'verified_scope': ['native reverse corners and locked add/subtract modifiers',
                                 'native C09 pause/resume, uncanceled ordinary wheel, held/suspended Esc ACKs',
                                 'native hit/no-hit/Alt/zero-distance area cursor and exact outgoing Action packets',
                                 'native two-stone import, keyboard name, private save owner ACK and placement control',
                                 'actual green hit-face/Alt/zero-distance preview, Alt-right cyan lock and final Esc',
                                 '23 exact RenderTick.END PNGs with independent source/inventory/server state'],
              'files': files}
    (output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--server-log', type=Path, required=True)
    parser.add_argument('--client-log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = collect(args)
    print(json.dumps({key: result[key] for key in ('status', 'profile', 'observations')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
