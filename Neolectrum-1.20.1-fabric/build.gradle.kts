import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
        id("java-library")
        kotlin("jvm") version "2.3.20"
        // **A loom line that still carries the mapping pipeline, under the plugin id of its
        // era.** The 26.x builds ride loom 1.19, which dropped `mappings` and the `mod*`
        // configurations outright - Mojang stopped shipping mappings, so 1.19 has nothing to
        // remap and no longer knows how. 1.20.1 is obfuscated, so this build pins the newest
        // loom line that still remaps: 1.10, the line every 1.20.1 mod was built on. The plugin
        // marker `net.fabricmc.fabric-loom` only exists from 1.14.0-alpha.32 on - 1.10 publishes
        // under the bare `fabric-loom` spelling the 26.x comment calls the old marker. It asks
        // for Gradle 8.x (this module's wrapper pins 8.14, not the 9.8 the 26.x modules run),
        // and it runs on a Java 21 JVM happily.
        id("fabric-loom") version "1.10.5"
}

// **One version per Minecraft line, one module per loader.** This is the Fabric build of the
// 1.20.1 line. The version lives in the line's Forge tree (`Neolectrum-1.20.1/gradle.properties`
// - Forge, because 1.20.1 has no NeoForge; the property name is the line's version-of-record
// either way), so one bump covers both loaders and the release's tag assert stays the single gate
// for every jar.
val parentProperties = Properties().apply {
        rootProject.file("../Neolectrum-1.20.1/gradle.properties").inputStream().use { stream -> load(stream) }
}
val modVersion = parentProperties.getProperty("neoforge_mod_version")
val modId = "wgpu_mc"

group = parentProperties.getProperty("neoforge_maven_group", "dev.birb.wgpu")
version = modVersion

base { archivesName.set("$modId-fabric") }

java {
        // **Java 17, the floor 1.20.1 launches on.** The 26.x lines compile against 25 because
        // the game asks for it; 1.20.1 is a Java 17 game and a 1.20.1 launcher may hand the game
        // exactly that, so this line's bytecode must load on it. The settings script carries the
        // toolchain resolver, so the 17 this pins is downloaded when the host JVM is newer.
        toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin {
        compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
        }
}

loom {
        accessWidenerPath = file("src/main/resources/wgpu_mc.accesswidener")

        // **The refmap is loom's job, not the annotation processor's.** The legacy AP converts
        // loom's three-column mapping (official -> intermediary -> named) into the two-column
        // table it reads targets from, and on this build that conversion came out *empty* - 87
        // bytes of header - after which every target in every mixin failed with "Unable to locate
        // obfuscation mapping", all 92 of them, including methods the jar provably carries. With
        // the AP off, loom generates the refmap itself at remap time (MixinRefmapService reads
        // the compiled classes and the same mapping the jar is remapped with), which is the path
        // the modern lines take and the one that cannot silently half-convert.
        mixin {
                // The refmap's name is the mixin config's own "refmap" key here, not this
                // extension: `defaultRefmapName` is an annotation-processor-era setting and
                // loom 1.10 refuses it while the AP is off. The service reads the key out of
                // the config at remap time, writes the file beside it and re-injects the
                // reference into the packaged config.
                useLegacyMixinAp = true
        }

        mods {
                create(modId) {
                        sourceSet(sourceSets.main.get())
                }
        }
}

// **A different shape from the 26.x modules, and that is the point.** The 26.x lines compile the
// shared tree and answer the smaller part; the 1.20.1 line is built the other way round, because
// the two architectures are not the same architecture. On 26.x the mod *is* the game's GpuDevice
// - Minecraft renders through a device this mod implements - and the shared tree is written
// against that contract. 1.20.1 has no such seam: Blaze3D is GL, so the mod takes the GL seam
// instead, the architecture the 1.21.1 backport proved - the game renders its own meshes, and
// every GL call it makes is answered by this mod out of wgpu. That body lives in
// `../Neolectrum-1.20.1/src` (the line's overlay tree, shared by both loaders verbatim), and what
// this build reads out of the 26.x shared tree is the three files whose job is loader- and
// version-neutral: the native library loader with its Android extraction hardening, the runtime
// it runs on, and the settings record both share.
//
// The overlay's own `WindowMixin` is the Forge-flavoured one - it injects where Forge's patched
// `Window` constructor hands creation to `ImmediateWindowHandler` - so this module compiles its
// own copy instead, injected before the `glfwCreateWindow` call the vanilla constructor makes.
// Everything else in the overlay is both loaders' verbatim.
val syncSharedKotlin = tasks.register<Sync>("syncSharedKotlin") {
        from("../Neolectrum/src/main/kotlin") {
                include(
                        "dev/birb/wgpu/AndroidRuntime.kt",
                        "dev/birb/wgpu/rust/WgpuNative.kt",
                        "dev/birb/wgpu/rust/RendererSettings.kt",
                )
        }
        into(layout.buildDirectory.dir("generated/shared-kotlin"))
}

val syncOverlayKotlin = tasks.register<Sync>("syncOverlayKotlin") {
        from("../Neolectrum-1.20.1/src/main/kotlin") {
                exclude(
                        // the two NeoForge `@Mod` entries - this module answers them with its own
                        // `WgpuMcFabric` entrypoint, the way every line's Fabric build answers the
                        // loader seams
                        "dev/birb/wgpu/WgpuMcMod.kt",
                        "dev/birb/wgpu/WgpuMcModClient.kt",
                )
        }
        into(layout.buildDirectory.dir("generated/overlay-kotlin"))
}

val syncOverlayJava = tasks.register<Sync>("syncOverlayJava") {
        from("../Neolectrum-1.20.1/src/main/java")
        into(layout.buildDirectory.dir("generated/overlay-java"))
}

sourceSets.main {
        java.srcDirs(
                syncOverlayJava.map { it.destinationDir },
        )
        kotlin.srcDirs(
                syncSharedKotlin.map { it.destinationDir },
                syncOverlayKotlin.map { it.destinationDir },
                "src/main/kotlin",
        )
        resources.srcDirs("../Neolectrum-1.20.1/src/main/resources")

        tasks.named("processResources") {
                dependsOn(syncSharedKotlin, syncOverlayKotlin, syncOverlayJava)
        }
        resources.exclude(
                // the Forge build's mod file - 1.20.1 Forge spells it `mods.toml` - and the
                // transformer list only the Forge loader applies
                "**/META-INF/mods.toml",
                "**/META-INF/accesstransformer.cfg",
        )
}

// **The mapping the mixin annotation processor reads, completed and converted here.** Two things
// are wrong with the file loom hands the AP, and both cost a day to find:
//
// 1. The AP's own conversion of the three-column shape comes out empty - 87 bytes of header -
//    after which every target in every mixin fails with "Unable to locate obfuscation mapping".
// 2. Worse, the file is *incomplete*: Mojang's official obfuscation map only lists the classes
//    the vanilla jar obfuscates, and in 1.20.1 that is not all of them - RenderSystem,
//    GlStateManager and GLX keep their names at runtime, so they have no row at all, and a mixin
//    targeting one of them has no answer in any spelling of the columns.
//
// `scripts/tiny_named_intermediary.py` answers both: it re-spells the mapping in the two
// namespaces the processor reads (with `named` as a *destination* namespace - `getNamespaceId`
// does not search the source one), and it completes every uncovered `com.mojang` class with
// identity rows read out of the named jar, because a class the jar does not rename is a class
// whose runtime name is its Mojang name. The repointing runs in the compile's `doFirst`, from
// the same `-A` argument loom set, so the file the processor reads is always the one built from
// the mapping this build resolved.
tasks.named<JavaCompile>("compileJava") {
        doFirst {
                val argIndex = options.compilerArgs.indexOfFirst { it.startsWith("-AinMapFileNamedIntermediary=") }
                if (argIndex >= 0) {
                        val loomMap = options.compilerArgs[argIndex].removePrefix("-AinMapFileNamedIntermediary=")
                        val mappingsId = File(loomMap).parentFile.name
                        val namedJar = File(
                                gradle.gradleUserHomeDir,
                                "caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/" +
                                        "${parentProperties.getProperty("minecraft_version")}-$mappingsId/" +
                                        "minecraft-merged-${parentProperties.getProperty("minecraft_version")}-$mappingsId.jar",
                        )
                        val fixedMap = layout.buildDirectory.file("mixin-map-fixed.tiny").get().asFile
                        val converter = rootProject.file("../scripts/tiny_named_intermediary.py")
                        if (!fixedMap.isFile || fixedMap.lastModified() < File(loomMap).lastModified()) {
                                exec {
                                        commandLine(
                                                "python3",
                                                converter.absolutePath,
                                                loomMap,
                                                fixedMap.absolutePath,
                                                namedJar.takeIf { it.isFile }?.absolutePath ?: "",
                                        )
                                }
                        }
                        options.compilerArgs[argIndex] = "-AinMapFileNamedIntermediary=${fixedMap.absolutePath}"
                        // The refmap's name is the mixin config's own `refmap` key
                        // (`wgpu_mc.refmap.json`, the name the loader looks up); loom's default
                        // spells the archive's base name instead, so the two must be married here
                        // or the packaged config points at a file the jar does not carry.
                        val refmapIndex = options.compilerArgs.indexOfFirst { it.startsWith("-AoutRefMapFile=") }
                        if (refmapIndex >= 0) {
                                options.compilerArgs[refmapIndex] =
                                        "-AoutRefMapFile=${layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath}/wgpu_mc.refmap.json"
                        }
                }
        }
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

// **The natives, from whichever build packaged them first.** The normal handoff is the Forge
// build's `copyNatives` output in `../Neolectrum-1.20.1/build/generated/natives` - the same files
// every line ships. On a build where that step did not run, this module lays the same libraries
// out itself, straight from the engine checkout, so the line's Fabric jar carries natives
// regardless of the Forge build's health.
val packagedNatives = rootProject.file("../Neolectrum-1.20.1/build/generated/natives")
val engineTarget = rootProject.file("../wgpu-mc-neo/rust/target")

val copyNativesFromEngine = tasks.register<Copy>("copyNativesFromEngine") {
        description = "Lays the engine's native libraries out as a jar carries them."
        onlyIf {
            val host = File(engineTarget, "release/${System.mapLibraryName("wgpu_mc_jni")}")
            val android = File(engineTarget, "aarch64-linux-android/release/libwgpu_mc_jni.so")
            val apple = File(engineTarget, "aarch64-apple-ios/release/libwgpu_mc_jni.dylib")
            val found = listOf(host, android, apple).any { it.isFile }
            if (!found) {
                logger.warn(
                    "wgpu-mc: no engine libraries under {} - this Fabric jar carries no natives",
                    engineTarget.path,
                )
            }
            found
        }
        into(layout.buildDirectory.dir("generated/natives-fabric-only"))
        from(engineTarget) {
            include("release/${System.mapLibraryName("wgpu_mc_jni")}")
            include("aarch64-linux-android/release/libwgpu_mc_jni.so")
            include("x86_64-linux-android/release/libwgpu_mc_jni.so")
            include("i686-linux-android/release/libwgpu_mc_jni.so")
            include("aarch64-apple-ios/release/libwgpu_mc_jni.dylib")
            include("aarch64-apple-darwin/release/libwgpu_mc_jni.dylib")
            eachFile {
                // `assets/wgpu_mc/natives/<abi>/<file>` for the cross-compiles, and the host
                // platform's library flat under `assets/wgpu_mc/natives/` - the same layout the
                // Forge build's copyNatives produces.
                val segments = relativePath.segments
                val dir = when (segments.first()) {
                    "release" -> null
                    "aarch64-linux-android" -> "android-arm64-v8a"
                    "x86_64-linux-android" -> "android-x86_64"
                    "i686-linux-android" -> "android-x86"
                    "aarch64-apple-ios" -> "ios-arm64"
                    "aarch64-apple-darwin" -> "macos-arm64"
                    else -> segments.first()
                }
                relativePath = if (dir == null) {
                    RelativePath(false, "assets", "wgpu_mc", "natives", segments.last())
                } else {
                    RelativePath(true, "assets", "wgpu_mc", "natives", dir, segments.last())
                }
            }
        }
        includeEmptyDirs = false
}

sourceSets.main {
        if (packagedNatives.isDirectory) {
                resources.srcDir(packagedNatives)
        } else {
                resources.srcDir(copyNativesFromEngine.map { it.destinationDir })
                tasks.named("processResources") { dependsOn(copyNativesFromEngine) }
        }
}

dependencies {
        minecraft("com.mojang:minecraft:1.20.1")
        // **1.20.1 is an obfuscated game** - the one thing that makes this build unlike the 26.x
        // ones. The compile-time names are Mojang's official mappings (the same names the
        // unobfuscated 26.x jars carry, minus the years of renames between the two versions),
        // and loom remaps the finished jar to Fabric's intermediary names for production, with
        // the mixin refmap generated along the way.
        mappings(loom.officialMojangMappings())
        // `modImplementation`, not `implementation`: on the 26.x lines these arrive already
        // named, here they must be remapped to intermediary with the jar, and `modImplementation`
        // is the configuration that asks loom for that.
        modImplementation("net.fabricmc:fabric-loader:0.20.1")
        modImplementation("net.fabricmc.fabric-api:fabric-api:0.92.12+1.20.1")

        // The mod is written in Kotlin and nothing on a player's Fabric classpath brings the
        // runtime: the entrypoint is one plain `class` the default adapter builds with a
        // reflective no-argument `newInstance()` (an `object` would die in that reflection), but
        // the Kotlin *classes* - every mixin and every call they make - need the stdlib on the
        // classpath before the first one loads. `include` is Fabric's jar-in-jar.
        include("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
}
