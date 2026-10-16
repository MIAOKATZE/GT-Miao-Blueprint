# v016 normal-JAR GUI release gate

Preparation is not a runtime PASS. ROOT alone executes Java, Gradle and clients.
Neither harness reads launcher accounts or copies user worlds. The public release
version is taken from the built release, not from this internal harness namespace.

`check` now includes `runReleaseGuiBridgeTest`, depending on `reobfJar` and
`compileTestJava`. The target path comes from the real `ReobfuscatedJar.archiveFile`
provider. The probe loads the **original unchanged** normal-JAR Host and Screen in
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
