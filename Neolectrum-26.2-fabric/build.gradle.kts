import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
        id("java-library")
        kotlin("jvm") version "2.3.20"
        id("net.fabricmc.fabric-loom") version "1.19.1"
}

// **One version per Minecraft line, one module per loader.** This is the Fabric build of the 26.2
// sibling: it compiles the shared tree in `../Neolectrum/src` (the same tree the 26.1 builds read),
// the 26.2 overlay in `../Neolectrum-26.2/src` (the files 26.2's Minecraft broke, answered there
// once for both loaders), and this module's own Fabric answers - the entrypoint, the access
// widener and this fabric.mod.json, which names `~26.2` where the 26.1 module's names `~26.1.2`.
val parentProperties = Properties().apply {
        rootProject.file("../Neolectrum-26.2/gradle.properties").inputStream().use { stream -> load(stream) }
}
val modVersion = parentProperties.getProperty("neoforge_mod_version")
val modId = "wgpu_mc"

group = parentProperties.getProperty("neoforge_maven_group", "dev.birb.wgpu")
version = modVersion

base { archivesName.set("$modId-fabric") }

java {
        // Minecraft 26.2 needs Java 25, the same floor the NeoForge build sets.
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

kotlin {
        compilerOptions {
                jvmTarget.set(JvmTarget.JVM_25)
        }
}

loom {
        accessWidenerPath = file("src/main/resources/wgpu_mc.accesswidener")

        mods {
                create(modId) {
                        sourceSet(sourceSets.main.get())
                }
        }
}

sourceSets.main {
        java.srcDirs("../Neolectrum/src/main/java", "../Neolectrum-26.2/src/main/java")
        kotlin.srcDirs("../Neolectrum/src/main/kotlin", "../Neolectrum-26.2/src/main/kotlin")
        resources.srcDirs("../Neolectrum/src/main/resources", "../Neolectrum-26.2/src/main/resources")

        // The loader seams the 26.1 fabric module names, unchanged: the NeoForge entrypoints and
        // early window, and the five files whose job touches the game's NeoForge-patched surface.
        // (26.2 merges `TextureFormat` into `GpuFormat`, so the format map's stencil seam is gone;
        // the file this module answers now is the block cache, same as 26.1.)
        java.exclude(
                "dev/birb/wgpu/mixin/render/WindowMixin.java",
                "dev/birb/wgpu/mixin/core/EarlyWindowMixin.java",
        )
        kotlin.exclude(
                "dev/birb/wgpu/WgpuMcMod.kt",
                "dev/birb/wgpu/WgpuMcModClient.kt",
                "dev/birb/wgpu/backend/EarlyWindow.kt",
                "dev/birb/wgpu/backend/WgpuCommandEncoder.kt",
                "dev/birb/wgpu/backend/WgpuFormat.kt",
                "dev/birb/wgpu/render/TintProfile.kt",
                "dev/birb/wgpu/render/Wgpu.kt",
                "dev/birb/wgpu/BlockCache.kt",
                // The 26.2 overlay's NeoForge-flavoured copy of the same file - this module's own
                // `BlockCacheFabric.kt` under `src/main` here is the 26.2 answer on this loader.
                "dev/birb/wgpu/BlockCache.kt",
        )
        resources.exclude(
                "**/META-INF/neoforge.mods.toml",
                "**/META-INF/accesstransformer.cfg",
                "**/wgpu_mc.mixins.json.bak",
                "**/assets/electrum/**",
                "**/*.bbmodel",
                "**/.cache",
        )
}

tasks.processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        val properties = mapOf(
                "version" to modVersion,
        )
        inputs.properties(properties)
        filesMatching("fabric.mod.json") {
                expand(properties)
        }
}

// **The natives, from the build that already packaged them** - the same handoff the 26.1 module
// describes: the NeoForge build's `copyNatives` lays the libraries out in
// `../Neolectrum-26.2/build/generated/natives`, and this module ships the same files the same way.
val packagedNatives = rootProject.file("../Neolectrum-26.2/build/generated/natives")
if (packagedNatives.isDirectory) {
        sourceSets.main {
                resources.srcDir(packagedNatives)
        }
} else {
        logger.warn(
                "wgpu-mc: {} does not exist yet, so this Fabric jar carries no natives - build the " +
                        "NeoForge jar first (its copyNatives packages what both loaders ship)",
                packagedNatives.path,
        )
}

dependencies {
        minecraft("com.mojang:minecraft:26.2")
        // 26.2 is unobfuscated like 26.1 - no mappings line, and the loader and API arrive as
        // plain `implementation` for the same reason: there is nothing left to remap.
        implementation("net.fabricmc:fabric-loader:0.20.1")
        implementation("net.fabricmc:fabric-api:0.161.0+26.2")

        include("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
}
