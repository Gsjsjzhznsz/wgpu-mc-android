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
        // (No sources jar: the module's resources live in two trees, and a sources jar is a
        // second place to answer the same duplicate the jar itself answers.)
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

kotlin {
        compilerOptions {
                jvmTarget.set(JvmTarget.JVM_25)
        }
}

loom {
        // **The widener, named for the build the way it is named for the loader.** The
        // fabric.mod.json field tells the *loader* to apply the widener at runtime; this is the
        // same file named for *loom*, so the same entries reach the jar the Kotlin compiles
        // against - without it the path stays empty, `validateAccessWidener` runs with no source,
        // and every member the widener opens is unresolved at compile time.
        accessWidenerPath = file("src/main/resources/wgpu_mc.accesswidener")

        // **The sourceset, declared as the mod it is.** Without this block loom has no reason to
        // read this module's fabric.mod.json as *metadata about this build* - and the access
        // widener that file names is discovered through exactly that read: the widener path stays
        // empty, `validateAccessWidener` runs with no source, and every member the widener opens
        // is unresolved at compile time. The example mod carries the same block for the same
        // reason.
        mods {
                create(modId) {
                        sourceSet(sourceSets.main.get())
                }
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
                // **Four files whose job touches the game's NeoForge-patched surface**, each
                // answered by this module's own copy under the same package (named `*Fabric`,
                // because an exclude pattern matches by path and a same-named file here would be
                // caught by the same pattern - the class names inside are the ones every
                // reference resolves): the GpuDevice implementation carries a
                // `clearStencilTexture` override of a method only NeoForge's GpuDevice has, the
                // format map names two stencil constants only NeoForge's TextureFormat enum has,
                // and the two fluid-tint sites call `fluidTintSource()`, a channel only
                // NeoForge's model classes carry. The copies are the same files with those three
                // seams answered the vanilla way - no override, no stencil constants, untinted
                // fluids.
                "dev/birb/wgpu/backend/WgpuCommandEncoder.kt",
                "dev/birb/wgpu/backend/WgpuFormat.kt",
                "dev/birb/wgpu/render/TintProfile.kt",
                "dev/birb/wgpu/render/Wgpu.kt",
                // The block cache reads the atlas' sprite map, whose vanilla name is
                // `texturesByName` where NeoForge's patched jar answers `textures` - the same map
                // by either name, and the answer is this module's own copy.
                "dev/birb/wgpu/BlockCache.kt",
        )
        // **/ prefixes, and not bare paths: an exclude pattern without one matches the file at
        // the root of the FIRST source directory it is declared against, and these files live in
        // the shared tree the default srcDir reaches - the double star is what actually filters.
        resources.exclude(
                "**/META-INF/neoforge.mods.toml",
                "**/META-INF/accesstransformer.cfg",
                "**/wgpu_mc.mixins.json.bak",
                "**/assets/electrum/**",
                "**/*.bbmodel",
                "**/.cache",
        )
}

// The module's own `wgpu_mc.mixins.json` (same name, this module's srcDir is searched first) is
// what ships, so the shared one never wins the duplicate by accident either.
tasks.processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // The id is spelled literally in fabric.mod.json, not expanded: loom reads the *source*
        // metadata to find the access widener, and a placeholder where a mod id belongs is not a
        // mod id as far as its validation is concerned - the widener then never loads, and every
        // member it widens is unresolved at compile time.
        val properties = mapOf(
                "version" to modVersion,
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
        // this mod does not use (its entrypoint is one plain `class` the default adapter builds
        // with a reflective no-argument `newInstance()` - it was an `object` once, whose private
        // constructor that reflection cannot touch, and every launch died on it), but the Kotlin
        // *classes* - every mixin and every call they make - need the stdlib on the
        // classpath before the first one loads. `include` is Fabric's jar-in-jar, the same shape
        // the NeoForge build's jarJar embeds.
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

