import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
        `java-library`
        kotlin("jvm") version "2.3.20"
        // **ForgeGradle 6, the last line that builds Forge 1.20.1.** The 7.x line is the current
        // one and is aimed at the later games; 1.20.1's userdev format is what 6.0 speaks. It
        // asks for Gradle 8.x (this module's wrapper pins 8.14, not the 9.8 the 26.x modules
        // run), and it runs on a Java 21 JVM happily.
        id("net.minecraftforge.gradle") version "6.0.54"
}

// **One version per Minecraft line, one module per loader.** This is the Forge build of the
// 1.20.1 line - Forge, because NeoForge forked from Forge at 1.20.2 and does not exist here. The
// gradle.properties keeps the `neoforge_mod_version` key anyway: it is the line's
// version-of-record whichever loader it rides, and the CI tag assert reads exactly that key out
// of exactly the `Neolectrum*/gradle.properties` glob - a line that renamed the key is a line the
// assert silently skips, and a forgotten bump is a duplicate version the update checker cannot
// warn about. The name is history; the job it does is current.
val properties = Properties().apply {
        rootProject.file("gradle.properties").inputStream().use { stream -> load(stream) }
}
val modVersion = properties.getProperty("neoforge_mod_version")
val minecraftVersion = properties.getProperty("minecraft_version")
val forgeVersion = properties.getProperty("forge_version")
val modId = "wgpu_mc"
val modName = "Electrum"
val modLicense = "MPLv2"

group = properties.getProperty("neoforge_maven_group", "dev.birb.wgpu")
version = modVersion

base { archivesName.set(modId) }

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

// **The shared sources, compiled against Forge.** The module's own `src` IS the 1.20.1 overlay
// - the GL seam, its mixins, the native bridge, the Forge entry - so the default source
// directories already read it, and what this build adds is the three files whose job is loader-
// and version-neutral, synced out of the 26.x shared tree: the native library loader with its
// Android extraction hardening, the runtime it runs on, the settings record both share. (The
// fabric build of this line syncs the overlay instead, because there the module's own tree holds
// the Fabric answers; here there is no second tree to answer.)
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

sourceSets.main {
        kotlin.srcDirs(syncSharedKotlin.map { it.destinationDir })

        tasks.named("processResources") {
                dependsOn(syncSharedKotlin)
        }
        resources.exclude(
                // the fabric-only metadata the shared tree carries - this build answers the
                // loader with mods.toml and an access transformer instead
                "**/fabric.mod.json",
                "**/wgpu_mc.accesswidener",
        )
}

// **The natives, from the engine checkout.** The same libraries every line ships, laid out the
// way a jar carries them (`assets/wgpu_mc/natives/<abi>/libwgpu_mc_jni.so`), copied straight from
// the Rust engine's target tree the Fabric build's fallback copies from.
val engineTarget = rootProject.file("../wgpu-mc-neo/rust/target")

val androidTripleAbis = mapOf(
        "aarch64-linux-android" to "android-arm64-v8a",
        "x86_64-linux-android" to "android-x86_64",
        "i686-linux-android" to "android-x86",
)

val appleTripleDirs = mapOf(
        "aarch64-apple-ios" to "ios-arm64",
        "aarch64-apple-darwin" to "macos-arm64",
)

val copyNatives = tasks.register<Copy>("copyNatives") {
        description = "Copies the JNI bridge built by the Rust engine checkout into this module's resources."
        group = "build"
        onlyIf {
            val android = File(engineTarget, "aarch64-linux-android/release/libwgpu_mc_jni.so")
            val apple = File(engineTarget, "aarch64-apple-ios/release/libwgpu_mc_jni.dylib")
            val found = listOf(android, apple).any { it.isFile }
            if (!found) {
                logger.warn(
                    "wgpu-mc: no engine libraries under {} - this jar carries no natives",
                    engineTarget.path,
                )
            }
            found
        }
        into(layout.buildDirectory.dir("generated/natives"))
        from(engineTarget) {
            include("aarch64-linux-android/release/libwgpu_mc_jni.so")
            include("x86_64-linux-android/release/libwgpu_mc_jni.so")
            include("i686-linux-android/release/libwgpu_mc_jni.so")
            include("aarch64-apple-ios/release/libwgpu_mc_jni.dylib")
            include("aarch64-apple-darwin/release/libwgpu_mc_jni.dylib")
            eachFile {
                val segments = relativePath.segments
                val dir = when (segments.first()) {
                    "aarch64-linux-android" -> "android-arm64-v8a"
                    "x86_64-linux-android" -> "android-x86_64"
                    "i686-linux-android" -> "android-x86"
                    "aarch64-apple-ios" -> "ios-arm64"
                    "aarch64-apple-darwin" -> "macos-arm64"
                    else -> segments.first()
                }
                relativePath = RelativePath(true, "assets", "wgpu_mc", "natives", dir, segments.last())
            }
        }
        includeEmptyDirs = false
}

sourceSets.main {
        resources.srcDir(copyNatives.map { it.destinationDir })
}

tasks.named<ProcessResources>("processResources") {
        dependsOn(copyNatives)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val replaceProperties = mapOf(
                "minecraft_version" to minecraftVersion,
                "forge_version" to forgeVersion,
                "mod_id" to modId,
                "mod_name" to modName,
                "mod_license" to modLicense,
                "mod_version" to modVersion,
        )
        inputs.properties(replaceProperties)
        filesMatching("META-INF/mods.toml") {
                expand(replaceProperties)
        }
}

// **The Kotlin runtime, embedded.** The mod is written in Kotlin and nothing on a player's Forge
// classpath brings the runtime - the entry is one plain `class` the javafml adapter builds with a
// reflective no-argument `newInstance()` (the same shape the Fabric entrypoint ships, which is
// why the line needs no Kotlin for Forge), but the Kotlin *classes* need the stdlib on the
// classpath before the first one loads. `jarJar` is Forge's jar-in-jar; the `-all` suffix on the
// published jar is its signature, and the CI jar check asserts the embedded stdlib by name.
// **Mappings, declared the way ForgeGradle 6 wants them.** The official channel - Mojang's own
// names, the same names the unobfuscated 26.x jars carry - and ForgeGradle reobfuscates the
// finished jar to Forge's runtime (SRG) names, with the mixin refmap generated along the way.
// The Groovy MDK spells this `mappings channel: 'official', version: '1.20.1'`; the extension
// method underneath takes the same map.
configure<net.minecraftforge.gradle.common.util.MinecraftExtension> {
        mappings(mapOf("channel" to "official", "version" to minecraftVersion))
}

jarJar.enable()

dependencies {
        minecraft("net.minecraftforge:forge:$minecraftVersion-$forgeVersion")

        // Mappings: ForgeGradle 6 asks Mojang's official channel by default - the same names the
        // unobfuscated 26.x jars carry - and reobfuscates the finished jar to Forge's runtime
        // (SRG) names, with the mixin refmap generated along the way.
        // The jarJar notation carries the accepted RANGE (it goes into the embedded metadata
        // verbatim); `pin` names the one build the embed actually carries.
        jarJar("org.jetbrains.kotlin:kotlin-stdlib:[2.3.20,3.0.0)") {
                jarJar.pin(this, "2.3.20")
        }
        // **No mixin annotation processor here, on purpose.** The processor reads the mapping
        // table this build compiles against and answers the refmap from it - and on this line
        // the answer is nearly empty on purpose: the classes the GL seam mixins (RenderSystem,
        // GlStateManager and their inner states) are classes Mojang does not obfuscate - the
        // official mapping carries them as identity rows - so their member names are the same
        // strings at runtime and need no mapping at all. The one string that does move is
        // TimerQueryLazyLoader.instantiate, and that one is shipped as a static refmap in this
        // module's resources (wgpu_mc.refmap.json, m_231155_, stable per the 1.20.1 SRG table).
        // Running the processor instead of shipping the answer made it fail the compile on the
        // identity classes, whose lookups its mapping table cannot answer.
}

// The reobfuscated jarJar output is the artifact a player installs; the CI jar check reads the
// `-all` name and the embedded stdlib out of it.
tasks.jarJar {
        finalizedBy("reobfJarJar")
}
