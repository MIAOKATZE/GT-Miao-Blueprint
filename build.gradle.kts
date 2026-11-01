plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// Blueprint metadata needs the release version independently of the Git-derived build version.
extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
    injectedTags.put("RELEASE_VERSION", providers.gradleProperty("RELEASE_VERSION"))
}

// 手写回归由 JavaExec 运行，不通过 JUnit 发现。
tasks.test {
    failOnNoDiscoveredTests = false
}

val hologramTestClasspath = sourceSets.getByName("test").runtimeClasspath +
    configurations.detachedConfiguration(
        dependencies.create("com.github.GTNewHorizons:NotEnoughItems:2.8.171-GTNH:dev"),
        dependencies.create("com.github.GTNewHorizons:CodeChickenCore:1.4.22:dev"),
        dependencies.create("com.github.GTNewHorizons:ForgeMultipart:1.7.18:dev"),
        dependencies.create("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209"),
        dependencies.create("org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209"),
        dependencies.create("it.unimi.dsi:fastutil:8.5.18"),
        dependencies.create("com.github.GTNewHorizons:waila:1.19.34:dev"),
        dependencies.create("curse.maven:ic2-242638:2353971"),
        dependencies.create("org.joml:joml:1.10.8"),
        dependencies.create("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:1.3.5:downgraded-8"),
        dependencies.create("com.github.GTNewHorizons:ForestryMC:4.11.40:dev"),
        dependencies.create("com.github.GTNewHorizons:Nuclear-Control:2.7.17:dev"),
        dependencies.create("curse.maven:minefactory-reloaded-66672:2366150")
    ).apply { isTransitive = false }

tasks.register<JavaExec>("runHologramTest") {
    group = "verification"
    description = "Runs independent projector, blueprint, resources, rotation, rendering and packet regressions."
    mainClass = "com.miaokatze.gtmb.hologram.HologramRegressionTest"
    // GT's modern interface fixture needs ASM newer than Forge's bundled ASM 5.
    classpath = configurations.detachedConfiguration(dependencies.create("org.ow2.asm:asm:9.8")) + hologramTestClasspath
    val scratchDir = layout.buildDirectory.dir("hologram-test")
    workingDir(scratchDir)
    doFirst { scratchDir.get().asFile.mkdirs() }
}

tasks.named("check") { dependsOn("runHologramTest") }

tasks.register<JavaExec>("runSnapshotAeTaskRegression") {
    group = "verification"
    description = "Checks P0a complete AE reports and real test-instrumented decoder calls in an isolated JVM."
    dependsOn("compileTestJava")
    mainClass = "com.miaokatze.gtmb.hologram.HologramSnapshotAeTaskRegression"
    classpath = tasks.named<JavaExec>("runHologramTest").get().classpath
    val scratchDir = layout.buildDirectory.dir("hologram-ae-task-test")
    workingDir(scratchDir)
    doFirst { scratchDir.get().asFile.mkdirs() }
}

tasks.named("check") { dependsOn("runSnapshotAeTaskRegression") }

tasks.register<JavaExec>("runEmptyPayloadRegression") {
    group = "verification"
    description = "Checks task-local empty payload facts, shared budgets and actual strict decoder counts."
    dependsOn("compileTestJava")
    mainClass = "com.miaokatze.gtmb.hologram.HologramEmptyPayloadRegression"
    classpath = tasks.named<JavaExec>("runHologramTest").get().classpath
    val scratchDir = layout.buildDirectory.dir("hologram-empty-payload-test")
    workingDir(scratchDir)
    doFirst { scratchDir.get().asFile.mkdirs() }
}

tasks.named("check") { dependsOn("runEmptyPayloadRegression") }

tasks.register<JavaExec>("runPreviewUvRegression") {
    group = "verification"
    description = "Checks live submission UV snapshots against original getters, including actual getter counts."
    dependsOn("compileTestJava")
    mainClass = "com.miaokatze.gtmb.client.hologram.HologramPreviewUvRegression"
    classpath = tasks.named<JavaExec>("runHologramTest").get().classpath
    val scratchDir = layout.buildDirectory.dir("hologram-preview-uv-test")
    workingDir(scratchDir)
    doFirst { scratchDir.get().asFile.mkdirs() }
}

tasks.named("check") { dependsOn("runPreviewUvRegression") }

tasks.register<JavaExec>("runBlueprintValidationBenchmark") {
    group = "verification"
    description = "Measures production validation reuse at 50k, 100k and 1m streamed cells; not a live-world benchmark."
    dependsOn("compileTestJava")
    mainClass = "com.miaokatze.gtmb.hologram.HologramBlueprintValidationCacheRegression"
    classpath = tasks.named<JavaExec>("runHologramTest").get().classpath
    maxHeapSize = "4g"
    val scratchDir = layout.buildDirectory.dir("hologram-validation-benchmark")
    workingDir(scratchDir)
    doFirst { scratchDir.get().asFile.mkdirs() }
}

// The common service and native adapters need more heap for Checkstyle's complete syntax trees.
tasks.withType<org.gradle.api.plugins.quality.Checkstyle>().configureEach {
    maxHeapSize = "2g"
}

// Exercise the shipped SRG ABI: development classpaths cannot witness inherited MCP-name gaps.
abstract class ReleaseGuiBridgeArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val srgRuntime: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val forgeRuntime: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val muiRuntime: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf(
        normalJar.get().asFile.absolutePath,
        srgRuntime.get().asFile.absolutePath,
        forgeRuntime.get().asFile.absolutePath,
        muiRuntime.get().asFile.absolutePath
    )
}

val releaseGuiBridgeAsm = configurations.detachedConfiguration(dependencies.create("org.ow2.asm:asm:9.8"))
val releaseGuiMuiRuntime = configurations.detachedConfiguration(
    dependencies.create("com.github.GTNewHorizons:ModularUI2:2.3.94-1.7.10")
).apply { isTransitive = false }
val releaseGuiNormalJar = tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar").flatMap { it.archiveFile }
val releaseGuiSrgRuntime = layout.buildDirectory.file("rfg/srg_merged_minecraft.jar")
val releaseGuiForgeRuntime = layout.buildDirectory.file("rfg/recompiled_minecraft-1.7.10.jar")
tasks.register<JavaExec>("runReleaseGuiBridgeTest") {
    group = "verification"
    description = "Verifies the normal projector UI ABI against the pinned normal MUI2 and SRG runtimes."
    dependsOn("reobfJar", "compileTestJava")
    mainClass = "com.miaokatze.gtmb.client.hologram.HologramV016ReleaseGuiBridgeProbe"
    // No main output/development engine is available to the isolated target loader.
    classpath = sourceSets.getByName("test").output + releaseGuiBridgeAsm
    argumentProviders.add(objects.newInstance(ReleaseGuiBridgeArguments::class.java).apply {
        normalJar.set(releaseGuiNormalJar)
        srgRuntime.set(releaseGuiSrgRuntime)
        forgeRuntime.set(releaseGuiForgeRuntime)
        muiRuntime.set(layout.file(providers.provider { releaseGuiMuiRuntime.singleFile }))
    })
}
tasks.named("check") { dependsOn("runReleaseGuiBridgeTest") }

val hologramCoexistence = providers.gradleProperty("hologramCoexistence").isPresent
val hologramExtendedCompatibility = providers.gradleProperty("hologramExtendedCompatibility").isPresent
val hologramAeDeepCompatibility = providers.gradleProperty("hologramAeDeepCompatibility").isPresent
if (hologramAeDeepCompatibility) {
    require(listOf("hologramSmoke", "hologramCoexistence", "hologramClientSmoke", "hologramInteractionServer", "hologramInteractionClient")
        .any { providers.gradleProperty(it).isPresent }) {
        "-PhologramAeDeepCompatibility requires a hologram smoke/interaction run property."
    }
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        if (providers.gradleProperty("hologramClientSmoke").isPresent)
            extraRunJvmArguments.add("-Dgtmb.hologram.aeClientSmoke=true")
        if (providers.gradleProperty("hologramInteractionServer").isPresent || providers.gradleProperty("hologramInteractionClient").isPresent)
            extraRunJvmArguments.add("-Dgtmb.hologram.aeRealInteractionSmoke=true")
    }
}
if (hologramExtendedCompatibility) {
    require(listOf(
        "hologramSmoke", "hologramCompatibilitySmoke", "hologramCoexistence", "hologramClientSmoke",
        "hologramInteractionServer", "hologramInteractionClient"
    ).any { providers.gradleProperty(it).isPresent }) {
        "-PhologramExtendedCompatibility requires a hologram smoke/interaction run property."
    }
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add("-Dgtmb.hologram.extendedCompatibilitySmoke=true")
    }
}
val smokeRoot = layout.buildDirectory.dir(if (hologramCoexistence) "hologram-coexistence/smoke" else "hologram-smoke")
val interactionRoot = layout.buildDirectory.dir(if (hologramCoexistence) "hologram-coexistence/interaction" else "hologram-interaction")
if (providers.gradleProperty("hologramLibrarySocketSmoke").isPresent) {
    require(providers.gradleProperty("hologramInteractionServer").isPresent ||
        providers.gradleProperty("hologramInteractionClient").isPresent) {
        "-PhologramLibrarySocketSmoke requires an interaction server or client run."
    }
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add("-Dgtmb.hologram.librarySocketSmoke=true")
        extraRunJvmArguments.add("-Dgtmb.hologram.libraryReceiptDir=" + interactionRoot.get().dir("receipts").asFile.absolutePath)
    }
}
val hologramReferenceServer = providers.gradleProperty("hologramReferenceServer").isPresent
val hologramReferenceClient = providers.gradleProperty("hologramReferenceClient").isPresent
val referenceRoot = layout.buildDirectory.dir("hologram-reference")
if (hologramReferenceServer || hologramReferenceClient) {
    require(!(hologramReferenceServer && hologramReferenceClient) && !hologramCoexistence &&
        !hologramExtendedCompatibility && !hologramAeDeepCompatibility &&
        listOf("hologramSmoke", "hologramCompatibilitySmoke", "hologramClientSmoke", "hologramInteractionServer",
            "hologramInteractionClient").none { providers.gradleProperty(it).isPresent }) {
        "Machine reference capture must run alone in its dedicated profile."
    }
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add(if (hologramReferenceServer) "-Dgtmb.hologram.referenceServer=true"
            else "-Dgtmb.hologram.referenceClient=true")
        extraRunJvmArguments.add("-Dgtmb.hologram.referenceStateDir=" + referenceRoot.get().dir("server").asFile.absolutePath)
    }
    tasks.named<JavaExec>(if (hologramReferenceServer) "runServer" else "runClient") {
        dependsOn("compileTestJava")
        classpath += sourceSets["test"].output
        workingDir(referenceRoot.map { it.dir(if (hologramReferenceServer) "server" else "client") })
        if (hologramReferenceClient) args("--width", "1400", "--height", "1100")
    }
}

if (hologramCoexistence) {
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add("-Dgtmb.hologram.coexistSmoke=true")
    }
}

if (providers.gradleProperty("hologramSmoke").isPresent ||
    hologramCoexistence && !providers.gradleProperty("hologramInteractionServer").isPresent &&
    !providers.gradleProperty("hologramInteractionClient").isPresent &&
    !providers.gradleProperty("hologramClientSmoke").isPresent) {
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add("-Dgtmb.hologram.smoketest=true")
    }
    tasks.named<JavaExec>("runServer") {
        dependsOn("compileTestJava")
        classpath += sourceSets["test"].output
        workingDir(smokeRoot.map { it.dir("server") })
    }
}

if (providers.gradleProperty("hologramCompatibilitySmoke").isPresent) {
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add("-Dgtmb.hologram.compatibilitySmoke=true")
    }
    tasks.named<JavaExec>("runServer") {
        workingDir(layout.buildDirectory.dir("hologram-compat/server"))
    }
}

if (providers.gradleProperty("hologramClientSmoke").isPresent) {
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add("-Dgtmb.hologram.clientSmoke=true")
        extraRunJvmArguments.add("-Dgtmb.hologram.clientState=" + smokeRoot.get().file("server/hologram-client-state.nbt").asFile.absolutePath)
    }
    tasks.named<JavaExec>("runClient") {
        if (hologramAeDeepCompatibility) {
            dependsOn("compileTestJava")
            classpath += sourceSets["test"].output
        }
        workingDir(smokeRoot.map { it.dir("client") })
        args("--width", "1100", "--height", "800")
    }
}

if (providers.gradleProperty("hologramInteractionServer").isPresent) {
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add("-Dgtmb.hologram.interactionServer=true")
        extraRunJvmArguments.add("-Dgtmb.hologram.interactionReceiptDir=" + interactionRoot.get().dir("receipts").asFile.absolutePath)
    }
    tasks.named<JavaExec>("runServer") {
        workingDir(interactionRoot.map { it.dir("server") })
    }
}

if (providers.gradleProperty("hologramInteractionClient").isPresent) {
    extensions.configure<com.gtnewhorizons.retrofuturagradle.MinecraftExtension>("minecraft") {
        extraRunJvmArguments.add("-Dgtmb.hologram.realInteractionSmoke=true")
        extraRunJvmArguments.add("-Dgtmb.hologram.renderDiagnostics=true")
        extraRunJvmArguments.add("-Dgtmb.hologram.interactionAddress=127.0.0.1:25581")
        extraRunJvmArguments.add("-Dgtmb.hologram.interactionReceiptDir=" + interactionRoot.get().dir("receipts").asFile.absolutePath)
    }
    tasks.named<JavaExec>("runClient") {
        workingDir(interactionRoot.map { it.dir("client") })
        args("--width", "1280", "--height", "800")
    }
}
