"""Bounded native evidence readers and unchanged save-transaction validators, copied from Sulfur02.

These functions only read artifacts. They never launch a game or synthesize runtime evidence.
"""
import base64
import copy
from datetime import datetime, timezone
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import struct
import uuid
import zipfile
import zlib
from collect_current import ROOT, FIXTURES, actual_run, recent, require, sha
from nbt_reader import Reader, read_nbt
from workbench_validation import verify_blueprint_pair, verify_native_preview, verify_save_transaction
LIMIT = 32 * 1024 * 1024
def json_file(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_file(path, parent):
    require(path.is_file() and not path.is_symlink(), 'Missing or linked evidence: ' + str(path))
    require(path.resolve().is_relative_to(parent.resolve()), 'Evidence escaped isolated directory: ' + str(path))
    attributes = getattr(path.lstat(), 'st_file_attributes', 0)
    require(not attributes & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0), 'Reparse evidence is forbidden: ' + str(path))
    return path


def unpack(raw):
    require(len(raw) <= LIMIT, 'Compressed NBT exceeds limit')
    if raw[:2] == b'\x1f\x8b':
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
            raw = stream.read(LIMIT + 1)
    require(len(raw) <= LIMIT, 'Expanded NBT exceeds limit')
    return raw


def nbt_bytes(raw, *, typed=False):
    reader = Reader(unpack(raw), typed=typed)
    require(reader.number('B') == 10, 'Expected native compound NBT')
    reader.string()
    result = reader.payload(10)
    require(not reader.stream.read(1), 'Trailing native NBT bytes')
    return result


def typed_compounds(path):
    """Extract top-level typed compound bytes for Java's compressed UI/snapshot hashes."""
    raw = unpack(path.read_bytes())
    reader = Reader(raw)
    require(reader.number('B') == 10, 'Frame has no native compound root')
    reader.string()
    result = {}
    while True:
        kind = reader.number('B')
        if kind == 0:
            break
        key = reader.string()
        start = reader.stream.tell()
        reader.payload(kind, 1)
        if kind == 10:
            result[key] = b'\x0a\x00\x00' + raw[start:reader.stream.tell()]
    require(not reader.stream.read(1), 'Trailing frame NBT bytes')
    return result


def java_gzip_hash(raw):
    # GZIPOutputStream's fixed header has MTIME=0, XFL=0 and OS=0.
    compressor = zlib.compressobj(level=-1, wbits=-15)
    compressed = compressor.compress(raw) + compressor.flush()
    header = bytes.fromhex('1f8b0800000000000000')
    trailer = struct.pack('<II', zlib.crc32(raw) & 0xffffffff, len(raw) & 0xffffffff)
    return digest(header + compressed + trailer)


def tag_at(tag, *path):
    for key in path:
        tag = tag[1][key] if tag[0] == 10 else tag[1][1][key]
    return tag


def snapshot_identity(snapshot, actor, session):
    require(snapshot.get('session') == session and session, 'Actual Netty snapshot session mismatch')
    # Player identity is independently bound by the server receipt and frame, rather than client cell contents.
    require(snapshot.get('planRevision', -1) >= 0, 'Actual Netty snapshot has no native plan revision')


def native_completion(machine, typed_machine, line, typed_line):
    stage = machine['stage']
    require(tag_at(typed_machine, 'stage') == (3, stage) and 0 <= stage < 3
            and tag_at(typed_machine, 'formed') == (1, 1)
            and tag_at(typed_machine, 'observedProgress')[0] == 3 and machine['observedProgress'] > 0,
            'Native reactor has no exact formed stage or observed natural progress')
    for key in ('recipesDone', 'nativeRecipesDone', 'completionBaseline'):
        require(tag_at(typed_machine, key)[0] == 4 and machine[key] >= 0,
                'Native recipe counter/baseline is negative or has the wrong NBT type')
    require(machine['recipesDone'] == machine['nativeRecipesDone'] - machine['completionBaseline'],
            'Native recipe delta differs from the untouched raw GT counter and construction baseline')
    boundaries = tag_at(typed_machine, 'nativeCompletionBoundaries')
    require(boundaries[0] == 9 and boundaries[1][0] in (0, 10)
            and (boundaries[1][0] == 10 or not boundaries[1][1]), 'Native completion boundaries are not a typed compound list')
    require(tag_at(typed_line, 'worldTick')[0] == tag_at(typed_line, 'suppliedTick')[0] == 4
            and 0 <= line['suppliedTick'] < line['worldTick'], 'Native completion lacks forward supplied/observed world ticks')
    previous_tick = -1
    fluid = ('sulfurdioxide', 'sulfurtrioxide', 'sulfuricacid')[stage]
    for boundary in boundaries[1][1]:
        require(boundary[0] == 10, 'Native completion boundary has the wrong compound type')
        samples = [tag_at(boundary, key) for key in ('before', 'after')]
        before, after = samples
        for sample in samples:
            require(sample[0] == 10 and tag_at(sample, 'stage') == (3, stage)
                    and tag_at(sample, 'phase') == (8, 'ServerTick.END') and tag_at(sample, 'formed') == (1, 1),
                    'Native completion sample belongs to another stage or is not an actual formed END observation')
            require(all(tag_at(sample, key)[0] == 4 for key in ('worldTick', 'totalRunTime', 'lastWorkingTick', 'recipesDone'))
                    and all(tag_at(sample, key)[0] == 3 for key in ('progress', 'maxProgress', 'efficiency', 'eut'))
                    and tag_at(sample, 'allowedToWork') in ((1, 0), (1, 1))
                    and tag_at(sample, 'nativeRecipeResult')[0] == 8,
                    'Native completion sample erased or mistyped real native timing/recipe/pending state')
            pending = tag_at(sample, 'pendingOutputs')
            require(pending[0] == 9 and pending[1][0] in (0, 10)
                    and (pending[1][0] == 10 or not pending[1][1]), 'Native pending outputs have the wrong compound list type')
            for output in pending[1][1]:
                amount = tag_at(output, 'Amount')
                require(output[0] == 10 and tag_at(output, 'FluidName') == (8, fluid)
                        and amount[0] == 3 and 0 < amount[1] <= 64000 and amount[1] % 1000 == 0,
                        'Native completion pending output has the wrong reaction, amount, type or finite stoichiometric unit')
        value = lambda sample, key: tag_at(sample, key)[1]
        require(line['suppliedTick'] <= value(before, 'worldTick') < value(after, 'worldTick') <= line['worldTick']
                and value(after, 'worldTick') == value(before, 'worldTick') + 1
                and value(before, 'worldTick') > previous_tick,
                'Native completion boundary skips/reverses a natural world tick, predates supply or repeats an old observation')
        require(value(before, 'totalRunTime') >= 0 and value(after, 'totalRunTime') == value(before, 'totalRunTime') + 1
                and value(after, 'lastWorkingTick') > value(before, 'lastWorkingTick')
                and value(after, 'lastWorkingTick') == value(after, 'totalRunTime'),
                'Native completion does not advance the real GT working clock in its own runtime domain')
        require(value(before, 'maxProgress') > 1 and value(before, 'progress') == value(before, 'maxProgress') - 1
                and value(after, 'progress') in (0, 1) and value(after, 'maxProgress') >= value(after, 'progress')
                and 0 < value(after, 'efficiency') <= 10000
                and machine['observedProgress'] >= value(before, 'progress')
                and tag_at(before, 'pendingOutputs')[1][1],
                'Native completion is not the exact live final-progress boundary with pending native chemical output')
        require(machine['completionBaseline'] <= value(before, 'recipesDone') <= value(after, 'recipesDone') <= machine['nativeRecipesDone'],
                'Native completion observer changed the raw GT counter or observed a future/counter-reset state')
        previous_tick = value(after, 'worldTick')
    require(machine['recipesDone'] > 0 or bool(boundaries[1][1]),
            'Native reactor has neither a real recipe counter increase nor a fully verified natural completion boundary')


def offline_uuid(name):
    return str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode('utf-8')).digest(), version=3))


def world_files(phase):
    world = phase['fixture'] / 'server' / phase['environment']['world']
    require(world.is_dir() and not world.is_symlink() and world.resolve().parent == phase['fixture'] / 'server',
            'Actual world is missing or escaped the isolated server')
    files = []
    for path in sorted(world.rglob('*')):
        require(not path.is_symlink() and not getattr(path.lstat(), 'st_file_attributes', 0)
                & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0), 'Linked entry inside world archive: ' + str(path))
        require(path.resolve().is_relative_to(world.resolve()), 'World archive entry escaped its world')
        if path.is_file():
            files.append(path)
    require(files and len(files) <= 10000 and sum(path.stat().st_size for path in files) <= 512 * 1024 * 1024,
            'Unexpected empty or oversized fixture world')
    level = safe_file(world / 'level.dat', world)
    recent(level, [phase['runs'][0]])
    require(read_nbt(level)['Data']['LevelName'] == phase['environment']['world'], 'Saved world identity differs from actual run')
    own_name = phase['environment']['offline_fixture_username']
    require(re.fullmatch(r'[A-Za-z0-9_]{1,16}', own_name) and offline_uuid(own_name) == phase['actor'],
            'Saved world player/auxiliary inventory name is not this fixture offline actor')
    baubles = {own_name + '.baub', own_name + '.baubback'}
    for player in (world / 'playerdata').glob('*'):
        if player.is_file():
            require(player.name in {phase['actor'] + '.dat', phase['actor'] + '.dat_old'} | baubles,
                    'World archive contains another player profile or unfinished player write')
            if player.name in baubles:
                require('Baubles-Expanded-2.2.6-GTNH-dev.jar' in phase['environment']['roles']['server']['support_jars']
                        and player.stat().st_size == 45
                        and read_nbt(player, typed=True) == (10, {'Baubles.Inventory': (9, (0, []))}),
                        'Saved fixture Baubles file is not the registered native empty inventory for this exact actor')
    return world, files


def saved_chunk(phase, world, x, z, *, typed=False):
    cx, cz = x >> 4, z >> 4
    region = safe_file(world / 'region' / f'r.{cx >> 5}.{cz >> 5}.mca', world)
    recent(region, [phase['runs'][0]])
    with region.open('rb') as stream:
        stream.seek(4 * ((cx & 31) + (cz & 31) * 32))
        entry = stream.read(4)
        require(len(entry) == 4, 'Missing actual region location')
        offset, sectors = int.from_bytes(entry[:3], 'big'), entry[3]
        require(offset >= 2 and sectors > 0, 'Native fixture chunk was never saved')
        stream.seek(offset * 4096)
        length = int.from_bytes(stream.read(4), 'big')
        compression = stream.read(1)
        require(0 < length <= sectors * 4096 - 4 and length <= LIMIT, 'Invalid native region chunk length')
        payload = stream.read(length - 1)
        require(len(payload) == length - 1, 'Truncated saved native chunk')
    if compression == b'\x01':
        data = nbt_bytes(payload, typed=typed)
    elif compression == b'\x02':
        decoder = zlib.decompressobj()
        expanded = decoder.decompress(payload, LIMIT + 1)
        require(len(expanded) <= LIMIT and decoder.eof and not decoder.unused_data, 'Invalid/oversized compressed native chunk')
        data = nbt_bytes(expanded, typed=typed)
    elif compression == b'\x03':
        data = nbt_bytes(payload, typed=typed)
    else:
        raise ValueError('Unsupported native region compression')
    level = data[1]['Level'] if typed else data['Level']
    coordinates = (level[1]['xPos'], level[1]['zPos']) if typed else (level['xPos'], level['zPos'])
    expected_coordinates = ((3, cx), (3, cz)) if typed else (cx, cz)
    require(coordinates == expected_coordinates, 'Saved native chunk coordinate differs')
    return level


def saved_tile(phase, world, x, y, z, *, typed=False):
    level = saved_chunk(phase, world, x, z, typed=typed)
    if typed:
        tiles = level[1]['TileEntities']
        require(tiles[0] == 9 and tiles[1][0] == 10, 'Actual native tile list has a wrong NBT type')
        matches = [tile for tile in tiles[1][1]
            if tuple(tile[1].get(axis) for axis in ('x', 'y', 'z')) == ((3, x), (3, y), (3, z))]
    else:
        matches = [tile for tile in level.get('TileEntities', []) if (tile.get('x'), tile.get('y'), tile.get('z')) == (x, y, z)]
    require(len(matches) == 1, 'Actual saved native tile is missing/duplicated at ' + str((x, y, z)))
    return matches[0]


def saved_block(phase, world, x, y, z):
    level = saved_chunk(phase, world, x, z)
    sections = [section for section in level.get('Sections', []) if section.get('Y') == y >> 4]
    require(len(sections) <= 1, 'Duplicate actual native chunk section')
    if not sections:
        return dict(block_id=0, metadata=0)
    section = sections[0]
    require(len(section.get('Blocks', [])) == 4096 and len(section.get('Data', [])) == 2048,
            'Unknown native saved chunk block encoding')
    index = (y & 15) << 8 | (z & 15) << 4 | (x & 15)
    nibble = lambda array: (array[index >> 1] >> (4 * (index & 1))) & 15
    block_id = section['Blocks'][index]
    if 'Add' in section:
        require(len(section['Add']) == 2048, 'Unknown extended native block encoding')
        block_id |= nibble(section['Add']) << 8
    return dict(block_id=block_id, metadata=nibble(section['Data']))


def ae_save_confirmation(proof, snapshot, pair, kind):
    evidence = tag_at(proof, 'actualAeSaveReview')
    report, ui = tag_at(evidence, 'actualReport'), tag_at(evidence, 'actualUi')
    require(evidence[0] == report[0] == ui[0] == 10
            and tag_at(report, 'session') == tag_at(proof, 'session') == (8, snapshot['session'])
            and tag_at(report, 'planRevision') == tag_at(proof, 'planRevision') == (4, snapshot['planRevision'])
            and tag_at(ui, 'session') == tag_at(report, 'session')
            and tag_at(ui, 'planRevision') == tag_at(report, 'planRevision'),
            'Actual AE save review does not bind the authoritative owner session/revision and rendered UI')
    require(kind in ('new', 'overwrite')
            and tag_at(report, 'operation') == (8, 'exportDraft' if kind == 'new' else 'saveBlueprintReplace')
            and tag_at(report, 'update') == (1, int(kind == 'overwrite'))
            and tag_at(report, 'name') == (8, pair['model']['name'])
            and tag_at(report, 'canConfirm') == (1, 1) and tag_at(report, 'blockerCount') == (3, 0),
            'Actual AE report blocks confirmation or describes a different named new/overwrite draft')
    transaction = tag_at(report, 'transactionId')
    require(transaction[0] == 8 and str(uuid.UUID(transaction[1])) == transaction[1],
            'Actual AE review has no canonical server-issued transaction nonce')
    issues = tag_at(report, 'issues')
    require(issues[0] == 9 and issues[1][0] in (0, 10) and (issues[1][0] == 10 or not issues[1][1])
            and len(issues[1][1]) <= 8192, 'Actual AE report lost its typed complete issue list')
    warnings = 0
    for issue in issues[1][1]:
        severity, code, count = (tag_at(issue, key) for key in ('severity', 'code', 'count'))
        require(issue[0] == 10 and severity in ((8, 'info'), (8, 'warning'))
                and code[0] == 8 and re.fullmatch(r'[A-Za-z0-9_.-]{1,96}', code[1])
                and count[0] == 4 and count[1] > 0,
                'Actual AE report contains a hidden blocker or malformed native issue')
        warnings += severity == (8, 'warning')
    require(tag_at(report, 'issueCount') == (3, len(issues[1][1]))
            and tag_at(report, 'warningCount') == (3, warnings),
            'Actual AE report issue/warning counts do not match its full original issue list')
    controls = tag_at(ui, 'drawnControls')
    require(controls[0] == 9 and controls[1][0] == 10 and tag_at(ui, 'aeReview') == (1, 1),
            'AE confirmation did not originate in the actual displayed review modal')
    buttons = [control for control in controls[1][1] if tag_at(control, 'id') == (3, 181)]
    require(len(buttons) == 1 and tag_at(buttons[0], 'enabled') == (1, 1),
            'Actual AE review has no unique visible enabled native 181 confirmation control')
    fields = ('sequenceBefore', 'confirmationSequence', 'confirmationAckSequence', 'nativeMouseEventsBefore', 'nativeMouseEventsAfter')
    require(all(tag_at(evidence, key)[0] == 4 and tag_at(evidence, key)[1] >= 0 for key in fields)
            and tag_at(evidence, 'reviewClosedByNativeClick') == (1, 1),
            'Actual AE confirmation erased typed native input/sequence evidence or did not close by native click')
    value = lambda key: tag_at(evidence, key)[1]
    require(value('confirmationSequence') == value('sequenceBefore') + 1
            and value('nativeMouseEventsAfter') == value('nativeMouseEventsBefore') + 1
            and tag_at(ui, 'uiSequence')[0] == 4 and 0 <= tag_at(ui, 'uiSequence')[1] <= value('sequenceBefore')
            and snapshot['uiSequence'] >= value('confirmationAckSequence') >= value('confirmationSequence'),
            'Actual native AE confirmation did not dispatch one new sequence/click followed by the clean owner ACK')
    for key in ('previewVerifiedReply', 'ownerDiskAck', 'saveCommittedReply'):
        packet = tag_at(proof, key)
        require(tag_at(packet, 'session') == tag_at(report, 'session')
                and tag_at(packet, 'planRevision') == tag_at(report, 'planRevision'),
                'Actual pair verification/disk owner ACK/server commit is detached from the confirmed AE draft')
    return transaction[1]


def native_author_saves(source, frames, author, exported, exported_png):
    typed_author = read_nbt(source['receipts'] / 'sulfur-author-final.nbt', typed=True)
    proofs = tag_at(typed_author, 'actualSaveProofs')
    require(proofs[0] == 9 and proofs[1][0] == 10 and len(proofs[1][1]) == 2,
            'Author must retain exactly the real new and overwrite native save transactions')
    nonces, generations, review_nonces = set(), [], set()
    for index, (kind, label) in enumerate((('new', 'sulfur-source-save-new-frame'), ('overwrite', 'sulfur-source-save-frame'))):
        proof = proofs[1][1][index]
        require(tag_at(proof, 'saveKind') == (8, kind) and tag_at(proof, 'savedJson')[0] == 7
                and tag_at(proof, 'savedPng')[0] == 7, 'Actual author save kind or raw pair byte-array tags differ')
        json_bytes, png_bytes = bytes(tag_at(proof, 'savedJson')[1]), bytes(tag_at(proof, 'savedPng')[1])
        pair = verify_blueprint_pair(json_bytes, png_bytes)
        require(tag_at(proof, 'savedJsonSha256') == (8, pair['jsonSha256'])
                and tag_at(proof, 'savedPngSha256') == (8, pair['pngSha256']), 'Actual author transaction bytes differ from sealed pair hashes')
        snapshot = frames[label]['actualSnapshot']
        require(snapshot.get('blueprintSaved') and snapshot.get('blueprintOperationSuccess')
                and not snapshot.get('blueprintDirty') and not snapshot.get('blueprintSavePending')
                and snapshot['session'] == author['session'] and snapshot['libraryLocalId'] == author['localId']
                and tag_at(proof, 'localId') == (8, author['localId'])
                and tag_at(proof, 'session') == (8, snapshot['session'])
                and tag_at(proof, 'planRevision') == (4, snapshot['planRevision']),
                'Native author save transaction differs from its actual clean END owner ACK snapshot')
        wrapper = (10, {'actualSaveWitness': proof, 'actualNativePreviewWitness': tag_at(proof, 'nativePreviewWitness')})
        nonce, generation = verify_save_transaction(wrapper, pair, author['localId'], snapshot['session'], snapshot['planRevision'])
        review_nonce = ae_save_confirmation(proof, snapshot, pair, kind)
        require(review_nonce not in review_nonces, 'New/overwrite reused a prior AE review transaction nonce')
        review_nonces.add(review_nonce)
        require(nonce not in nonces and (not generations or generation > generations[-1]),
                'New/overwrite saves reused a server nonce or native END capture')
        nonces.add(nonce)
        generations.append(generation)
        if index == 0:
            require(author['newSaveJsonSha256'] == pair['jsonSha256'] and author['newSavePngSha256'] == pair['pngSha256'],
                    'Original new save hashes differ from its original raw native pair')
        else:
            require(json_bytes == exported and png_bytes == exported_png,
                    'Final overwrite native transaction differs from exact exported pair bytes')
    return dict(save_receipts=sorted(nonces), capture_generations=generations, ae_review_transactions=sorted(review_nonces))


def archive_world(world, files, target):
    entries = {}
    with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files:
            safe_file(path, world)
            relative = world.name + '/' + path.relative_to(world).as_posix()
            checksum = sha(path)
            archive.write(path, relative)
            require(sha(path) == checksum, 'Actual stopped world changed while archiving')
            entries[relative] = dict(bytes=path.stat().st_size, sha256=checksum)
    return dict(bytes=target.stat().st_size, sha256=sha(target), entries=entries,
                scope='Generated isolated fixture world only, including its explicit offline test player; no launcher account or external instance')


