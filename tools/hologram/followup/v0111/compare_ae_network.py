"""Read two detached AE observations and report raw graph evidence, never a world/material PASS.

Accepts the observer's native uncompressed/gzip NBT, or a JSON representation for offline inspection.
No source identities or nonzero energy are removed. Exit 0 means the evidence was read, not accepted.
"""
from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct

LIMIT = 64 * 1024 * 1024


class EvidenceError(ValueError):
    pass


class NbtReader:
    """Strict reader for the native .1710 tag family used by this diagnostic schema."""

    def __init__(self, data: bytes):
        self.stream = io.BytesIO(data)

    def read(self, count):
        if count < 0 or count > LIMIT:
            raise EvidenceError('invalid native byte length')
        result = self.stream.read(count)
        if len(result) != count:
            raise EvidenceError('truncated native NBT')
        return result

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.read(struct.calcsize('>' + fmt)))[0]

    def string(self):
        # Java writeUTF represents NUL with C0 80 and permits UTF-16 surrogate code units.
        return self.read(self.number('H')).replace(b'\xc0\x80', b'\0').decode('utf-8', 'surrogatepass')

    def length(self, element_bytes=1):
        count = self.number('i')
        if count < 0 or count > LIMIT // element_bytes:
            raise EvidenceError('invalid native collection length')
        return count

    def payload(self, tag, depth=0):
        if depth > 512:
            raise EvidenceError('native NBT nesting exceeds 512')
        if tag in (1, 2, 3, 4, 5, 6):
            return self.number({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[tag])
        if tag == 7:
            return list(self.read(self.length()))
        if tag == 8:
            return self.string()
        if tag == 9:
            element = self.number('B')
            count = self.length()
            if element > 11 or count and element == 0:
                raise EvidenceError('unknown or malformed native list element type')
            return [self.payload(element, depth + 1) for _ in range(count)]
        if tag == 10:
            result = {}
            while True:
                child = self.number('B')
                if child == 0:
                    return result
                key = self.string()
                if key in result:
                    raise EvidenceError('duplicate native compound key')
                result[key] = self.payload(child, depth + 1)
        if tag == 11:
            return [self.number('i') for _ in range(self.length(4))]
        raise EvidenceError('unknown native NBT tag type ' + str(tag))

    def root(self):
        if self.number('B') != 10:
            raise EvidenceError('native evidence root must be a compound')
        self.string()
        result = self.payload(10)
        if self.stream.read(1):
            raise EvidenceError('trailing bytes after native evidence')
        return result


def load(path: Path):
    with path.open('rb') as stream:
        data = stream.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise EvidenceError('evidence exceeds diagnostic byte limit')
    if data[:2] == b'\x1f\x8b':
        with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
            data = stream.read(LIMIT + 1)
        if len(data) > LIMIT:
            raise EvidenceError('expanded evidence exceeds diagnostic byte limit')
    if path.suffix.lower() == '.json':
        return json.loads(data.decode('utf-8'))
    return NbtReader(data).root()


def require(condition, message):
    if not condition:
        raise EvidenceError(message)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def flag(value):
    require(isinstance(value, (int, bool)) and value in (0, 1), 'invalid boolean evidence')
    return bool(value)


def validate(snapshot):
    require(isinstance(snapshot, dict), 'evidence must be a compound/object')
    version = snapshot.get('schemaVersion')
    require(version in (1, 2), 'unsupported observer schema')
    require(snapshot.get('algorithm') == 'ae-native-graph-v' + str(version), 'unsupported graph algorithm')
    require(snapshot.get('scope') == 'raw-observation-only', 'only raw graph scope is admitted')
    require(flag(snapshot.get('evidenceOnly')) and not flag(snapshot.get('authorizesPass')), 'evidence must not authorize PASS')
    require(snapshot.get('dimension') == 1048578 and snapshot.get('size') == [40, 40, 40], 'wrong fixture dimensions')
    origin = snapshot.get('origin')
    require(isinstance(origin, list) and len(origin) == 3 and all(integer(x) for x in origin), 'missing fixture origin')
    require(integer(snapshot.get('worldTick')) and isinstance(snapshot.get('sampleLabel'), str), 'missing sample timing/label')
    for name in ('nodes', 'edges', 'grids', 'unknowns'):
        require(isinstance(snapshot.get(name), list), 'missing native ' + name)
    require(all(isinstance(x, str) for x in snapshot['unknowns']), 'malformed unknown coverage rows')
    require(snapshot.get('unknownCount') == len(snapshot['unknowns']), 'unknown coverage count mismatch')
    require(flag(snapshot.get('coverageComplete')) == (not snapshot['unknowns']), 'unknown coverage cannot be called complete')
    nodes = {}
    for node in snapshot['nodes']:
        require(isinstance(node, dict) and isinstance(node.get('key'), str) and node['key'], 'missing structural port key')
        require(node['key'] not in nodes, 'duplicate structural port key')
        flag(node.get('internal'))
        located = flag(node.get('located'))
        require(located or snapshot['unknowns'], 'unlocated node cannot have complete coverage')
        if located:
            require(node['key'].startswith('relative:' if node['internal'] else 'absolute:'), 'port coordinate role mismatch')
            for name in ('p', 'k', 'observedGrid'):
                require(integer(node.get(name)), 'missing exact node ' + name)
            if flag(node.get('gPresent')):
                require(integer(node.get('g')), 'missing exact node g')
            else:
                require(snapshot['unknowns'], 'missing GridStorage cannot have complete coverage')
            if version == 2:
                flag(node.get('persistedPort'))
        nodes[node['key']] = node
    require(snapshot.get('nodeCount') == len(nodes), 'node count mismatch')
    require(snapshot.get('internalNodeCount') == sum(flag(n['internal']) for n in nodes.values()), 'internal-node count mismatch')
    grids = {}
    membership = {}
    for grid in snapshot['grids']:
        require(integer(grid.get('observedGrid')) and grid['observedGrid'] not in grids, 'duplicate/missing observed grid identity')
        require(isinstance(grid.get('members'), list) and all(isinstance(k, str) for k in grid['members']), 'missing grid members')
        require(len(set(grid['members'])) == len(grid['members']), 'duplicate node in grid group')
        require(any(nodes.get(k, {}).get('internal') for k in grid['members']), 'selected grid must contain an internal port')
        for key in grid['members']:
            require(key in nodes and key not in membership, 'unknown or multiply grouped node')
            membership[key] = grid['observedGrid']
            if nodes[key].get('located'):
                require(nodes[key]['observedGrid'] == grid['observedGrid'], 'node/grid identity mismatch')
        if flag(grid.get('energyCacheKnown')):
            require(integer(grid.get('extraRawBits')), 'missing exact live extra bits')
            require(-(1 << 63) <= grid['extraRawBits'] < (1 << 63), 'extra bits outside native long range')
            actual = struct.unpack('>d', struct.pack('>q', grid['extraRawBits']))[0]
            finite = actual == actual and actual not in (float('inf'), float('-inf'))
            require(flag(grid.get('extraFinite')) == finite and flag(grid.get('extraZero')) == (actual == 0), 'extra scalar disagrees with raw native bits')
        else:
            require(snapshot['unknowns'], 'unknown energy cache cannot have complete coverage')
        require(integer(grid.get('energyProviderEntries', 0)), 'invalid energy-provider count')
        if grid.get('energyProviderEntries', 0):
            require(snapshot['unknowns'], 'unvalidated energy coupling cannot have complete coverage')
        if flag(grid.get('gPresent')):
            require(integer(grid.get('g')), 'missing exact grid g')
            require(snapshot['unknowns'] or all(nodes[k].get('gPresent') and nodes[k].get('g') == grid['g']
                                              for k in grid['members']), 'node storage differs from its grid storage')
        else:
            require(snapshot['unknowns'], 'missing grid storage cannot have complete coverage')
        if version == 2:
            if flag(grid.get('pathCacheKnown')):
                flag(grid.get('networkBooting'))
                require(isinstance(grid.get('controllerState'), str), 'missing native controller state')
            else:
                require(snapshot['unknowns'], 'unknown path cache cannot have complete coverage')
        grids[grid['observedGrid']] = grid
    require(set(membership) == set(nodes), 'nodes missing their original grid grouping')
    require(snapshot.get('selectedGridCount') == len(grids), 'selected-grid count mismatch')
    edge_set = set()
    for edge in snapshot['edges']:
        require(edge.get('a') in nodes and edge.get('b') in nodes, 'edge endpoint absent from graph')
        require(membership[edge['a']] == membership[edge['b']], 'edge joins two distinct native grids')
        require(edge['a'] <= edge['b'], 'edge endpoint order is not canonical')
        for name in ('directionA', 'directionB'):
            require(integer(edge.get(name)) and 0 <= edge[name] <= 6, 'invalid native edge direction')
        directional = flag(edge.get('hasDirection'))
        require(directional == (edge['directionA'] != 6 and edge['directionB'] != 6), 'direction flag mismatch')
        require(not directional or edge['directionA'] ^ 1 == edge['directionB'], 'edge directions are not opposite')
        require(edge['a'] != edge['b'], 'native connection cannot loop to the same port')
        identity = (edge['a'], edge['b'], edge['directionA'], edge['directionB'], directional)
        require(identity not in edge_set, 'duplicate native edge')
        edge_set.add(identity)
    if version == 2:
        validate_runtime_ports(snapshot, nodes, membership, edge_set)
    return nodes, grids, edge_set


RELAY = 'com.miaokatze.gtswn.common.quantum.QuantumNodeBridgeRelay'
QUANTUM_NODE = 'com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode'


def validate_runtime_ports(snapshot, nodes, membership, edges):
    endpoint_pairs = {(a, b) for a, b, *_ in edges}

    def connected(a, b):
        return tuple(sorted((a, b))) in endpoint_pairs

    for key, node in nodes.items():
        if not node.get('located'):
            continue
        if node.get('machineClass') == RELAY:
            require(not flag(node['persistedPort']) and node.get('portPath') == '@runtime/quantumBridgeRelay',
                    'runtime relay cannot impersonate a persisted NBT port')
            owner, target = node.get('relayOwnerPort'), node.get('relayTargetPort')
            flags = [flag(node.get(name)) for name in ('relayAttachmentMatches', 'relayLocalEdgeLive',
                     'relayAnchorEdgeLive', 'relaySameGrid', 'relayAnchorLocationMatches', 'relayTopologyComplete',
                     'relayOwnerPlayerMatches')]
            if owner not in nodes or target not in nodes:
                require(snapshot['unknowns'] and not flags[5], 'relay endpoints missing without incomplete coverage')
                continue
            require(nodes[owner].get('machineClass') == QUANTUM_NODE, 'relay owner port is not an exact quantum node')
            require(flag(node['relayLocalEdgeLive']) == connected(key, owner)
                    and flag(node['relayAnchorEdgeLive']) == connected(key, target), 'relay edge facts disagree with original edges')
            grouped = membership[key] == membership[owner] == membership[target]
            require(flag(node['relaySameGrid']) == grouped, 'relay grouping disagrees with original graph')
            require(flag(node['relayOwnerPlayerMatches']) == (node['p'] == nodes[owner]['p']), 'relay actor fact disagrees with exact p')
            for axis in ('dimension', 'x', 'y', 'z'):
                require(node.get(axis) == nodes[owner].get(axis), 'runtime relay structural coordinate is not its real owner')
            anchor_matches = all(node.get('relayAnchor' + name) == nodes[target].get(field)
                                 for name, field in [('Dimension', 'dimension'), ('X', 'x'), ('Y', 'y'), ('Z', 'z')])
            require(flag(node['relayAnchorLocationMatches']) == anchor_matches, 'relay native location disagrees with actual target')
            complete = all(flags[:5])
            require(flags[5] == complete, 'relay complete flag disagrees with recorded native facts')
            require(complete or snapshot['unknowns'], 'incomplete relay cannot have complete coverage')
        bridge = node.get('quantumBridge')
        if bridge is not None:
            require(node.get('machineClass') == QUANTUM_NODE and isinstance(bridge, dict), 'quantum facts on an unrelated host')
            for name in ('hasAnchor', 'linkedByExistingMembership', 'connectionPresent', 'relayPresent',
                         'coreConnectionLive', 'proxyReady', 'clientLinkedCache'):
                flag(bridge.get(name))
            require(isinstance(bridge.get('offlineReason'), str), 'missing native quantum offline reason')
            for name in ('anchorDimension', 'anchorX', 'anchorY', 'anchorZ', 'lastMaintenanceTick', 'reconnectBackoff'):
                require(integer(bridge.get(name)), 'missing native quantum ' + name)
            require(flag(bridge['hasAnchor']) == (bridge['anchorDimension'] != -(1 << 31)), 'quantum anchor presence mismatch')
            if bridge['relayPresent'] and bridge['relayPort'] not in nodes:
                require(snapshot['unknowns'], 'quantum relay not included in existing node roster')


def native_readiness(snapshot, nodes, grids):
    if snapshot['schemaVersion'] != 2:
        return dict(available=False, reason='legacy observation did not record relay ownership or native path/bridge readiness',
                    necessaryConditionsAtSample=False, provesStableWindow=False)
    internal = {k: n for k, n in nodes.items() if n['internal'] and n.get('quantumBridge') is not None}
    anchored = {k: n for k, n in internal.items() if n['quantumBridge']['hasAnchor']}
    unlinked = [dict(port=k, facts=n['quantumBridge']) for k, n in anchored.items()
                if not n['quantumBridge']['linkedByExistingMembership'] or not n['quantumBridge']['proxyReady']]
    booting = [g['observedGrid'] for g in grids.values() if g.get('networkBooting')]
    path_unknown = [g['observedGrid'] for g in grids.values() if not g.get('pathCacheKnown')]
    conflicts = [g['observedGrid'] for g in grids.values() if g.get('controllerState') == 'CONTROLLER_CONFLICT']
    relay_actor_mismatches = [k for k, n in nodes.items() if n.get('machineClass') == RELAY and not n.get('relayOwnerPlayerMatches')]
    return dict(available=True, internalQuantumNodes=len(internal), internalAnchoredQuantumNodes=len(anchored),
                internalUnlinkedOrNotReady=unlinked, bootingGrids=booting, unknownPathGrids=path_unknown,
                controllerConflictGrids=conflicts, relayActorMismatches=relay_actor_mismatches,
                necessaryConditionsAtSample=bool(snapshot['coverageComplete'] and anchored and not unlinked
                                               and not booting and not path_unknown and not conflicts and not relay_actor_mismatches),
                provesStableWindow=False,
                limitations=['expected anchored-node roster must come from independent World/manifest evidence',
                             'one ready sample cannot prove an unchanged graph over a full native maintenance window',
                             'the global native connection budget and backoff are never advanced or overridden'])


def extra_rows(grids):
    rows = []
    for grid in grids.values():
        row = dict(members=sorted(grid['members']), observedGrid=grid['observedGrid'],
                   energyCacheKnown=bool(grid['energyCacheKnown']), gPresent=bool(grid['gPresent']))
        if grid['gPresent']:
            row['g'] = grid['g']
        if grid['energyCacheKnown']:
            bits = grid['extraRawBits']
            row.update(rawLong=bits, rawHex=f'{bits & ((1 << 64) - 1):016x}',
                       value=grid.get('extraText', ''), finite=bool(grid['extraFinite']), zero=bool(grid['extraZero']))
        rows.append(row)
    return sorted(rows, key=lambda row: row['members'])


def compare(before, after):
    bn, bg, be = validate(before)
    an, ag, ae = validate(after)
    common = sorted(set(bn) & set(an))
    changes = {field: [] for field in ('g', 'p', 'k')}
    for key in common:
        for field in changes:
            old, new = bn[key].get(field), an[key].get(field)
            if old != new or field == 'g' and bn[key].get('gPresent') != an[key].get('gPresent'):
                changes[field].append(dict(port=key, before=old, after=new,
                                           beforePresent=field in bn[key], afterPresent=field in an[key]))
    before_groups = sorted(tuple(sorted(g['members'])) for g in bg.values())
    after_groups = sorted(tuple(sorted(g['members'])) for g in ag.values())
    bgroup = {tuple(sorted(g['members'])): g for g in bg.values()}
    agroup = {tuple(sorted(g['members'])): g for g in ag.values()}
    group_identities = []
    for members in sorted(set(bgroup) & set(agroup)):
        old, new = bgroup[members], agroup[members]
        if old.get('g') != new.get('g') or old['gPresent'] != new['gPresent']:
            group_identities.append(dict(members=members, before=old.get('g'), after=new.get('g'),
                                         beforePresent=bool(old['gPresent']), afterPresent=bool(new['gPresent'])))
    before_extra, after_extra = extra_rows(bg), extra_rows(ag)
    before_located = {k for k, n in bn.items() if n.get('located')}
    after_located = {k for k, n in an.items() if n.get('located')}
    before_located_edges = {edge for edge in be if edge[0] in before_located and edge[1] in before_located}
    after_located_edges = {edge for edge in ae if edge[0] in after_located and edge[1] in after_located}
    located_groups = dict(before=sorted(tuple(sorted(k for k in g['members'] if k in before_located)) for g in bg.values()),
                          after=sorted(tuple(sorted(k for k in g['members'] if k in after_located)) for g in ag.values()))
    transitions = {}
    for key in common:
        if bn[key].get('located') and an[key].get('located'):
            pair = (bn[key].get('g'), an[key].get('g'))
            transitions.setdefault(pair, []).append(key)
    covered = bool(before['coverageComplete'] and after['coverageComplete'])
    sampled = before['internalNodeCount'] > 0 and after['internalNodeCount'] > 0
    extra_zero = covered and sampled and all(row['energyCacheKnown'] and row.get('finite') and row.get('zero')
                                             for row in before_extra + after_extra)
    return dict(
        status='EVIDENCE_ONLY', evidenceOnly=True, authorizesPass=False, scope='raw-isomorphism-only',
        algorithm='ae-native-graph-comparison-v2',
        inputAlgorithms=dict(before=before['algorithm'], after=after['algorithm']),
        sameObservationSchema=before['algorithm'] == after['algorithm'],
        beforeSample=dict(label=before['sampleLabel'], worldTick=before['worldTick'], origin=before['origin']),
        afterSample=dict(label=after['sampleLabel'], worldTick=after['worldTick'], origin=after['origin']),
        coverageComplete=covered, bothSamplesHaveInternalNodes=sampled,
        unknownCoverage=dict(before=before['unknowns'], after=after['unknowns']),
        nodeSetsEqual=set(bn) == set(an), missingPorts=sorted(set(bn) - set(an)), unexpectedPorts=sorted(set(an) - set(bn)),
        edgeSetsEqual=be == ae, missingEdges=sorted(be - ae), unexpectedEdges=sorted(ae - be),
        nativeGridMembershipEqual=before_groups == after_groups,
        gridMembership=dict(before=before_groups, after=after_groups),
        playerIdsEqual=not changes['p'], securityKeysEqual=not changes['k'],
        nativeIdentityDifferences=changes,
        gridIdentityDifferences=group_identities,
        extraZeroAtBothObservedInstants=extra_zero,
        liveExtra=dict(before=before_extra, after=after_extra),
        allRecordedGridExtrasZero=bool(before_extra and after_extra and all(row['energyCacheKnown'] and row.get('finite') and row.get('zero')
                                                                         for row in before_extra + after_extra)),
        locatedPortSetsEqual=before_located == after_located,
        locatedEdgeSetsEqual=before_located_edges == after_located_edges,
        locatedGridGroupsEqual=located_groups['before'] == located_groups['after'],
        locatedGridGroups=located_groups,
        nativeGridTransitions=[dict(before=old, after=new, ports=ports) for (old, new), ports in
                               sorted(transitions.items(), key=lambda item: (str(item[0]), item[1]))],
        legacyUnknownKinds=dict(before=dict(Counter(before['unknowns'])), after=dict(Counter(after['unknowns']))),
        nativeReadiness=dict(before=native_readiness(before, bn, bg), after=native_readiness(after, an, ag)),
        limitations=[
            'g differences remain explicit evidence; no native NBT field is removed or approved here',
            'a matching graph does not prove inventory, config, patterns, owner permissions or energy conservation',
            'zero extra applies only to these actual samples; it does not prove an unobserved cut/ready instant',
            'nonzero or unknown extra requires an independent energy ledger and is never treated as zero',
            'self/share network merge, terminal rebinding, security migration and FMP path identity are not admitted',
        ],
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        result = compare(load(args.before), load(args.after))
        result['inputs'] = []
        for path in (args.before, args.after):
            with path.open('rb') as stream:
                result['inputs'].append(dict(path=str(path.resolve()), sha256=hashlib.file_digest(stream, 'sha256').hexdigest()))
        exit_code = 0
    except (EvidenceError, OSError, ValueError, TypeError, KeyError, struct.error) as error:
        result = dict(status='INVALID_EVIDENCE', evidenceOnly=True, authorizesPass=False,
                      error=type(error).__name__ + ': ' + str(error))
        exit_code = 2
    rendered = json.dumps(result, indent=2, ensure_ascii=True, allow_nan=False) + '\n'
    if args.output:
        args.output.write_text(rendered, encoding='utf-8')
    print(rendered, end='')
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
