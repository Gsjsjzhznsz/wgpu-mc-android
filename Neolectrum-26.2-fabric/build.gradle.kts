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

// **The shared trees, filtered into the build rather than excluded out of it.** Gradle's
// excludes match a relative path against EVERY source root at once, and this module compiles
// three trees whose files share paths: the 26.1 original, the 26.2 overlay, and this module's
// own loader answers. Excluding `dev/birb/wgpu/chunk/RustChunkBake.kt` would have killed all
// three - the first CI run proved it by failing on the module's own copy. So the two shared
// trees are synced into this build's generated directories first, minus exactly the files the
// next tree up answers, and the source set reads the filtered copies:
//
//   shared-java    = ../Neolectrum/src/main/java, minus the five mixins 26.2 moved (the overlay
//                    tree answers them) and minus the two NeoForge-only mixins
//   shared-kotlin  = ../Neolectrum/src/main/kotlin, minus every file the loader or the 26.2
//                    overlay answers
//   overlay-kotlin = ../Neolectrum-26.2/src/main/kotlin, minus the NeoForge-flavoured block
//                    cache (this module's own `BlockCacheFabric.kt` answers it)
//
// This module's own `src/main` provides the loader answers, and wins every conflict by being
// named last.
val syncSharedJava = tasks.register<Sync>("syncSharedJava") {
        from("../Neolectrum/src/main/java") {
                exclude(
                        // the five mixins the 26.2 overlay answers
                        "dev/birb/wgpu/mixin/level/VisibleSectionsMixin.java",
                        "dev/birb/wgpu/mixin/render/WorldRendererMixin.java",
                        "dev/birb/wgpu/mixin/render/InGameHudMixin.java",
                        "dev/birb/wgpu/mixin/chunk/RustChunkBakeMixin.java",
                        "dev/birb/wgpu/mixin/core/OptionsScreenMixin.java",
                        // the two NeoForge-only mixins
                        "dev/birb/wgpu/mixin/render/WindowMixin.java",
                        "dev/birb/wgpu/mixin/core/EarlyWindowMixin.java",
                        // the render pass the 26.2 overlay answers
                        "dev/birb/wgpu/backend/WgpuRenderPass.java",
                )
        }
        into(layout.buildDirectory.dir("generated/shared-java"))
}

val sharedKotlinExcludes = listOf(
        // the loader seams
        "dev/birb/wgpu/WgpuMcMod.kt",
        "dev/birb/wgpu/WgpuMcModClient.kt",
        "dev/birb/wgpu/backend/EarlyWindow.kt",
        // the NeoForge-patched surfaces this module answers
        "dev/birb/wgpu/backend/WgpuCommandEncoder.kt",
        "dev/birb/wgpu/backend/WgpuFormat.kt",
        "dev/birb/wgpu/render/TintProfile.kt",
        "dev/birb/wgpu/render/Wgpu.kt",
        "dev/birb/wgpu/BlockCache.kt",
        // the 26.2 answers the overlay tree carries
        "dev/birb/wgpu/backend/WgpuDevice.kt",
        "dev/birb/wgpu/backend/WgpuSurface.kt",
        "dev/birb/wgpu/backend/WgpuBackend.kt",
        "dev/birb/wgpu/backend/WgpuTexture.kt",
        "dev/birb/wgpu/backend/WgpuBuffer.kt",
        "dev/birb/wgpu/backend/WgpuCompiledRenderPipeline.kt",
        "dev/birb/wgpu/chunk/RustChunkBake.kt",
        "dev/birb/wgpu/render/TerrainPass.kt",
        "dev/birb/wgpu/gui/OptionPageScreen.kt",
        "dev/birb/wgpu/gui/OptionPages.kt",
        "dev/birb/wgpu/gui/widgets/TabWidget.kt",
)

val syncSharedKotlin = tasks.register<Sync>("syncSharedKotlin") {
        from("../Neolectrum/src/main/kotlin") { exclude(sharedKotlinExcludes) }
        into(layout.buildDirectory.dir("generated/shared-kotlin"))
}

val syncOverlayKotlin = tasks.register<Sync>("syncOverlayKotlin") {
        from("../Neolectrum-26.2/src/main/kotlin") {
                // the overlay's block cache is the NeoForge-flavoured copy; this module's own
                // `BlockCacheFabric.kt` is the answer here
                exclude("dev/birb/wgpu/BlockCache.kt")
        }
        into(layout.buildDirectory.dir("generated/overlay-kotlin"))
}

sourceSets.main {
        java.srcDirs(syncSharedJava.map { it.destinationDir }, "../Neolectrum-26.2/src/main/java")
        kotlin.srcDirs(
                syncSharedKotlin.map { it.destinationDir },
                syncOverlayKotlin.map { it.destinationDir },
                "src/main/kotlin",
        )
        resources.srcDirs("../Neolectrum/src/main/resources", "../Neolectrum-26.2/src/main/resources")

        tasks.named("processResources") {
                dependsOn(syncSharedJava, syncSharedKotlin, syncOverlayKotlin)
        }
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

// **The natives, from whichever build packaged them first.** The normal handoff is the NeoForge
// build's `copyNatives` output in `../Neolectrum-26.2/build/generated/natives` - the same files
// both loaders ship. On a build where that step did not run (CI marks the NeoForge 26.2 build
// non-blocking while NeoForm 26.2-2 is broken upstream - see the workflow's note), this module
// lays the same libraries out itself, straight from the engine checkout the NeoForge build would
// have read, so the line's Fabric jar carries natives regardless of the NeoForge build's health.
val packagedNatives = rootProject.file("../Neolectrum-26.2/build/generated/natives")
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
            include("aarch64-apple-ios/release/libwgpu_mc_jni.dylib")
            include("aarch64-apple-darwin/release/libwgpu_mc_jni.dylib")
            eachFile {
                // `assets/wgpu_mc/natives/<abi>/<file>` for the cross-compiles, and the host
                // platform's library flat under `assets/wgpu_mc/natives/` - the same layout the
                // NeoForge build's copyNatives produces.
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
        minecraft("com.mojang:minecraft:26.2")
        // 26.2 is unobfuscated like 26.1 - no mappings line, and the loader and API arrive as
        // plain `implementation` for the same reason: there is nothing left to remap.
        implementation("net.fabricmc:fabric-loader:0.20.1")
        // The real coordinates, not loom's `net.fabricmc:fabric-api` shorthand: the shorthand
        // resolves for the 26.1.2 line and did not for 26.2 (the search came back as plain
        // `net.fabricmc:fabric-api` on the maven, which is a group that has no artifacts), while
        // the module's own group - `net.fabricmc.fabric-api` - serves every 26.2 build. Same
        // artifact either way; this spelling is the one the maven answers.
        implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")

        include("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
}

// ---------------------------------------------------------------------------
// **The mixin the compiler reads decides the shape the jar ships.**
//
// `Redirect.at()` changed type in the fabric sponge-mixin fork: 0.17.3 declares a single
// `At`, 0.17.4 declares `At[]`. javac encodes the *declared* shape - compile against
// 0.17.4 and every `at = @At(...)` in the jar is written as an array, which is a legal
// encoding for that declaration but one the MixinExtras bundled with older loaders
// (0.5.4 on 0.19.3 and below, 0.4.x on the 1.20.x era) cannot read: its factory-redirect
// scan casts the `at` value straight to one `AnnotationNode` and a launch dies on
// "ArrayList cannot be cast to AnnotationNode" the first mixin with a @Redirect is
// prepared - TextureAtlasMixin, here. Every loader from 0.15 to the latest reads the
// single shape (0.17.4 gained the normalizing `getValue(..., notNull)` overload and
// MixinExtras 0.5.5 the list form exactly so both shapes parse), so compiling against
// 0.17.3 is the one answer every launcher can read: the iOS Prisma build is pinned to
// loader 0.19.3 and cannot be told to move. The pin rides the compile and runtime
// classpaths only - loom's own configurations keep the 0.17.4 they are built against.
// ---------------------------------------------------------------------------
configurations.matching { it.name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath") }
        .configureEach {
                resolutionStrategy.force("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
        }
