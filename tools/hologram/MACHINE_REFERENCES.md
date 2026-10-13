# Native machine references

This optional tool makes visual references using GT5U's actual registered world renderers. It is independent of all hologram smoke suites and does not alter their assertions. Both helper classes live in the test source set; the two runtime entry points only load them when the dedicated reference property is enabled.

Run from the main checkout, using the same explicit support-jar directory accepted by `prepare_smoke.py`:

```powershell
python tools/hologram/prepare_smoke.py --mode reference --mods-source <support-jar-directory>
./gradlew.bat runServer -PhologramReferenceServer
python tools/hologram/prepare_client.py --mode reference
./gradlew.bat runClient -PhologramReferenceClient
```

The server uses a fresh empty `hologram-reference-*` world on localhost port 25582 under `build/hologram-reference/server`. It finds the controller by its exact registered class, captures its real StructureLib declaration, invokes its native `construct`, and substitutes service hatches only where the actual element declares that hatch. Six service hatches provide input/output buses, input/output fluid hatches, energy, and maintenance. Applicable machines also have an upward-facing muffler. The EBF muffler occupies the mandatory top-center element. Hatches use their actual machine casing texture after the native formation check.

Each machine must pass `checkStructure(true, base)` in the real server world before its full block metadata and native tile NBT are serialized. A failed machine prevents a server final PASS. No render snapshot is presented as formed without that check. The LCR constructor chooses exactly one heating coil, and service substitutions preserve it.

The current GT5U 5.09.54.205 controllers are:

| Reference | Registered class | Controller ID | Full size |
| --- | --- | --- | --- |
| electric-blast-furnace | `gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace` | 1000 | 3 × 4 × 3 |
| industrial-electrolyzer | `gregtech.common.tileentities.machines.multi.MTEIndustrialElectrolyzer` | 15514 | 5 × 5 × 5 |
| large-chemical-reactor | `gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor` | 1169 | 3 × 3 × 3 |
| industrial-centrifuge | `gregtech.common.tileentities.machines.multi.MTEIndustrialCentrifuge` | 15512 | 5 × 5 × 5 |

The client reads the server snapshots into a detached read-only native world, creates fresh native tiles from saved NBT, and calls `RenderBlocks.renderBlockByRenderType`. GT hatches, controllers, casings, coils, pipe casings, and frame boxes therefore use their registered renderers, actual textures, facing, and saved state. There is no schematic cube/material fallback, live-world connection, machine ticking, or invented block geometry.

Sixteen PNGs appear under `build/hologram-reference/client/screenshots`: each machine gets `front-left`, `front-right`, `rear-left`, and `cutaway`. The first three views contain the complete formed structure. The fourth explicitly hides the front block plane in the detached render world to expose internal materials; it is labelled CUTAWAY and must not replace a complete-assembly reference. Machine renders must span at least 600 screenshot pixels in height, contain at least 30,000 visible material pixels, and retain at least 100 distinct material colors. The client also verifies actual native triangle emission and restored registered machine IDs.

Use the complete front views as image-generation references. Captions are reference annotations, not requested banner text. The LCR has one PTFE pipe casing and one heating coil, no glass or muffler; the electrolyzer has Potin frames and brass/tin pipe casings; the centrifuge has Eglin Steel frames and sieve grates. Additional decorative blocks would no longer match these structures.

Require `[GTMB-MACHINE-REFERENCE] FINAL PASS machines=4` and `[GTMB-MACHINE-REFERENCE-CLIENT] FINAL PASS screenshots=16` in the logs. A failed fixture can shut down Minecraft while Gradle still exits successfully; the Gradle result alone does not verify the references.
