"""Offline diagnostic checks only; no game, fixture, source NBT or product is modified."""
import copy
import struct
import unittest

from compare_ae_network import EvidenceError, NbtReader, compare, QUANTUM_NODE, RELAY


def sample(label='before'):
    return dict(
        schemaVersion=1, algorithm='ae-native-graph-v1', scope='raw-observation-only',
        evidenceOnly=True, authorizesPass=False, coverageComplete=True,
        dimension=1048578, size=[40, 40, 40], origin=[24576, 80, 4096],
        sampleLabel=label, worldTick=123, unknowns=[], unknownCount=0,
        nodeCount=2, internalNodeCount=1, selectedGridCount=1,
        nodes=[dict(key='relative:source-port', located=True, internal=True, observedGrid=1, gPresent=True, g=10, p=4, k=-1),
               dict(key='absolute:boundary-port', located=True, internal=False, observedGrid=1, gPresent=True, g=10, p=4, k=-1)],
        edges=[dict(a='absolute:boundary-port', b='relative:source-port', directionA=2, directionB=3, hasDirection=True)],
        grids=[dict(observedGrid=1, members=['absolute:boundary-port', 'relative:source-port'],
                    gPresent=True, g=10, energyCacheKnown=True, extraRawBits=0, extraText='0.0',
                    extraFinite=True, extraZero=True, energyProviderEntries=0)],
    )


def native_tag(value):
    if isinstance(value, bool):
        return 1, struct.pack('>b', value)
    if isinstance(value, int):
        return 4, struct.pack('>q', value)
    if isinstance(value, str):
        encoded = value.encode('utf-8', 'surrogatepass').replace(b'\0', b'\xc0\x80')
        return 8, struct.pack('>H', len(encoded)) + encoded
    if isinstance(value, list):
        tags = [native_tag(x) for x in value]
        tag = tags[0][0] if tags else 0
        assert all(t == tag for t, _ in tags)
        return 9, struct.pack('>Bi', tag, len(tags)) + b''.join(b for _, b in tags)
    if isinstance(value, dict):
        body = b''
        for name, child in value.items():
            tag, data = native_tag(child)
            key = name.encode('utf-8')
            body += struct.pack('>BH', tag, len(key)) + key + data
        return 10, body + b'\0'
    raise TypeError(value)


def relay_sample():
    value = sample()
    owner, target, relay = 'relative:quantum-owner', 'absolute:controller', 'relative:quantum-runtime-relay'
    base = dict(located=True, observedGrid=1, gPresent=True, g=10, p=4, k=-1, dimension=1048578)
    value.update(schemaVersion=2, algorithm='ae-native-graph-v2', nodeCount=3, internalNodeCount=2)
    value['nodes'] = [
        dict(base, key=owner, internal=True, x=24576, y=80, z=4096, machineClass=QUANTUM_NODE, persistedPort=True,
             quantumBridge=dict(hasAnchor=True, anchorDimension=1048578, anchorX=24627, anchorY=83, anchorZ=4099,
                                linkedByExistingMembership=True, connectionPresent=True, relayPresent=True,
                                coreConnectionLive=True, proxyReady=True, clientLinkedCache=False,
                                relayPort=relay, targetPort=target, offlineReason='NONE',
                                lastMaintenanceTick=100, reconnectBackoff=20)),
        dict(base, key=target, internal=False, x=24627, y=83, z=4099,
             machineClass='appeng.tile.networking.TileController', persistedPort=True),
        dict(base, key=relay, internal=True, x=24576, y=80, z=4096, machineClass=RELAY, persistedPort=False,
             portPath='@runtime/quantumBridgeRelay', relayOwnerPort=owner, relayTargetPort=target,
             relayAnchorDimension=1048578, relayAnchorX=24627, relayAnchorY=83, relayAnchorZ=4099,
             relayAttachmentMatches=True, relayLocalEdgeLive=True, relayAnchorEdgeLive=True,
             relaySameGrid=True, relayAnchorLocationMatches=True, relayTopologyComplete=True,
             relayOwnerPlayerMatches=True),
    ]
    value['edges'] = [dict(a=min(a, b), b=max(a, b), directionA=6, directionB=6, hasDirection=False)
                      for a, b in [(owner, relay), (relay, target)]]
    value['grids'][0].update(members=[owner, target, relay], pathCacheKnown=True,
                             networkBooting=False, controllerState='CONTROLLER_ONLINE')
    return value


class GraphEvidenceTests(unittest.TestCase):
    def test_identical_graph_is_evidence_and_never_authorizes_pass(self):
        original = sample()
        result = compare(original, copy.deepcopy(original))
        self.assertTrue(result['nodeSetsEqual'] and result['edgeSetsEqual'] and result['nativeGridMembershipEqual'])
        self.assertTrue(result['extraZeroAtBothObservedInstants'])
        self.assertEqual('EVIDENCE_ONLY', result['status'])
        self.assertFalse(result['authorizesPass'])
        self.assertEqual(sample(), original)

    def test_g_reallocation_remains_an_explicit_difference(self):
        after = sample('ready')
        after['origin'] = [24576, 80, 4240]
        after['grids'][0]['g'] = 99
        for node in after['nodes']:
            node['g'] = 99
        result = compare(sample(), after)
        self.assertTrue(result['nodeSetsEqual'] and result['nativeGridMembershipEqual'])
        self.assertEqual(2, len(result['nativeIdentityDifferences']['g']))
        self.assertEqual(10, result['nativeIdentityDifferences']['g'][0]['before'])
        self.assertEqual(99, result['nativeIdentityDifferences']['g'][0]['after'])
        self.assertFalse(result['authorizesPass'])

    def test_player_and_security_changes_are_never_normalized(self):
        after = sample()
        after['nodes'][0].update(p=5, k=44)
        result = compare(sample(), after)
        self.assertFalse(result['playerIdsEqual'])
        self.assertFalse(result['securityKeysEqual'])
        self.assertEqual(1, len(result['nativeIdentityDifferences']['p']))

    def test_boundary_edge_loss_is_visible(self):
        after = sample()
        after['edges'].clear()
        result = compare(sample(), after)
        self.assertFalse(result['edgeSetsEqual'])
        self.assertEqual(1, len(result['missingEdges']))

    def test_nonzero_extra_is_preserved_and_does_not_prove_zero(self):
        after = sample()
        bits = struct.unpack('>q', struct.pack('>d', 2.5))[0]
        after['grids'][0].update(extraRawBits=bits, extraZero=False, extraText='2.5')
        result = compare(sample(), after)
        self.assertFalse(result['extraZeroAtBothObservedInstants'])
        self.assertEqual(bits, result['liveExtra']['after'][0]['rawLong'])
        self.assertEqual('2.5', result['liveExtra']['after'][0]['value'])

    def test_unknown_coverage_is_not_empty_or_complete(self):
        after = sample()
        after.update(coverageComplete=False, unknowns=['unsupported fixture host'], unknownCount=1)
        result = compare(sample(), after)
        self.assertFalse(result['coverageComplete'])
        self.assertFalse(result['extraZeroAtBothObservedInstants'])
        after['coverageComplete'] = True
        with self.assertRaises(EvidenceError):
            compare(sample(), after)

    def test_empty_samples_do_not_prove_ae_coverage(self):
        empty = sample()
        empty.update(nodes=[], edges=[], grids=[], internalNodeCount=0, nodeCount=0, selectedGridCount=0)
        result = compare(empty, copy.deepcopy(empty))
        self.assertFalse(result['bothSamplesHaveInternalNodes'])
        self.assertFalse(result['extraZeroAtBothObservedInstants'])

    def test_duplicate_ports_and_cross_grid_edges_are_invalid(self):
        after = sample()
        after['nodes'][1]['key'] = after['nodes'][0]['key']
        with self.assertRaises(EvidenceError):
            compare(sample(), after)
        after = sample()
        after['nodes'][0]['observedGrid'] = 2
        with self.assertRaises(EvidenceError):
            compare(sample(), after)

    def test_raw_energy_bits_cannot_disagree_with_zero_flag(self):
        after = sample()
        after['grids'][0]['extraRawBits'] = struct.unpack('>q', struct.pack('>d', 1.0))[0]
        with self.assertRaises(EvidenceError):
            compare(sample(), after)

    def test_native_reader_roundtrip_preserves_raw_bits_and_text(self):
        value = sample()
        value['sampleLabel'] = 'native\0\ud800'
        value['grids'][0]['extraRawBits'] = -(1 << 63)  # Exact negative-zero bits, not a JSON float conversion.
        tag, body = native_tag(value)
        decoded = NbtReader(bytes([tag]) + b'\0\0' + body).root()
        self.assertEqual(value, decoded)
        result = compare(value, decoded)
        self.assertEqual('8000000000000000', result['liveExtra']['after'][0]['rawHex'])

    def test_unknown_native_tag_and_duplicate_native_keys_fail(self):
        with self.assertRaises(EvidenceError):
            NbtReader(b'\x0a\0\0\x0c\0\x01x').root()
        with self.assertRaises(EvidenceError):
            NbtReader(b'\x0a\0\0\x01\0\x01x\0\x01\0\x01x\0\0').root()

    def test_owned_native_relay_preserves_two_edges_and_does_not_prove_stability(self):
        value = relay_sample()
        result = compare(value, copy.deepcopy(value))
        ready = result['nativeReadiness']['after']
        self.assertTrue(result['edgeSetsEqual'])
        self.assertTrue(ready['necessaryConditionsAtSample'])
        self.assertFalse(ready['provesStableWindow'])
        self.assertFalse(result['authorizesPass'])
        self.assertFalse(value['nodes'][0]['quantumBridge']['clientLinkedCache'])

    def test_relay_is_not_persisted_proxy_and_location_is_not_owner_location(self):
        value = relay_sample()
        value['nodes'][2]['persistedPort'] = True
        with self.assertRaises(EvidenceError):
            compare(value, value)
        value = relay_sample()
        value['nodes'][2]['relayAnchorX'] = value['nodes'][0]['x']
        with self.assertRaises(EvidenceError):
            compare(value, value)

    def test_relay_flags_cannot_replace_a_missing_real_edge(self):
        value = relay_sample()
        value['edges'].pop()
        with self.assertRaises(EvidenceError):
            compare(value, value)

    def test_native_path_booting_is_not_ready(self):
        value = relay_sample()
        value['grids'][0]['networkBooting'] = True
        result = compare(value, value)
        self.assertFalse(result['nativeReadiness']['after']['necessaryConditionsAtSample'])
        self.assertEqual([1], result['nativeReadiness']['after']['bootingGrids'])

    def test_quantum_anchor_waiting_for_connection_is_not_native_readiness(self):
        value = relay_sample()
        value['nodes'].pop()
        value.update(nodeCount=2, internalNodeCount=1)
        value['grids'][0]['members'].pop()
        value['edges'].clear()
        value['nodes'][0]['quantumBridge'].update(linkedByExistingMembership=False, connectionPresent=False,
                                                  relayPresent=False, coreConnectionLive=False, relayPort='',
                                                  offlineReason='NETWORK_NOT_READY')
        result = compare(value, value)
        self.assertFalse(result['nativeReadiness']['after']['necessaryConditionsAtSample'])
        self.assertEqual(1, len(result['nativeReadiness']['after']['internalUnlinkedOrNotReady']))

    def test_relay_actor_change_remains_an_exact_security_difference(self):
        before = relay_sample()
        after = copy.deepcopy(before)
        after['nodes'][2].update(p=5, relayOwnerPlayerMatches=False)
        result = compare(before, after)
        self.assertFalse(result['playerIdsEqual'])
        self.assertFalse(result['nativeReadiness']['after']['necessaryConditionsAtSample'])
        self.assertEqual(['relative:quantum-runtime-relay'], result['nativeReadiness']['after']['relayActorMismatches'])

    def test_legacy_unknown_relay_rows_are_not_backfilled_by_new_schema(self):
        value = sample()
        value.update(unknowns=['unsupported proxy class ' + RELAY], unknownCount=1, coverageComplete=False)
        result = compare(value, value)
        self.assertFalse(result['coverageComplete'])
        self.assertFalse(result['nativeReadiness']['after']['available'])
        self.assertFalse(result['nativeReadiness']['after']['necessaryConditionsAtSample'])


if __name__ == '__main__':
    unittest.main()
