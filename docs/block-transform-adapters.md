# Blueprint block transforms

`HologramBlockTransformApi.register(Adapter)` is the public extension point for a mod integration that understands its own metadata and NBT schema. Registrations are tried newest first, before the built-in adapters, so a community integration can handle a custom multipart container instead of being rejected by the built-in FMP microblock adapter. The returned `AutoCloseable` removes that registration.

`accepts(State)` should match the actual target block class or a documented tile ID. The blueprint's source multiblock class is provenance and does not identify the target tile. Registry names and classes help select an adapter; they do not explain an arbitrary mod's orientation encoding.

`rotate(State, boolean vertical)` receives detached `metadata`, `tile`, and `item` values. It may mutate or replace those values. Preserve inventory, fluid, item identity, and configuration tags that are outside the adapter's understood directional schema. `accepts` gets a disposable copy, so mutations made while matching are discarded. Runtime and optional-class linkage failures are converted to `IOException` at the registry boundary.

The shared world-axis quarter turns are:

- Horizontal: `(x, y, z) -> (-z, y, x)`.
- Vertical: `(x, y, z) -> (x, -z, y)`.
- Points inside a 16-unit microtile cell rotate around its center: horizontal `(x, y, z) -> (16-z, y, x)`; vertical `(x, y, z) -> (x, 16-z, y)`.

Throw `IOException` when the target schema cannot express a turn. An adapter handles the entire matched block, including all parts in a multipart container; it must not claim a mixed container and transform only its own part. The server stages the complete preset and capture before replacing the live placement. A later rejection keeps all original cell coordinates and payloads. Normal bounds, permission, controller alignment, facing, and material checks still apply.

`HologramBlockTransformApi.rotate(...)` uses the same registry for native preview and test fixtures. Its full overload also accepts item NBT, original cell coordinates, and an optional LittleTiles reference map. The map uses `cellX,cellY,cellZ:minX,minY,minZ` as the key and the transformed referenced tile minimum as the value; it is copied before an external adapter receives it. A nonempty reference map describes one quarter turn. Rebuild it from the complete neighboring blueprint before the next quarter turn.

Built-in boundaries in v0.0.4:

| Family | Adapted directional data | Boundary |
| --- | --- | --- |
| GT5U | Machine alignment; pipe connection and fluid input masks; item-pipe incoming side; covers; sided and strong redstone | Pipe-specific same-name fields are interpreted only after checking the real metatile prototype. |
| AE2 | Forward/up basis; side-mounted definitions, extras and facades; reporting-part spin; captured cable connection/channel visual data; the three documented metadata-oriented blocks | Runtime network membership is recomputed by AE2. Native visual smoke is separate from NBT invariants. |
| EnderIO | Conduit connection lists, side arrays, filters/upgrades and documented enum-suffixed settings | Unknown third-party conduit classes are rejected. |
| ForgeMultipart | Native face, hollow, edge, corner and post microblock shape slots on both axes; AE2 cable-bus parts and their nested visual/side data | Other part IDs need a registered integration. |
| ArchitectureCraft | All 24 native shape side/turn bases | Local connection masks and offsets remain in their existing local basis. |
| CarpentersBlocks | Slabs and six-face plates on both axes; horizontal slope/stairs, gates, doors, hatch, garage door, beds, safes, levers, ladders, torches and collapsible corners; covers/dyes/overlays and chisel-side slots | Upright/heightfield schemas without a complete rolled orientation reject vertical turns. |
| LittleTiles | Voxel boxes, native cutout orientation/position/size, and complete captured fixed/chair/ladder/bed relationships | Door mechanics, legacy absolute relationships, unknown structures and references outside the captured blueprint are rejected. |

Unadapted ordinary blocks retain the prior metadata/NBT behavior. Unchanged payload strings remain byte-identical. Optional mod classes are accessed through documented NBT or guarded reflection, so a missing optional mod does not introduce an unhandled linkage failure.

In the schema-2 editor, a real `MetaPipeEntity` uses the complete horizontal state transform for a single-cell rotation. Ordinary GT machines keep their existing legal-front editing operation. Whole-blueprint placement turns use the full state transform for both machines and pipes.

Native component drop tags may encode dimensions and geometry, as LittleTiles microtile items do. After rotating a stored cell, the service stages a payable primary sample from the rotated native payload, preserves an existing exact match when possible, and updates stored item NBT plus indexed, coordinate, automatic, frozen and planned targets together. Inventory, fluid and energy costs retained in the primary tag must remain equal. A failed native cost reconstruction rejects the complete rotation before any live target map changes. Normal material validation then prices the rotated payload.

`HologramBlockTransformRegression` checks four-turn identities, directional masks and cover correspondence, payload preservation, public adapter priority, neighboring microtile references, and a late multi-cell rejection through the actual service transform. `HologramBlockTransformNativeSmoke.run()` reports only native APIs actually tested after FML initialization; absent mods are not reported as tested. Native rendering and world placement smoke remain required to assess geometry and placement beyond these serialization checks.
