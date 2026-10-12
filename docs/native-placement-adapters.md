# Native blueprint placement adapters

`HologramNativePlacement.register(id, adapter)` registers an explicit persistence/material provider. The newest matching provider takes precedence, including over the built-in six mod integrations. The same registry is used for area capture, material inspection, construction and rollback.

`Adapter.create` must create a fresh detached tile from the selected NBT and reject missing parts, materials and unrepresentable state. It receives an isolated read-only world; it must never reuse a live tile, tick, harvest, remove inventory, join a real network or change another cell. Factory or material callback failures preserve the draft and reject construction.

`Adapter.materials` must return all physical part and cover materials, with exact item NBT and quantities. A component without a material must be rejected. The first material supplies the captured primary sample; normal construction pays one primary item, and the resource plan pays every remaining native item. Filters, upgrades and microblock dimensions must be included where they are physical items. Inventories, fluids, energy and projector recovery seals retain the normal resource and ownership rules.

`includesInventoryDrops` defaults to false. Return true only when the native material callback already includes every physical inventory item in the selected payload. Matching inventory rows are then paid once through the native plan; fluid and energy costs remain independent. Do not set it for callbacks that return only a shell or selected drops.

The built-in providers reject complete composite restoration when `retainNbt=false`. Selecting only part of a required structure payload must remain representable by the native factory; unknown part IDs and unknown material references must never be replaced with defaults or silently discarded.

Native block placement runs inside the service's normal material transaction. Forge place cancellation and failed construction restore the previous block/tile and refund all reserved material. Native inspection does not authorize removal of an existing device: the existing recovery policy still protects unsupported recovery tiles.

The extended server suite `HologramNativePlacementSmoke.run(WorldServer)` requires all six installed mods. It invokes the production service transaction with real registered blocks, tiles, multipart parts and material items, and verifies rotated construction, insufficient material, place-event cancellation, source preservation and multipart rollback.
