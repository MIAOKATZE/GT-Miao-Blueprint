# Normal-JAR GUI release gate (reused v016 harness)

The current MUI2 migration reuses these files; the namespace is historical.
No runtime has been executed by editing or compiling this harness.

`runReleaseGuiBridgeTest` supplies four actual inputs: unchanged normal GTMB,
SRG Minecraft, Forge-patched Minecraft and normal MUI2 **2.3.92**. The fourth
argument selects a static bytecode ABI gate. It resolves the Screen inheritance
through actual `GuiScreenWrapper`, all nine concrete Host ports, and Minecraft /
MUI2 member references in the migrated Screens and workstation workflows. Forge's
unmapped extension signatures supplement the SRG contract without replacing it.
It rejects vanilla button/text-field allocations. No class is defined or stubbed
on this path. This is an ABI witness, not a class-loading or native-render PASS.
For the independent runner use `run_bridge.py --mui2 <normal-mui2.jar> ...`.

The same `V016ProductionGuiProbe.java` addon detects the real `IMuiScreen` route
and requires the loaded mod version to be `2.3.92-1.7.10`. It opens the actual
normal-JAR workstation at the main menu with a pure client snapshot, then observes
natural render generations. It checks the initialized actual Panel, real native
ButtonWidget/TextFieldWidget instances and their shared POCKET background objects.
It uses the real ModularScreen hit-test pipeline at an actual button rectangle
(not a direct widget callback), and verifies the real preview-view state changes.
This operation is instrumented screen input; it is not OS/native pointer input.

All four actual POCKET resources must decode and bind in the live GL context at
their original dimensions: panel 64x64, cloth 32x32, button/pressed 88x18.
The addon calls actual Minecraft resource reload, waits for four
later natural renders, and repeats the resource/binding checks with zero observed
GL errors. Before/after framebuffer PNGs and zero CutAction codec transmissions
are required. `run_native.py` enforces these additional fields whenever the MUI2
route is observed. This scene has **no world, player or backend business scope**.

Actual world/Netty naming and storage behavior belongs to the existing isolated
`HologramCutNativeClientProbe`, not to this menu addon. That probe now finds real
MUI2 buttons by stable semantic names and sends its existing target-window OS
messages to coordinates obtained from actual `LocatedWidget` viewport transforms.
For GUI controls it first posts a mouse move, waits for two natural frames and
requires the real MUI hover list to contain that same widget, then posts a separate
mouse down. Neither hover nor callbacks are forced by the probe. It covers Name Cancel and Escape with real
CutAction codec TX counters, normal Save followed by another prompt, explicit
Save-without-prompt followed by ordinary confirmation, independent storage raw
render sessions, and the existing placement/pose/undo flows. The actual raw
browser proofs retain complete sources, session identities and native GL counters.
Placement review separately checks Cancel/Confirm/Confirm-without-future-review,
zero CutAction packets on Cancel, an ordinary confirmation that keeps the prompt,
and the explicit durable placement preference. A final independent one-cell gold
fixture is paired with `HologramCutNativeServerProbe`: the first right press must
lock with no CutAction and unchanged disk/target, then the second right press must
send exactly one real paste without a dialog. Its earlier original-position undo
remains a genuine separate undo. CUT mode also checks Escape preserves the cache,
cached Shift-right placement, empty-cache Shift-right notice, ordinary-right list
access with empty or nonempty cache, and Shift-Alt-right new selection.

## Legacy v016 control

The following three-input behavior is retained only for historical direct-dispatch
and old-AME controls. It cannot validate the migrated MUI2 screen.

Preparation is not a runtime PASS. ROOT alone executes Java, Gradle and clients.
Neither harness reads launcher accounts or copies user worlds. The public release
version is taken from the built release, not from this internal harness namespace.

The target path comes from the real `ReobfuscatedJar.archiveFile` provider.
Without a fourth MUI2 argument, the probe loads the **original unchanged** normal-JAR Host and Screen in
an isolated class loader. It emits direct `invokeinterface` bytecode for every
abstract Host method, dispatches all nine, checks all five getter results and
requires exactly one call to each of the four real SRG/Forge base drawing methods.

Minecraft's actual `build/rfg/srg_merged_minecraft.jar` supplies the base field and
method names. The stable Forge `drawHoveringText` overload is read separately
from `build/rfg/recompiled_minecraft-1.7.10.jar` because vanilla's SRG merge
predates Forge patches. Drawing bodies are instrumented, GL-free fixtures; unused
dependencies are inert placeholders. Screen construction is bypassed with Unsafe.
This is a JVM ABI witness, not native GUI, state-construction or Minecraft proof.
The target Host/Screen bytes are never remapped, rewritten or replaced.

`run_bridge.py` compiles the observer separately with ASM 9.8 and binds input,
normal-class and process hashes. Use `--expect old-ame` for the unchanged v0.1.5
negative control and `--expect pass` for v0.1.6. A negative control is recorded as
`EXPECTED_OLD_ABSTRACT_METHOD_ERROR`, never as an engine PASS.

`V016ProductionGuiProbe.java` is a separate, test-only FML addon compiled by
`build_addon.py`; it contains no GTMB classes and links no MCP Minecraft methods.
It runs with actual production LaunchWrapper/FML (`fml.deobfuscatedEnvironment`
must be false), reflects exact SRG methods and creates the actual unchanged
normal-JAR `HologramState`/`HologramScreen` with an empty import snapshot. The
actual screen is installed through Minecraft's `func_147108_a`, initialized by
Minecraft, rendered naturally, and observed at RenderTick END. All nine actual
Host methods are also invoked in that real GL context. Four completed production
render generations, zero observed GL errors, exact Host/Screen normal-JAR origin,
and a framebuffer PNG are required. It exits only its own Minecraft process.

The native scene is deliberately a **main-menu empty-import Screen** without a
world. It exercises the failing GUI dispatch/render chain, initialization and
real font/background/tooltip methods; it does not retest integrated-world state,
blueprint business behavior, network/Netty or gameplay. The user's archived crash
is independent evidence of the integrated-world v0.1.5 failure. Do not relabel
this scene as a world or network PASS.

`prepare_packaged.py --profile v016-gui` clones a new workspace production fixture,
copies the selected unchanged normal JAR and addon into its mods, disables both
legacy packaged/production smoke hooks, and enables only this addon. The normal
JAR is not put on a development classpath. `run_native.py` preserves its process,
raw stdout, properties, crash hashes and exact input identity; it also verifies
both actual class-resource URLs point at the selected normal JAR before accepting
PASS or the expected old AbstractMethodError. Existing attempts are immutable.

Exact ROOT commands and known local paths are in
`plan/tmp/v0.1.6-gui-remap/probe-design-and-commands.md`.

Files to explicitly stage (some parents are ignored):

* `build.gradle.kts`
* `src/test/java/com/miaokatze/gtmb/client/hologram/HologramV016ReleaseGuiBridgeProbe.java`
* `tools/hologram/prepare_packaged.py`
* `tools/hologram/followup/v016/{README.md,run_bridge.py,build_addon.py,run_native.py,V016ProductionGuiProbe.java}`

The probe, addon and generated evidence must never be included in the release JAR.
