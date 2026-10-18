import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
        id("java-library")
        kotlin("jvm") version "2.3.20"
        // **The plugin's own id, as the Fabric example mod spells it** - the bare `fabric-loom`
        // spelling is the old marker.
        id("net.fabricmc.fabric-loom") version "1.19.1"
}

// **One version for both loaders.** `Neolectrum/gradle.properties` is the version of record for
// the mod (`build.gradle.kts` there stamps the NeoForge jar with it, `updates.json` is checked
// against it, and the CI tag assert reads it) - this module reads the same value out of the same
// file, so a release carries two jars that answer as one version and no bump can move one loader
// and forget the other.
val parentProperties = Properties().apply {
        rootProject.file("../Neolectrum/gradle.properties").inputStream().use { stream -> load(stream) }
}
val modVersion = parentProperties.getProperty("neoforge_mod_version")
val modId = "wgpu_mc"

group = parentProperties.getProperty("neoforge_maven_group", "dev.birb.wgpu")
version = modVersion

base { archivesName.set("$modId-fabric") }

java {
        // Minecraft 26.1 moved to Java 25 - the same floor the NeoForge build sets.
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
        withSourcesJar()
}

kotlin {
        compilerOptions {
                jvmTarget.set(JvmTarget.JVM_25)
        }
}

// NOTE: the toolchain above is the whole Java story - 26.1 needs Java 25 and the toolchain pins
// both the compiler and the loader's floor. (A second `java { sourceCompatibility }` block was
// here and went: the toolchain is what counts, and the accessor `java` shadows the `java` package
// at the top level of a Gradle Kotlin script - `java.util.Properties` above has to come in
// through an import for exactly that reason.)

// ---------------------------------------------------------------------------
// The shared sources, compiled against Fabric.
//
// Everything the renderer is lives in `../Neolectrum/src` and is loader-neutral: the mixins read
// Minecraft's own classes (26.1 is unobfuscated, so the names are the same names both loaders
// load), the Rust bridge talks JNI, the options screens draw with the game's own widgets. Four
// files are the exception and are excluded here, each replaced by its Fabric answer in this
// module's own sources:
//
//   WgpuMcMod.kt        - the NeoForge `@Mod` entry; this module ships the same class shape
//                         (the `LOGGER`/counter companion every mixin reads) without the entry.
//   WgpuMcModClient.kt  - the client entry; `WgpuMcFabric` does the same init against Fabric's
//                         entrypoints and event hooks.
//   EarlyWindow.kt      - NeoForge's early loading screen, disarmed and taken over; Fabric has
//                         no FML early display, so there is nothing to disarm and the two mixins
//                         that drive it (`core.EarlyWindowMixin`, `render.WindowMixin`) are out
//                         of this module's mixin list for the same reason - `WindowMixin`'s
//                         redirect targets a call site only NeoForge's Window has, and a mixin
//                         that cannot find its target is a crash at apply time.
// ---------------------------------------------------------------------------
sourceSets.main {
        java.srcDirs("../Neolectrum/src/main/java")
        kotlin.srcDirs("../Neolectrum/src/main/kotlin")
        resources.srcDir("../Neolectrum/src/main/resources")

        java.exclude(
                "dev/birb/wgpu/mixin/render/WindowMixin.java",
                "dev/birb/wgpu/mixin/core/EarlyWindowMixin.java",
        )
        kotlin.exclude(
                "dev/birb/wgpu/WgpuMcMod.kt",
                "dev/birb/wgpu/WgpuMcModClient.kt",
                "dev/birb/wgpu/backend/EarlyWindow.kt",
        )
        resources.exclude(
                "META-INF/neoforge.mods.toml",
                "META-INF/accesstransformer.cfg",
                "assets/electrum/**",
                "**/*.bbmodel",
                "**/.cache",
        )
}

// The module's own `wgpu_mc.mixins.json` (same name, this module's srcDir is searched first) is
// what ships, so the shared one never wins the duplicate by accident either.
tasks.processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        val properties = mapOf(
                "version" to modVersion,
                "mod_id" to modId,
        )
        inputs.properties(properties)
        filesMatching("fabric.mod.json") {
                expand(properties)
        }
}

// **The natives, from the build that already packaged them.** The NeoForge build's `copyNatives`
// task lifts the Rust engine's libraries into `Neolectrum/build/generated/natives`, laid out
// exactly as a jar carries them (`assets/wgpu_mc/natives/<abi>/libwgpu_mc_jni.so`), and this
// module ships the same libraries the same way - so the Fabric build runs *after* the NeoForge
// one (the CI order) and adds that directory as a resource root when it exists. A build without
// it - a scaffold compile, a desktop-only build - produces a natives-less jar, which is what the
// CI jar check on the NeoForge side guards for both.
val packagedNatives = rootProject.file("../Neolectrum/build/generated/natives")
if (packagedNatives.isDirectory) {
        sourceSets.main {
                resources.srcDir(packagedNatives)
        }
} else {
        logger.warn(
                "wgpu-mc: {} does not exist yet, so this Fabric jar carries no natives - build the " +
                        "NeoForge jar first (its copyNatives packages what both loaders ship), or point " +
                        "this build at the engine checkout itself",
                packagedNatives.path,
        )
}

dependencies {
        minecraft("com.mojang:minecraft:26.1.2")
        // **26.1 is unobfuscated, so there is no mappings line here at all** - Mojang stopped
        // shipping them, which is exactly what the NeoForge build's gradle.properties has said
        // since 26.1 ("no mappings/parchment entries are needed anymore"). The same is true of
        // the intermediary the old layered path used to fall back on: gone from the maven, a
        // `0.0.0` placeholder where a build used to read one. This is also the recipe the Fabric
        // example mod itself carries for these versions: no `mappings(...)`, and the loader and
        // API arrive as plain `implementation`, because there is nothing left to remap - the
        // game's own names are the compile-time names, on both loaders.
        implementation("net.fabricmc:fabric-loader:0.19.5")
        implementation("net.fabricmc.fabric-api:fabric-api:0.155.3+26.1.2")

        // The mod is written in Kotlin and nothing on a player's Fabric classpath brings the
        // runtime: fabric-language-kotlin is the language adapter for Kotlin *entrypoints*, which
        // this mod does not use (its entrypoint is one object the loader reflects on), but the
        // Kotlin *classes* - every mixin and every call they make - need the stdlib on the
        // classpath before the first one loads. `include` is Fabric's jar-in-jar, the same shape
        // the NeoForge build's jarJar embeds.
        include("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
}
