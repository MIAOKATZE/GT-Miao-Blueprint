"""Read-only, typed checks for the real workbench NC payment and keyboard receipts."""
import copy
import base64
import hashlib
import io
import json
import math
from pathlib import Path
import re
import uuid
import struct
import zlib

from nbt_reader import read_nbt, read_nbt_bytes


def verify_blueprint_pair(json_bytes, png_bytes, *, expected_pack='unknown'):
    """Independently verify file format and RGB bytes; this never proves native rendering."""
    from PIL import Image
    check(0 < len(json_bytes) <= 8 * 1024 * 1024 and 0 < len(png_bytes) <= 4 * 1024 * 1024,
          'native blueprint pair exceeds bounded JSON/PNG lengths')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            check(key not in result, 'duplicate native JSON key: ' + key)
            result[key] = value
        return result
    def invalid_number(value):
        raise ValueError('nonfinite native JSON number: ' + value)
    document = json.loads(json_bytes.decode('utf-8'), object_pairs_hook=unique, parse_constant=invalid_number)
    check(isinstance(document, dict), 'native JSON is not an object')
    model = document.get('blueprint', document)
    check(isinstance(model, dict), 'native JSON blueprint is not an object')
    metadata = model.get('nativeBlueprint')
    expected = dict(formatVersion=1, gtmbVersion='0.1.1', gtnhVersion=expected_pack,
                    gtnhVersionSource='unavailable' if expected_pack == 'unknown' else 'dreamcoremod.displayedModpackVersion')
    check(isinstance(metadata, dict) and metadata == expected and type(metadata.get('formatVersion')) is int,
          'native blueprint version metadata or runtime provenance differs')
    check(not any(key in owner for owner in (document, model) for key in ('previewPng', 'png', 'exportedPng', 'previewBase64', 'previewPngBase64', 'pngBase64')),
          'standalone native JSON embeds a preview image')
    json_sha = hashlib.sha256(json_bytes).hexdigest()
    check(png_bytes[:8] == b'\x89PNG\r\n\x1a\n', 'native preview is not PNG')
    offset, source, header, compressed, ended, idat_started, idat_finished = 8, None, None, bytearray(), False, False, False
    while offset < len(png_bytes):
        check(offset + 12 <= len(png_bytes), 'truncated native PNG chunk')
        length = struct.unpack_from('>I', png_bytes, offset)[0]
        check(length <= 4 * 1024 * 1024 and offset + 12 + length <= len(png_bytes), 'native PNG chunk length exceeds bounds')
        kind = png_bytes[offset + 4:offset + 8]
        payload = png_bytes[offset + 8:offset + 8 + length]
        crc = struct.unpack_from('>I', png_bytes, offset + 8 + length)[0]
        check(zlib.crc32(kind + payload) & 0xffffffff == crc, 'native PNG chunk CRC differs')
        check(re.fullmatch(b'[A-Za-z]{4}', kind) and kind[2:3].isupper(), 'invalid native PNG chunk type')
        if header is None:
            check(kind == b'IHDR' and length == 13, 'native PNG does not begin with unique IHDR')
            header = struct.unpack('>IIBBBBB', payload)
            check(header[:2] == (640, 640) and header[2] == 8 and header[3] in (2, 6)
                  and header[4:] == (0, 0, 0), 'native PNG must be noninterlaced 640-square RGB/RGBA8')
        elif kind == b'IHDR':
            check(False, 'duplicate native PNG IHDR')
        elif kind == b'IDAT':
            check(not idat_finished and not ended, 'native PNG IDAT chunks are not contiguous')
            compressed.extend(payload)
            idat_started = True
        else:
            if idat_started:
                idat_finished = True
            if kind == b'tEXt':
                check(b'\0' in payload, 'native PNG text lacks keyword separator')
                keyword, value = payload.split(b'\0', 1)
                if keyword == b'gtmb.sourceJsonSha256':
                    check(source is None and value == json_sha.encode('ascii'), 'native PNG source digest is duplicated or differs from exact JSON bytes')
                    source = value
            elif kind == b'IEND':
                check(length == 0 and idat_started and offset + 12 == len(png_bytes), 'native PNG IEND is missing, malformed or has trailing bytes')
                ended = True
            else:
                check(kind not in (b'zTXt', b'iTXt'), 'native PNG source text must use the single uncompressed tEXt contract')
                check(kind[0:1].islower() or kind == b'PLTE', 'unknown critical native PNG chunk')
        offset += 12 + length
    check(header is not None and ended and source is not None, 'native PNG lacks header, terminal chunk or exact source digest')
    stride = 640 * (3 if header[3] == 2 else 4) + 1
    inflater = zlib.decompressobj()
    decoded = inflater.decompress(bytes(compressed), stride * 640 + 1)
    check(len(decoded) == stride * 640 and inflater.eof and not inflater.unconsumed_tail and not inflater.unused_data,
          'native PNG compressed raster exceeds bounds or is incomplete')
    check(all(decoded[row * stride] <= 4 for row in range(640)), 'native PNG scanline filter is invalid')
    with Image.open(io.BytesIO(png_bytes)) as image:
        image.verify()
    with Image.open(io.BytesIO(png_bytes)) as image:
        image.load()
        check(image.size == (640, 640), 'decoded native preview dimensions differ')
        rgb_sha = hashlib.sha256(image.convert('RGB').tobytes()).hexdigest()
    return dict(model=model, metadata=metadata, jsonSha256=json_sha, pngSha256=hashlib.sha256(png_bytes).hexdigest(),
                rgbSha256=rgb_sha, jsonBytes=len(json_bytes), pngBytes=len(png_bytes))


def verify_native_preview(witness, pair):
    check(at(witness, 'sourceJsonSha256') == (8, pair['jsonSha256'])
          and at(witness, 'rgbSha256') == (8, pair['rgbSha256'])
          and at(witness, 'rgbPacking') == (8, 'RGB8_TOP_LEFT_ROW_MAJOR')
          and at(witness, 'width') == (3, 640) and at(witness, 'height') == (3, 640)
          and all(at(witness, key) == (1, 1) for key in ('renderTickEnd', 'readbackSucceeded', 'nativeRendering'))
          and at(witness, 'captureGeneration')[0] == 4 and at(witness, 'captureGeneration')[1] > 0,
          'actual native END/FBO readback does not bind exact saved JSON and independently decoded RGB pixels')
    return at(witness, 'captureGeneration')[1]


def verify_save_transaction(proof, pair, local_id, session, revision):
    transaction = at(proof, 'actualSaveWitness')
    check('saveRejectedReply' not in transaction[1], 'native pair save was rejected')
    native = at(proof, 'actualNativePreviewWitness')
    check(at(transaction, 'nativePreviewWitness') == native, 'library transaction native capture differs from actual FBO witness')
    generation = verify_native_preview(native, pair)
    ack = at(transaction, 'ownerDiskAck')
    nonce = at(ack, 'saveReceipt')
    check(nonce[0] == 8 and str(uuid.UUID(nonce[1])) == nonce[1], 'actual save nonce is not a canonical UUID')
    for key, op in (('previewVerifiedReply', 'previewVerified'), ('ownerDiskAck', 'ackBlueprintSaved'), ('saveCommittedReply', 'saveCommitted')):
        packet = at(transaction, key)
        check(at(packet, 'op') == (8, op) and at(packet, 'localId') == (8, local_id)
              and at(packet, 'session') == (8, session) and at(packet, 'planRevision') == (4, revision)
              and at(packet, 'saveReceipt') == nonce and at(packet, 'artifactVersion') == (3, 1)
              and at(packet, 'jsonSha256') == (8, pair['jsonSha256']) and at(packet, 'previewSha256') == (8, pair['pngSha256']),
              'actual native verification/disk ACK/server commit differs from owner, session, revision, nonce or exact pair: ' + key)
    return nonce[1], generation


def verify_pair_receipt(proof, typed, receipts, folder, *, expected_stem=None, expected_pack='unknown'):
    local = proof['localId']
    check(re.fullmatch(r'local_[a-fA-F0-9-]{36}', local), 'native pair receipt local filename ID is invalid')
    json_path, png_path = Path(proof['actualPath']).resolve(), Path(proof['actualPngPath']).resolve()
    check(json_path == folder / (local + '.json') and png_path == folder / (local + '.png'),
          'native pair paths are not same-basename files in the isolated client store')
    stem = expected_stem or 'normal-native-blueprint'
    check(proof['nativeJson'] == stem + '.json' and proof['nativePng'] == stem + '.png', 'native pair byte-copy names differ')
    copies = [receipts / proof['nativeJson'], receipts / proof['nativePng']]
    check(all(path.is_file() and not path.is_symlink() for path in copies), 'native pair byte-copy receipt is absent')
    json_bytes, png_bytes = (path.read_bytes() for path in copies)
    pair = verify_blueprint_pair(json_bytes, png_bytes, expected_pack=expected_pack)
    for key, value in [('artifactVersion', 1), ('previewWidth', 640), ('previewHeight', 640),
                       ('actualJsonBytes', len(json_bytes)), ('actualPngBytes', len(png_bytes))]:
        check(at(typed, key) == (3, value), 'native pair receipt has incorrect typed format/dimensions/byte lengths: ' + key)
    check(proof['actualFileSha256'] == proof['sourceJsonSha256'] == pair['jsonSha256']
          and proof['actualPngSha256'] == pair['pngSha256']
          and at(typed, 'actualNativeBlueprint') == (10, {key: (3 if key == 'formatVersion' else 8, value) for key, value in pair['metadata'].items()}),
          'native pair receipt SHA or typed metadata differs from actual bytes')
    snapshot = proof['actualSnapshot']
    check(snapshot.get('blueprintSaved') and snapshot.get('blueprintOperationSuccess') and not snapshot.get('blueprintSavePending')
          and not snapshot.get('blueprintDirty') and snapshot.get('libraryLocalId') == local,
          'native pair lacks successful clean owner ACK snapshot')
    nonce, generation = verify_save_transaction(typed, pair, local, snapshot['session'], snapshot['planRevision'])
    return pair, json_path, png_path, json_bytes, png_bytes, nonce, generation


NC_PACKAGE = 'shedar.mods.ic2.nuclearcontrol.items.'
GT_SENSOR = 'gregtech.common.items.ItemSensorCard'
REACTOR = NC_PACKAGE + 'ItemCardReactorSensorLocation'
ENERGY = NC_PACKAGE + 'ItemCardEnergySensorLocation'
MULTIPLE = NC_PACKAGE + 'ItemCardMultipleSensorLocation'
REGISTRY_CLASSES = {
    'IC2NuclearControl:ItemSensorLocationCard': REACTOR,
    'IC2NuclearControl:ItemEnergySensorLocationCard': ENERGY,
    'IC2NuclearControl:ItemMultipleSensorLocationCard': MULTIPLE,
}
CACHE_TYPES = {
    GT_SENSOR: {'state': 3, 'mString': 3},
    REACTOR: dict.fromkeys(('state', 'heat', 'maxHeat', 'output', 'timeLeft'), 3)
        | dict.fromkeys(('reactorPoweredB', 'isSteam'), 1),
    ENERGY: {'state': 3} | dict.fromkeys(('energyL', 'maxStorageL', 'range_trigger_amount'), 6),
    MULTIPLE: dict.fromkeys(('state', 'capacity', 'amount', 'liquidId', 'powerType', 'average', 'production'), 3)
        | dict.fromkeys(('energy', 'range_trigger_amount'), 6) | {'liquidTag': 10},
}
KEYBOARD_INPUTS = {
    'native-blueprint-name': ('presetNameField', 'Workbench raw source'),
    'name-primary-flow-preset': ('processingPresetField', 'Workbench primary flow'),
    'name-second-flow-preset': ('processingPresetField', 'Workbench secondary flow'),
    'rename-primary-flow-through-keyboard': ('processingPresetField', 'Workbench primary updated'),
    'type-real-global-prompt': ('promptField', 'Workbench actual dirty global prompt'),
    'native-overwrite-name': ('presetNameField', 'Workbench actual overwritten'),
} | {f'name-private-save-as-{i}': ('presetNameField', f'Workbench actual {i}') for i in range(6)}
FRAME_LABELS = '''initial-import-tab raw-blueprint-save-disabled initial-panel-selected-prompt
initial-stone-structure-sidebar view-rotation-server-plan-unchanged native-process-server-ack
selected-cell-nbt-before-refinement selected-cell-nbt-explicit-refinement nbt-refinement-server-ack-save-gated
refined-flow-complete-server-ack processing-preset-save-modal processing-preset-created-server-ack
processing-preset-loaded-unconfirmed-ack processing-preset-updated-server-ack processing-preset-deleted-server-ack
scrolled-private-row-native-preview-ack saved-panel-selected-cell-nbt saved-stone-structure-sidebar
global-prompt-native-keyboard-choice dirty-row-switch-discard-review dirty-row-switch-confirm-review
dirty-switch-confirmed-server-ack overwrite-target-only-current-plan-unchanged overwrite-named-native-review
overwrite-native-replace-disk-ack private-delete-native-review private-delete-actual-file-and-draft-preserved
native-untouched-stage-0-next-disabled native-share-tendency-unconfirmed'''.split() + [
    f'{prefix}-stage-{stage}-{choice}' for prefix in ('native', 'preset') for stage in range(6)
    for choice in ('unconfirmed', 'explicit-choice')] + [
    f'refined-stage-{stage}-{choice}' for stage in (4, 5) for choice in ('unconfirmed', 'explicit-choice')] + [
    f'private-save-as-{i}-actual-disk-ack' for i in range(6)] + [
    f'{prefix}-{suffix}' for prefix in ('saved-edit-review', 'overwrite-edit-review')
    for suffix in ('saved-import-save-disabled', 'stage-3-clear-target-unknown', 'six-stage-server-ack')]


def check(condition, message):
    if not condition:
        raise ValueError('Workbench ' + message)


def plain(node):
    kind, payload = node
    if kind == 10:
        return {key: plain(value) for key, value in payload.items()}
    if kind == 9:
        return [plain(value) for value in payload[1]]
    return payload


def at(node, *path):
    for key in path:
        node = node[1][key] if node[0] == 10 else node[1][1][key]
    return node


def rows(node, slot_key, slot_type, slots):
    check(node[0] == 9 and node[1][0] == 10, 'inventory is not a native compound list')
    result = {}
    for stack in node[1][1]:
        slot = at(stack, slot_key)
        check(slot[0] == slot_type and slot[1] not in result, 'inventory has a wrong typed or duplicate slot')
        check(at(stack, 'Count') == (1, 1) and at(stack, 'id')[0] == 2
            and at(stack, 'Damage')[0] == 2, 'native item identity, damage or count type differs')
        result[slot[1]] = stack
    check(set(result) == set(slots), 'inventory slots differ from the complete fixture inventory')
    return result


def sensor_projection(stack, sensor_class, *, payment=False, mode=None):
    """Only exact registered classes and correct tag types lose audited observations."""
    result = copy.deepcopy(stack)
    tag = result[1].get('tag')
    if sensor_class not in CACHE_TYPES or tag is None or tag[0] != 10:
        return result
    changed = False
    for key, value in list(tag[1].items()):
        expected = CACHE_TYPES[sensor_class].get(key)
        if sensor_class == GT_SENSOR and re.fullmatch(r'mString[0-9]+', key):
            expected = 8
        if payment and mode in ('relative', 'clear') and key in ('x', 'y', 'z'):
            expected = 3
        if expected is not None and value[0] == expected:
            del tag[1][key]
            changed = True
    if payment:
        tag[1].pop('gtmbCoordinatePayment', None)
    if (changed or payment) and not tag[1]:
        del result[1]['tag']
    return result


def panel_projection(panel, classes):
    result = copy.deepcopy(panel)
    check(at(result, 'id') in ((8, 'IC2NCInfoPanel'), (8, 'IC2NCAdvancedInfoPanel')),
          'runtime comparison received an unregistered panel')
    items = at(result, 'Items')
    check(items[0] == 9 and (items[1][0] == 10 or items[1] == (0, [])), 'panel Items has a wrong native type')
    for i, stack in enumerate(items[1][1]):
        sensor_class = classes.get(at(stack, 'id'))
        items[1][1][i] = sensor_projection(stack, sensor_class)
    return result


def payment_stack(stack, sensor_class, mode):
    result = sensor_projection(stack, sensor_class, payment=True, mode=mode)
    for key in ('Slot', 'fixtureSlot', 'gtitBlueprintItemRegistry'):
        result[1].pop(key, None)
    return result


def validate_nc_payment(path):
    root = read_nbt(path, typed=True)
    data = plain(root)
    check(at(root, 'passed') == (1, 1) and 'failure' not in data, 'NC payment receipt failed')
    check(at(root, 'sourceBefore') == at(root, 'sourceAfter'), 'source panel or full source inventory changed')
    check(at(root, 'sourcePosition') == (11, [50, 80, 44]), 'native source position differs')
    source = at(root, 'sourceBefore')
    source_cards = rows(at(source, 'Items'), 'Slot', 1, range(3))
    before = rows(at(root, 'inventoryBefore'), 'fixtureSlot', 3, range(6))
    remaining = rows(at(root, 'inventoryAfter'), 'fixtureSlot', 3, (0, 5))
    check(remaining == {slot: before[slot] for slot in (0, 5)}, 'held projector or future stone changed during payment')
    check(at(before[5], 'id') == (2, 1) and at(before[5], 'Damage') == (2, 0)
        and 'tag' not in before[5][1], 'future stone is not the exact untouched native stack')
    check(at(before[0], 'tag', 'gtitHologramToolId')[0] == 8, 'held projector lacks its actual tool UUID')
    uuid.UUID(at(before[0], 'tag', 'gtitHologramToolId')[1])
    initial = rows(at(root, 'initialInventory'), 'fixtureSlot', 3, (0,))
    held = copy.deepcopy(before[0])
    held[1]['tag'][1].pop('gtitHologramToolId')
    if not held[1]['tag'][1]:
        held[1].pop('tag')
    check(held == initial[0], 'held projector changed beyond its assigned native tool UUID')
    check(at(before[4], 'Damage') == (2, 9) and 'tag' not in before[4][1], 'payment panel shell variant differs')
    check(len({at(before[slot], 'id') for slot in range(6)}) == 6, 'payment shell/cards/future/held identities overlap')
    check(at(root, 'rollbackBefore') == at(root, 'inventoryBefore') == at(root, 'rollbackAfter'),
          'cancelled payment did not restore the exact typed original inventory')
    check(at(root, 'differentConfigurationBefore') == at(root, 'differentConfigurationAfter')
        and at(root, 'differentConfigurationOperateResult') == (1, 0), 'unknown payment configuration was charged')

    stages_list = at(root, 'stages')[1][1]
    names = ['before-different-configuration', 'after-different-configuration'] + [
        f'existing-{kind}-{phase}-operation' for kind in
        ('empty', 'wrong-coordinate', 'wrong-opaque', 'wrong-static-config', 'correct') for phase in ('before', 'after')
    ] + ['before-place-event-veto', 'after-place-event-veto', 'before-successful-placement', 'after-successful-placement']
    check([plain(at(stage, 'stage')) for stage in stages_list] == names, 'NC stages are missing, duplicated or out of order')
    stages = dict(zip(names, stages_list))
    planned = at(stages_list[0], 'plannedTile')
    planned_cards = rows(at(planned, 'Items'), 'Slot', 1, range(3))
    classes = {}
    for slot, sensor_class in enumerate((REACTOR, ENERGY, MULTIPLE)):
        card = planned_cards[slot]
        registry = at(card, 'gtitBlueprintItemRegistry')
        check(registry[0] == 8 and REGISTRY_CLASSES.get(registry[1]) == sensor_class,
              'planned card is not the exact registered fixture sensor class')
        classes[at(card, 'id')] = sensor_class
        check(at(source_cards[slot], 'id') == at(before[slot + 1], 'id') == at(card, 'id'), 'card identity changed')
        mode = 'relative' if slot == 0 else 'clear'
        check(at(card, 'tag', 'gtmbCoordinatePayment') == (8, mode), 'payment mode marker differs from audited relation')
        check(payment_stack(card, sensor_class, mode) == payment_stack(before[slot + 1], sensor_class, mode)
            == payment_stack(source_cards[slot], sensor_class, mode), 'payment changed exact non-coordinate card data')
    candidate = copy.deepcopy(before[2])
    candidate[1].pop('fixtureSlot')
    check(candidate == at(root, 'reviewedRuntimeCandidate'), 'accepted runtime candidate differs from the paid actual card')
    expected_candidate = copy.deepcopy(source_cards[1])
    expected_candidate[1].pop('Slot')
    expected_candidate[1]['tag'][1].update({'x': (3, 751), 'energyL': (6, 52.0)})
    check(candidate == expected_candidate, 'clear payment did not exercise the exact changed old coordinate and typed energy cache')
    relations = at(planned, 'gtmbCoordinateRelations')[1][1]
    check(len(relations) == 3, 'planned coordinate descriptors do not cover three cards')
    for slot, relation in enumerate(relations):
        check(at(relation, 'slot') == (3, slot) and at(relation, 'adapter') == (8, 'nc.sensor')
            and at(relation, 'mode') == (8, 'relative' if slot == 0 else 'clear')
            and [at(relation, key) for key in ('dx', 'dy', 'dz')] == [(3, 0), (3, 0), (3, 1 if slot == 0 else 0)]
            and all(at(relation, key) == (3, 0) for key in ('targetX', 'targetY', 'targetZ', 'targetDimension')),
            'mapped/cleared relation retains a wrong offset, dimension or original target')
    for stage in stages_list:
        check(at(stage, 'plannedBlock') == (8, 'IC2NuclearControl:blockNuclearControlMain')
            and at(stage, 'plannedMetadata') == (3, 9) and at(stage, 'dimension') == (3, 0)
            and at(stage, 'targetPosition') == (11, [82, 80, 44]) and at(stage, 'plannedTile') == planned,
            'NC stage changed its native scene, planned payload or mapped target')
        check(at(stage, 'actor') == at(stages_list[0], 'actor') and at(stage, 'world') == at(stages_list[0], 'world')
            and plain(at(stage, 'world')).startswith('hologram-followup-'), 'NC stage used another native actor/world')

    expected = at(root, 'expectedInstalledTile')
    derived = copy.deepcopy(source)
    derived[1].update({key: (3, value) for key, value in zip(('x', 'y', 'z'), (82, 80, 44))})
    derived_cards = rows(at(derived, 'Items'), 'Slot', 1, range(3))
    derived_cards[0][1]['tag'][1].update({key: (3, value) for key, value in zip(('x', 'y', 'z'), (82, 80, 45))})
    for slot in (1, 2):
        derived_cards[slot][1].pop('tag', None)
    check(expected == derived, 'expected installed panel changes static data, opaque assets or mapped card coordinates')
    placed = at(root, 'placed')
    check(panel_projection(placed, classes) == expected, 'placed NC panel differs beyond audited typed runtime caches')
    placed_cards = rows(at(placed, 'Items'), 'Slot', 1, range(3))
    check([at(placed_cards[slot], 'tag', 'state') for slot in range(3)] == [(3, 2), (3, 3), (3, 3)],
          'native relative/clear cards did not record their exact typed current states')
    event_cards = rows(at(root, 'eventNativeCards'), 'Slot', 1, range(3))
    expected_cards = rows(at(expected, 'Items'), 'Slot', 1, range(3))
    check({slot: sensor_projection(stack, classes.get(at(stack, 'id'))) for slot, stack in event_cards.items()}
        == expected_cards, 'observed complete event changes mapped coordinates, configuration or card assets')
    for kind in ('empty', 'wrong-coordinate', 'wrong-opaque', 'wrong-static-config', 'correct'):
        first, last = (stages[f'existing-{kind}-{phase}-operation'] for phase in ('before', 'after'))
        check(at(first, 'inventory') == at(last, 'inventory') == at(root, 'inventoryBefore')
            and at(first, 'actualTile') == at(last, 'actualTile'), 'existing ' + kind + ' target or inventory was mutated')
        check(at(first, 'cellStatus') == (8, 'satisfied' if kind == 'correct' else 'blocked')
            and at(last, 'cellStatus') == (8, 'satisfied' if kind == 'correct' else 'protected'),
            'existing ' + kind + ' target classification differs')
        for stage in (first, last):
            check(at(stage, 'actualBlock') == (8, 'IC2NuclearControl:blockNuclearControlMain')
                and at(stage, 'actualMetadata') == (3, 9)
                and at(stage, 'actualTileClass') == (8, 'shedar.mods.ic2.nuclearcontrol.tileentities.TileEntityAdvancedInfoPanel'),
                'existing target is not the native advanced panel')
        projected = panel_projection(at(first, 'actualTile'), classes)
        wrong = copy.deepcopy(expected)
        if kind == 'empty':
            wrong[1]['Items'] = (9, (0, []))
        elif kind == 'wrong-coordinate':
            at(wrong, 'Items', 0, 'tag')[1]['x'] = (3, 89)
        elif kind == 'wrong-opaque':
            at(wrong, 'Items', 0, 'tag')[1]['followupUnexpectedNativeConfiguration'] = (8, 'preserve-exactly')
        elif kind == 'wrong-static-config':
            wrong[1]['showLabels'] = (1, 0)
        check(projected == wrong, 'existing ' + kind + ' target does not carry the exact intended comparison payload')

    rejected = stages['after-different-configuration']
    check(at(rejected, 'cellStatus') == (8, 'missing') and plain(at(rejected, 'quoteError'))
        and at(rejected, 'inventory') == at(root, 'differentConfigurationAfter'), 'wrong payment payload rejection is missing')
    bad = copy.deepcopy(at(root, 'inventoryBefore'))
    at(bad, 2, 'tag')[1]['followupUnexpectedConfiguration'] = (8, 'must-match-exactly')
    check(bad == at(root, 'differentConfigurationBefore'), 'rejected payment did not contain the exact opaque mismatch')
    for name in ('before-different-configuration', 'after-different-configuration', 'before-place-event-veto',
                 'after-place-event-veto', 'before-successful-placement'):
        stage = stages[name]
        check(at(stage, 'actualBlock') == (8, 'minecraft:air') and at(stage, 'actualTileClass') == (8, 'null')
            and 'actualTile' not in stage[1], 'failed/cancelled operation left a native target')
    veto = stages['after-place-event-veto']
    check(at(veto, 'inventory') == at(root, 'rollbackAfter') and at(veto, 'cellStatus') == (8, 'protected')
        and at(root, 'vetoOperateResult') == (1, 1), 'PlaceEvent rollback/status proof differs')
    check([at(veto, key) for key in ('events', 'earlyShellEvents', 'completeEvents', 'cancelledEvents', 'unexpectedEvents')]
        == [(3, 2), (3, 1), (3, 1), (3, 1), (3, 0)], 'native shell and complete event phases differ')
    events = at(veto, 'placementEvents')[1][1]
    check(len(events) == 2, 'PlaceEvent receipt count differs')
    for i, event in enumerate(events):
        check(at(event, 'phase') == (8, ('early-native-empty-shell', 'complete-service-target')[i])
            and at(event, 'cancelled') == (1, i) and at(event, 'complete') == (1, i)
            and at(event, 'factoryMatches') == (1, 1) and at(event, 'expectedTile') == expected
            and at(event, 'inventoryDuring') == at(root, 'inventoryAfter')
            and at(event, 'block') == (8, 'IC2NuclearControl:blockNuclearControlMain')
            and at(event, 'metadata') == (3, 9)
            and at(event, 'nativeTileClass') == (8, 'shedar.mods.ic2.nuclearcontrol.tileentities.TileEntityAdvancedInfoPanel'),
            'actual native PlaceEvent factory, payment or cancellation differs')
        if i == 0:
            shell = copy.deepcopy(expected)
            shell[1]['Items'] = (9, (0, []))
            shell[1]['facing'] = (2, 2)
            check(at(event, 'nativeTile') == shell, 'early event was not the exact native empty shell')
        else:
            check(panel_projection(at(event, 'nativeTile'), classes) == expected,
                  'complete cancelled event did not contain the exact mapped native cards and static configuration')
    final = stages['after-successful-placement']
    check(at(final, 'actualTile') == placed and at(final, 'inventory') == at(root, 'inventoryAfter')
        and at(final, 'cellStatus') == (8, 'placed'), 'successful placement did not consume only the shell and three cards')


def validate_warm_source(ready, payment):
    check(at(ready, 'passed') == at(payment, 'passed') == (1, 1), 'native payment/warmup source proof failed')
    check(at(ready, 'nativeWarmupTicks') == (3, 40), 'native source baseline predates its actual initialization ticks')
    initialized = copy.deepcopy(at(payment, 'sourceAfter'))
    cards = rows(at(initialized, 'Items'), 'Slot', 1, range(3))
    for slot in range(3):
        check('state' not in at(cards[slot], 'tag')[1], 'prewarm payment source unexpectedly already has native state')
        at(cards[slot], 'tag')[1]['state'] = (3, 2 if slot == 0 else 3)
    check('screenData' not in initialized[1], 'prewarm payment source unexpectedly already has screen geometry')
    initialized[1]['screenData'] = (10, {corner + axis: (3, value)
        for corner in ('min', 'max') for axis, value in zip(('X', 'Y', 'Z'), (50, 80, 44))})
    check(initialized == at(ready, 'sourceTile'), 'native warmup changed data beyond exact state and screen initialization')
    native_cards = rows(at(ready, 'sourceTile', 'Items'), 'Slot', 1, range(3))
    independent = rows(at(ready, 'sourceCards'), 'slot', 3, range(3))
    for slot in range(3):
        native, original = copy.deepcopy(native_cards[slot]), copy.deepcopy(independent[slot])
        native[1].pop('Slot')
        original[1].pop('slot')
        check(native == original, 'independent warm source inventory and full tile serialization disagree')


def validate_workbench_source(receipts):
    ready = read_nbt(receipts / 'workbench-server-ready.nbt', typed=True)
    final = read_nbt(receipts / 'workbench-server-final.nbt', typed=True)
    payment = read_nbt(receipts / 'nc-payment-world.nbt', typed=True)
    validate_warm_source(ready, payment)
    check(at(final, 'passed') == (1, 1) and at(final, 'sourceUnchanged') == (1, 1), 'native server source proof failed')
    check(at(ready, 'sourceTile') == at(final, 'sourceTileBefore') == at(final, 'sourceTileAfter'),
          'full native source changed during real GUI run')
    check(at(ready, 'sourceCards') == at(final, 'sourceCardsBefore') == at(final, 'sourceCardsAfter'),
          'full typed native source card inventory changed during real GUI run')


def native_snbt(node):
    """Render the audited 1.7.10 NBT view without dropping keys or tag suffixes."""
    kind, value = node
    if kind == 10:
        return '{' + ','.join(key + ':' + native_snbt(item) for key, item in value.items()) + '}'
    if kind == 9:
        return '[' + ','.join(str(i) + ':' + native_snbt(item) for i, item in enumerate(value[1])) + ']'
    if kind == 8:
        return '"' + value.replace('"', '\\"') + '"'
    if kind in (1, 2, 3, 4, 6):
        return str(value) + {1: 'b', 2: 's', 3: '', 4: 'L', 6: 'd'}[kind]
    raise ValueError('Workbench native entry uses a type outside this fixture SNBT contract: ' + str(kind))


def parse_native_snbt(text):
    """Decode the recoverable 1.7.10 native view grammar without consulting expected data."""
    check(isinstance(text, str) and 0 < len(text) <= 65536 and len(text.encode('utf-8')) <= 65536,
          'native NBT display exceeds its bounded text size')
    offset, nodes = 0, 0

    def take(token):
        nonlocal offset
        check(text.startswith(token, offset), 'unknown or incomplete native NBT display grammar')
        offset += len(token)

    def tag(depth):
        nonlocal offset, nodes
        nodes += 1
        check(depth <= 64 and nodes <= 10000 and offset < len(text), 'native NBT display exceeds depth/node bounds or is incomplete')
        marker = text[offset]
        if marker == '{':
            offset += 1
            result = {}
            while offset < len(text) and text[offset] != '}':
                key = re.match(r'[A-Za-z0-9_.+-]+', text[offset:])
                check(key is not None and len(key[0]) <= 1024, 'native NBT display key is not recoverable')
                name = key[0]
                offset += len(name)
                take(':')
                check(name not in result and len(result) < 4096, 'duplicate or excessive native NBT display keys')
                result[name] = tag(depth + 1)
                if offset < len(text) and text[offset] == '}':
                    break
                take(',')
                check(offset < len(text) and text[offset] != '}', 'native NBT display has a trailing compound separator')
            take('}')
            return 10, result
        if marker == '[':
            offset += 1
            check(offset < len(text) and text[offset] != ']', 'empty native list display does not prove its element type')
            items = []
            while offset < len(text) and text[offset] != ']':
                check(len(items) < 4096, 'native NBT display list exceeds its entry bound')
                take(str(len(items)) + ':')
                items.append(tag(depth + 1))
                check(items[-1][0] == items[0][0], 'native NBT display list contains mixed tag types')
                if offset < len(text) and text[offset] == ']':
                    break
                take(',')
                check(offset < len(text) and text[offset] != ']', 'native NBT display has a trailing list separator')
            take(']')
            return 9, (items[0][0], items)
        if marker == '"':
            offset += 1
            chars = []
            while offset < len(text):
                char = text[offset]
                offset += 1
                if char == '"':
                    return 8, ''.join(chars)
                # NBTTagString's native 1.7.10 view escapes quotes, not other backslashes.
                if char == '\\' and offset < len(text) and text[offset] == '"':
                    char = '"'
                    offset += 1
                chars.append(char)
            raise ValueError('native NBT display string is not recoverable')
        number = re.match(r'[-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?[bsLfd]?', text[offset:])
        check(number is not None, 'unknown native NBT display tag grammar')
        token = number[0]
        offset += len(token)
        suffix = token[-1] if token[-1] in 'bsLfd' else ''
        kind = {'b': 1, 's': 2, '': 3, 'L': 4, 'f': 5, 'd': 6}[suffix]
        raw = token[:-1] if suffix else token
        if kind in (1, 2, 3, 4):
            check(re.fullmatch(r'[-+]?[0-9]+', raw) is not None, 'native integer display has noninteger grammar')
            value = int(raw)
            bits = {1: 8, 2: 16, 3: 32, 4: 64}[kind]
            check(-(1 << (bits - 1)) <= value < (1 << (bits - 1)), 'native integer display exceeds its exact tag width')
        else:
            value = float(raw)
            check(math.isfinite(value), 'native numeric display is nonfinite')
            if kind == 5:
                try:
                    value = struct.unpack('>f', struct.pack('>f', value))[0]
                except OverflowError as failure:
                    raise ValueError('native float display exceeds its tag width') from failure
                check(math.isfinite(value), 'native float display exceeds its finite tag width')
        return kind, value

    result = tag(0)
    check(offset == len(text), 'native NBT display contains trailing or unknown text')
    return result


def native_tags_equal(actual, expected):
    """Compound order is immaterial; tag kinds, list order and float bit patterns are exact."""
    if actual[0] != expected[0]:
        return False
    kind, value = actual
    other = expected[1]
    if kind == 10:
        return set(value) == set(other) and all(native_tags_equal(value[key], other[key]) for key in value)
    if kind == 9:
        return value[0] == other[0] and len(value[1]) == len(other[1]) and all(
            native_tags_equal(left, right) for left, right in zip(value[1], other[1]))
    if kind in (5, 6):
        return struct.pack('>f' if kind == 5 else '>d', value) == struct.pack('>f' if kind == 5 else '>d', other)
    return value == other


def native_composite_view_matches(node, text):
    # Preserve the existing exact-display checks, including unrecoverable empty-list views.
    # A changed display gains no inferred types: the independent parser must recover every tag.
    return text == native_snbt(node) or native_tags_equal(parse_native_snbt(text), node)


def relation_map(node, screen_present):
    check(node[0] == 9 and node[1][0] == 10, 'coordinate relations have the wrong native list type')
    expected = {('nc.sensor', slot) for slot in range(3)}
    if screen_present:
        expected |= {('nc.screen.min', -1), ('nc.screen.max', -1)}
    result = {}
    for relation in node[1][1]:
        adapter, slot = at(relation, 'adapter'), at(relation, 'slot')
        key = adapter[1], slot[1]
        check(adapter[0] == 8 and slot[0] == 3 and key in expected and key not in result,
              'relation adapter/slot is unknown, wrong typed or duplicated')
        check(at(relation, 'id') == (8, f'0,0,0:tile:{key[0]}:{key[1]}')
            and at(relation, 'payload') == (8, 'tile')
            and all(at(relation, axis) == (3, 0) for axis in ('x', 'y', 'z'))
            and at(relation, 'implicitDimension') == (1, 1)
            and at(relation, 'physicalAsset') == (1, int(key[0] == 'nc.sensor')),
            'relation host, ID, payload or asset kind differs from the native panel')
        result[key] = relation
    check(set(result) == expected, 'native relations do not exactly cover three sensors and actual screen bounds')
    return result


def processed_relations(source, screen_present, *, saved):
    result = copy.deepcopy(source)
    for (adapter, slot), relation in relation_map(result, screen_present).items():
        mode = ('relative', 'absolute', 'clear')[slot] if adapter == 'nc.sensor' else 'relative'
        relation[1]['mode'] = (8, mode)
        for key, coordinate, origin in zip(('dx', 'dy', 'dz'), ('targetX', 'targetY', 'targetZ'), (50, 80, 44)):
            relation[1][key] = (3, at(relation, coordinate)[1] - origin if mode == 'relative' else 0)
        if saved:
            relation[1].pop('targetKnown')
            if mode != 'absolute':
                for key in ('targetX', 'targetY', 'targetZ', 'targetDimension'):
                    relation[1][key] = (3, 0)
    return result


def coordinate_policy(policy):
    check(plain(at(policy, 'confirmedStages')) == ['preset', 'identity', 'quantum', 'coordinates', 'nbt', 'hints']
        and at(policy, 'coordinateDefault') == (8, 'auto') and at(policy, 'tendency') == (8, 'self'),
        'saved coordinate policy or six manual confirmations differ')
    overrides = at(policy, 'coordinateOverrides')
    check(overrides[0] == 9 and overrides[1][0] == 10, 'explicit coordinate choices have the wrong list type')
    wanted = {(8, f'0,0,0:tile:nc.sensor:{slot}'): (8, mode)
        for slot, mode in enumerate(('relative', 'absolute', 'clear'))}
    choices = {}
    for choice in overrides[1][1]:
        check(set(choice[1]) == {'id', 'mode'} and at(choice, 'id') not in choices,
              'explicit coordinate choice contains unknown fields or duplicate identity')
        choices[at(choice, 'id')] = at(choice, 'mode')
    check(choices == wanted, 'policy does not preserve the three exact explicit sensor choices')


def validate_required_frames(required):
    wanted = {f'workbench-{label}.{suffix}' for label in FRAME_LABELS for suffix in ('nbt', 'png')}
    check(wanted.issubset(set(required)), 'manifest omits an original or fresh saved-source review END/PNG witness')


def validate_saved_readonly(receipts, findings, finding, imported):
    """Bind the post-inspection END's complete native NBT to the original saved pair."""
    check(at(findings, finding + 'SavedNbtReadOnlyBeforeReview') == (1, 1),
          'saved-source NBT inspection did not preserve the required fresh review gates')
    artifact = at(findings, finding + 'LoadedArtifact')
    matches = []
    names = [f'workbench-save-as-{i}-disk-ack.nbt' for i in range(6)] + ['workbench-overwrite-disk-ack.nbt']
    for name in names:
        proof = read_nbt(receipts / name, typed=True)
        if all(at(artifact, key) == at(proof, key) for key in ('actualPath', 'actualPngPath', 'actualFileSha256', 'actualPngSha256')):
            matches.append(proof)
    check(len(matches) == 1, 'loaded saved artifact does not identify one original acknowledged JSON/PNG pair')
    proof = matches[0]
    check(all(at(proof, key) == value for key, value in artifact[1].items()),
          'read-only loaded artifact changed typed metadata, byte lengths or exact source-bound disk proof')
    document = json.loads((receipts / plain(at(proof, 'nativeJson'))).read_bytes())
    model = document.get('blueprint', document)
    snapshot = at(imported, 'actualSnapshot')
    cells = at(snapshot, 'cells')[1][1]
    check(len(cells) == len(model['cells']), 'saved read-only inspection changed the full loaded cell set')
    observed = {}
    for row in at(snapshot, 'cellNbtEntries')[1][1]:
        index = plain(at(row, 'index'))
        check(index not in observed and 0 <= index < len(cells), 'saved read-only native NBT has duplicate or invalid cell index')
        entries = at(row, 'entries')[1][1]
        paths = {plain(at(entry, 'path')): entry for entry in entries}
        check(len(paths) == len(entries), 'saved read-only native NBT view has duplicate paths')
        observed[index] = paths
    types = {1: 'byte', 2: 'short', 3: 'int', 4: 'long', 5: 'float', 6: 'double', 7: 'byte_array',
             8: 'string', 9: 'list', 10: 'compound', 11: 'int_array', 12: 'long_array'}
    expected_indices = set()
    for index, (cell, saved) in enumerate(zip(cells, model['cells'])):
        check([at(cell, axis) for axis in ('dx', 'dy', 'dz')] == [(3, saved[axis]) for axis in ('x', 'y', 'z')]
              and at(cell, 'id') == (8, saved['block']) and at(cell, 'meta') == (3, saved['metadata']),
              'read-only saved native cell changed exact identity, metadata or position')
        if not saved.get('tileNbt'):
            continue
        expected_indices.add(index)
        tile = read_nbt_bytes(base64.b64decode(saved['tileNbt'], validate=True), typed=True)
        expected = {}
        def visit(compound, parent='tile:'):
            for key, tag in compound[1].items():
                path = parent + '/' + key
                value = native_snbt(tag) if tag[0] in (9, 10) else str(tag[1])
                expected[path] = (types[tag[0]], value, tag)
                if tag[0] == 10:
                    visit(tag, path)
        visit(tile)
        check(index in observed and set(observed[index]) == set(expected),
              'saved NBT inspection removed or introduced a complete native field/path')
        check(all(at(observed[index][path], 'type') == (8, kind)
                  and at(observed[index][path], 'value')[0] == 8
                  and (native_composite_view_matches(tag, at(observed[index][path], 'value')[1]) if tag[0] in (9, 10)
                       else at(observed[index][path], 'value') == (8, value))
                  for path, (kind, value, tag) in expected.items()),
              'saved NBT inspection changed native typed coordinates, assets, relations or opaque/static configuration')
    check(set(observed) == expected_indices, 'saved read-only NBT view lost a tile or introduced unreviewed native data')


def validate_saved_reviews(receipts, findings, screen_present):
    policies = []
    for prefix, finding in (('saved-edit-review', 'savedEditFreshSixStageReview'),
                            ('overwrite-edit-review', 'overwriteEditFreshSixStageReview')):
        policy = at(findings, finding + 'LoadedPolicy')
        relations = at(findings, finding + 'LoadedRelations')
        check(at(findings, finding) == (1, 1), 'loaded saved source did not receive six fresh manual confirmations')
        coordinate_policy(policy)
        check(at(policy, 'deidentificationLevel') == (3, 0) and at(policy, 'quantum') == (8, 'rebind')
            and at(policy, 'autoHints') == (8, 'full'), 'loaded policy lost stored identity, quantum or NBT/hint choices')
        overrides = at(policy, 'cellNbtOverrides')
        check(overrides[0] == 9 and overrides[1][0] == 10 and len(overrides[1][1]) == 1,
              'loaded policy lost its blueprint-specific cell omission')
        override = overrides[1][1][0]
        check(at(override, 'all') == (1, 0) and all(at(override, axis) == (3, 0) for axis in ('x', 'y', 'z'))
            and plain(at(findings, 'actualOmittedNativeNbtRoot')) not in plain(at(override, 'keys')),
            'loaded policy changed its actual host or restored the omitted native root')
        imported = read_nbt(receipts / f'workbench-{prefix}-saved-import-save-disabled.nbt', typed=True)
        stage = read_nbt(receipts / f'workbench-{prefix}-stage-3-clear-target-unknown.nbt', typed=True)
        ack = read_nbt(receipts / f'workbench-{prefix}-six-stage-server-ack.nbt', typed=True)
        check(at(imported, 'actualSnapshot', 'blueprintProcessingComplete') == (1, 0)
            and at(imported, 'actualSnapshot', 'blueprintProcessingRequired') == (1, 1)
            and at(imported, 'actualSnapshot', 'processingPolicy') == policy
            and at(imported, 'actualSnapshot', 'processingRelations') == relations,
            'stored receipt incorrectly authorizes a new author session or differs from its loaded source')
        controls = at(imported, 'actualUi', 'drawnControls')[1][1]
        for control_id, enabled in ((140, 0), (221, 0), (166, 1)):
            matching = [control for control in controls if at(control, 'id') == (3, control_id)]
            check(len(matching) == 1 and at(matching[0], 'enabled') == (1, enabled),
                  'saved source must allow immediate NBT inspection while save/flow export remain gated by fresh review')
        check(at(imported, 'actualUi', 'nbtDrawer') == (1, 0) and at(imported, 'actualUi', 'busy') == (1, 0)
              and at(imported, 'actualUi', 'nbtValueIndex') == (3, -1)
              and at(imported, 'pendingAck') == (1, 0)
              and at(imported, 'actualSnapshot', 'blueprintDirty') == (1, 0)
              and at(imported, 'actualSnapshot', 'blueprintSavePending') == (1, 0),
              'saved read-only NBT close left editing, a pending operation or a dirty draft')
        validate_saved_readonly(receipts, findings, finding, imported)
        check(at(stage, 'actualUi', 'processingFlow') == (1, 1) and at(stage, 'actualUi', 'flowStage') == (3, 3)
            and plain(at(stage, 'actualLocalPolicyAtEnd', 'confirmedStages')) == ['preset', 'identity', 'quantum']
            and at(stage, 'actualSnapshot', 'processingRelations') == relations,
            'coordinate stage skipped manual confirmations or restored cleared source information')
        check(at(ack, 'actualSnapshot', 'blueprintProcessingComplete') == (1, 1)
            and at(ack, 'actualSnapshot', 'processingPolicy') == policy
            and at(ack, 'actualSnapshot', 'processingRelations') == relations,
            'six-stage owner ACK changes the complete stored policy or native relationships')
        for (adapter, slot), relation in relation_map(relations, screen_present).items():
            mode = ('relative', 'absolute', 'clear')[slot] if adapter == 'nc.sensor' else 'relative'
            internal = adapter != 'nc.sensor' or slot == 0
            check(at(relation, 'mode') == (8, mode) and at(relation, 'targetKnown') == (1, int(mode != 'clear'))
                and at(relation, 'internal') == (1, int(internal)), 'loaded relationship changed its saved mode or known target classification')
            if mode == 'clear':
                check(all(at(relation, key) == (3, 0)
                    for key in ('targetX', 'targetY', 'targetZ', 'targetDimension', 'dx', 'dy', 'dz')),
                    'loaded clear source recovered original 902 coordinates, dimension or offsets')
            elif mode == 'absolute':
                check([at(relation, key) for key in ('targetX', 'targetY', 'targetZ', 'targetDimension')]
                    == [(3, 901), (3, 80), (3, 44), (3, 0)], 'loaded absolute source changed its exact external target')
            else:
                offsets = (1, 0, 0) if adapter == 'nc.sensor' else (0, 0, 0)
                check([at(relation, key) for key in ('dx', 'dy', 'dz')] == [(3, value) for value in offsets]
                    and at(relation, 'targetDimension') == (3, 0), 'loaded relative source changed its saved offsets/dimension')
                check([at(relation, key)[1] for key in ('targetX', 'targetY', 'targetZ')]
                    == [at(imported, 'actualSnapshot', axis)[1] + offset for axis, offset in zip(('x', 'y', 'z'), offsets)],
                    'loaded relative source target does not follow its current actual host')
        policies.append(policy)
    return policies


def validate_native_import(receipts):
    ready = read_nbt(receipts / 'workbench-server-ready.nbt', typed=True)
    final = read_nbt(receipts / 'workbench-client-final.nbt', typed=True)
    findings = at(final, 'verifiedFindings')
    frame = read_nbt(receipts / 'workbench-initial-import-tab.nbt', typed=True)
    snapshot = at(frame, 'actualSnapshot')
    check(at(frame, 'capturePhase') == (8, 'RenderTick.END') and at(frame, 'actualNettyClient') == (1, 1),
          'native source was not frozen from the real initial import END frame')
    cells = at(snapshot, 'cells')[1][1]
    panel_indices = [i for i, cell in enumerate(cells) if at(cell, 'id') == (8, 'IC2NuclearControl:blockNuclearControlMain')]
    check(len(panel_indices) == 1 and at(cells[panel_indices[0]], 'meta') == (3, 9), 'import ACK has no unique native advanced panel')
    native_rows = [row for row in at(snapshot, 'cellNbtEntries')[1][1] if at(row, 'index') == (3, panel_indices[0])]
    check(len(native_rows) == 1, 'initial import ACK has duplicate or missing native panel NBT observations')
    entries = {plain(at(entry, 'path')): entry for entry in at(native_rows[0], 'entries')[1][1]}
    check(len(entries) == len(at(native_rows[0], 'entries')[1][1]), 'initial native NBT view contains duplicate paths')
    native_items = at(findings, 'sourceNativeItems')
    cards = rows(native_items, 'Slot', 1, range(3))
    originals = rows(at(ready, 'sourceTile', 'Items'), 'Slot', 1, range(3))
    for slot, registry in enumerate(REGISTRY_CLASSES):
        expected = copy.deepcopy(originals[slot])
        expected[1]['gtitBlueprintItemRegistry'] = (8, registry)
        check(cards[slot] == expected, 'captured native card changed identity, typed caches, configuration or unknown assets')
    check(at(entries['tile:/Items'], 'type') == (8, 'list')
        and at(entries['tile:/Items'], 'value') == (8, native_snbt(native_items)),
        'frozen native Items do not exactly match the original Netty import ACK')
    screen_present = 'tile:/screenData' in entries
    check(at(findings, 'sourceNativeScreenDataPresent') == (1, int(screen_present)), 'screen presence was inferred from another snapshot')
    if screen_present:
        screen = at(findings, 'sourceNativeScreenData')
        check(screen == at(ready, 'sourceTile', 'screenData') and at(entries['tile:/screenData'], 'type') == (8, 'compound')
            and at(entries['tile:/screenData'], 'value') == (8, native_snbt(screen)),
            'frozen complete native screen data do not match independent warm source and real import ACK')
        check(all(at(screen, corner + axis) == (3, value) for corner in ('min', 'max')
            for axis, value in zip(('X', 'Y', 'Z'), (50, 80, 44))), 'native screen bounds are incomplete or wrong typed')
    else:
        check('sourceNativeScreenData' not in findings[1] and 'screenData' not in at(ready, 'sourceTile')[1],
              'absent captured screen data conceals native screen coordinates')
    source_relations = at(findings, 'sourceNativeRelations')
    check(source_relations == at(snapshot, 'processingRelations'), 'frozen relations differ from original import ACK')
    for (adapter, slot), relation in relation_map(source_relations, screen_present).items():
        internal = adapter != 'nc.sensor' or slot == 0
        target = (51 if slot == 0 else 900 + slot) if adapter == 'nc.sensor' else 50
        check(at(relation, 'targetKnown') == (1, 1) and at(relation, 'internal') == (1, int(internal))
            and [at(relation, key) for key in ('targetX', 'targetY', 'targetZ', 'targetDimension')]
            == [(3, target), (3, 80), (3, 44), (3, 0)]
            and at(relation, 'mode') == (8, 'relative' if internal else 'absolute')
            and [at(relation, key) for key in ('dx', 'dy', 'dz')] == [(3, target - 50 if internal else 0), (3, 0), (3, 0)],
            'native relation changed its source target, dimension or internal/external classification')
    check(at(findings, 'reusablePresetCellOmissionRequiresFreshNativeReview') == (1, 1),
          'reusable preset cell omission was not reapplied through its actual native review')
    processed = read_nbt(receipts / 'workbench-native-process-server-ack.nbt', typed=True)
    coordinate_policy(at(processed, 'actualSnapshot', 'processingPolicy'))
    check(relation_map(at(processed, 'actualSnapshot', 'processingRelations'), screen_present)
        == relation_map(processed_relations(source_relations, screen_present, saved=False), screen_present),
        'processed actual server ACK does not retain every exact sensor and screen relationship')
    return ready, findings, screen_present


def validate_saved_workbench(receipts, fixture, required):
    ready, findings, screen_present = validate_native_import(receipts)
    validate_required_frames(required)
    loaded_policies = validate_saved_reviews(receipts, findings, screen_present)
    omitted = at(findings, 'actualOmittedNativeNbtRoot')
    expected = copy.deepcopy(at(ready, 'sourceTile'))
    check(omitted[0] == 8 and omitted[1] in expected[1] and omitted[1] not in ('id', 'x', 'y', 'z', 'Items', 'screenData'),
          'selected native omission is unknown or erases protected source identity/assets/relations')
    expected[1].pop(omitted[1])
    expected[1]['Items'] = copy.deepcopy(at(findings, 'sourceNativeItems'))
    for slot, card in rows(at(expected, 'Items'), 'Slot', 1, range(3)).items():
        tag = at(card, 'tag')
        if slot != 1:
            for key in ('x', 'y', 'z'):
                tag[1].pop(key)
        tag[1]['gtmbCoordinatePayment'] = (8, ('relative', 'absolute', 'clear')[slot])
    if screen_present:
        for corner in ('min', 'max'):
            for axis in ('X', 'Y', 'Z'):
                at(expected, 'screenData')[1].pop(corner + axis)
    expected[1]['gtmbCoordinateRelations'] = processed_relations(at(findings, 'sourceNativeRelations'), screen_present, saved=True)
    names = [f'workbench-save-as-{i}-disk-ack.nbt' for i in range(6)] + ['workbench-overwrite-disk-ack.nbt']
    check(all(name in required for name in names), 'contract omits actual save-as/overwrite owner ACKs')
    proofs = [read_nbt(receipts / name) for name in names]
    ids = [proof['localId'] for proof in proofs[:6]]
    check(len(set(ids)) == 6 and proofs[-1]['localId'] in ids, 'save-as IDs are reused or overwrite targeted an unknown file')
    deleted = proofs[-1]['localId']
    folder = (fixture / 'client/MiaoBlueprints').resolve()
    check(folder.is_dir() and not folder.is_symlink(), 'isolated native pair directory is absent or symlinked')
    selected = []
    pairs, nonces, generations = [], set(), []
    final_findings = plain(findings)
    check(all(final_findings.get(key) for key in ('actualNativeJsonPngPairAndVersionMetadata',
          'privateLibraryContainsOnlyCurrentNativePairs', 'actualNativeFboReadbackMatchesDiskPreview',
          'actualNativePreviewVerificationAndOwnerCommitPackets', 'overwriteUpdatesNativePreviewSourceHash',
          'savedNativeMetadataPreservedThroughEditReview', 'privateDeleteRemovesNativePair')),
          'workbench lacks mandatory actual native pair, packet, overwrite, edit or deletion findings')
    for i, proof in enumerate(proofs):
        local = proof['localId']
        check(re.fullmatch(r'local_[a-fA-F0-9-]{36}', local) and proof.get('passed') and proof.get('actualNettyClient')
            and proof['name'] == (f'Workbench actual {i}' if i < 6 else 'Workbench actual overwritten')
            and re.fullmatch(r'[a-f0-9]{64}', proof['actualFileSha256']), 'actual disk save receipt identity or SHA is invalid')
        snapshot = proof['actualSnapshot']
        check(snapshot.get('blueprintSaved') and snapshot.get('blueprintOperationSuccess')
            and not snapshot.get('blueprintSavePending') and not snapshot.get('blueprintDirty')
            and snapshot.get('libraryLocalId') == local, 'actual save receipt lacks successful owner ACK')
        stem = f'workbench-save-as-{i}-native' if i < 6 else 'workbench-overwrite-native'
        check(all(stem + suffix in required for suffix in ('.json', '.png')), 'contract omits actual saved native pair bytes')
        pair, path, png_path, json_bytes, png_bytes, nonce, generation = verify_pair_receipt(
            proof, read_nbt(receipts / names[i], typed=True), receipts, folder, expected_stem=stem)
        check(nonce not in nonces and (not generations or generation > generations[-1]),
              'actual saves reused a nonce or native capture generation')
        nonces.add(nonce)
        generations.append(generation)
        pairs.append(pair)
        if local == deleted:
            check(not path.exists() and not png_path.exists(), 'confirmed delete left JSON or native PNG behind')
        else:
            check(all(p.is_file() and not p.is_symlink() for p in (path, png_path))
                  and path.read_bytes() == json_bytes and png_path.read_bytes() == png_bytes,
                  'surviving actual saved pair differs from its original disk ACK bytes')
        blueprint = pair['model']
        check(blueprint['id'] == local and blueprint['name'] == proof['name'], 'saved JSON changed local identity or exact typed name')
        panels = [cell for cell in blueprint['cells'] if cell['block'] == 'IC2NuclearControl:blockNuclearControlMain']
        check(len(panels) == 1 and panels[0]['metadata'] == 9, 'saved JSON has no unique native advanced panel')
        panel = read_nbt_bytes(base64.b64decode(panels[0]['tileNbt'], validate=True), typed=True)
        check(panel == expected, 'saved full native panel changed beyond explicit typed modes, complete relations and reviewed omission')
        processing = read_nbt_bytes(base64.b64decode(blueprint['processingNbt'], validate=True), typed=True)
        check(at(processing, 'complete') == (1, 1) and at(processing, 'policy', 'autoHints') == (8, 'full'),
              'saved native processing receipt is incomplete or lost final hints')
        coordinate_policy(at(processing, 'policy'))
        check(all(at(processing, 'policy') == policy for policy in loaded_policies),
              'saved processing policy differs from the complete stored policies preserved by fresh native reviews')
        if local != deleted:
            selected.extend((path, png_path))
    previous = pairs[ids.index(deleted)]
    check(proofs[-1]['previousJsonSha256'] == previous['jsonSha256']
          and proofs[-1]['previousPngSha256'] == previous['pngSha256']
          and pairs[-1]['jsonSha256'] != previous['jsonSha256'] and pairs[-1]['pngSha256'] != previous['pngSha256'],
          'overwrite did not replace the exact old JSON and source-bound native PNG')
    removed = final_findings['deletedNativePair']
    check(removed.get('localId') == deleted and removed.get('jsonAbsent') and removed.get('pngAbsent')
          and removed['actualPath'] == proofs[-1]['actualPath'] and removed['actualPngPath'] == proofs[-1]['actualPngPath']
          and removed['actualFileSha256'] == pairs[-1]['jsonSha256'] and removed['actualPngSha256'] == pairs[-1]['pngSha256'],
          'deleted pair proof does not identify the exact last overwritten JSON and PNG')
    expected_files = {local + suffix for local in set(ids) - {deleted} for suffix in ('.json', '.png')}
    check(len(selected) == 10 and {path.name for path in folder.iterdir()} == expected_files
          and all(path.is_file() and not path.is_symlink() for path in folder.iterdir()),
          'confirmed delete did not preserve exactly five native JSON/PNG pairs without stale or temporary files')
    return selected


def validate_keyboard(receipts, required):
    names = [name for name in required if re.fullmatch(r'workbench-keyboard-step-[0-9]+\.nbt', name)]
    check(len(names) == len(KEYBOARD_INPUTS), 'contract does not require every real keyboard operation')
    labels = set()
    steps = set()
    for name in names:
        row = read_nbt(receipts / name)
        label = row['label']
        check(label in KEYBOARD_INPUTS and label not in labels and row['step'] not in steps,
              'keyboard operation label or step is unknown/duplicated')
        check(name == f"workbench-keyboard-step-{row['step']}.nbt", 'keyboard diagnostic step/path differ')
        labels.add(label)
        steps.add(row['step'])
        check(row.get('passed') and 'failure' not in row and row.get('focusedAfterNativeClick')
            and row.get('focusedAfterInput') and row['selectedAfterControlA'] == row['textBefore']
            and (row['field'], row['expectedText']) == KEYBOARD_INPUTS[label] and row['textAfter'] == row['expectedText'],
            'native keyboard focus, complete Ctrl+A selection or exact replacement differs: ' + name)
