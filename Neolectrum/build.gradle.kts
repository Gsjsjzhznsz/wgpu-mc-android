import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.internal.jvm.Jvm
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.JavaForkOptions
import java.io.File

plugins {
        `java-library`
        `maven-publish`
        kotlin("jvm") version "2.3.20"
        id("net.neoforged.gradle.userdev") version "7.1.21"
}

val neoforgeMinecraftVersion = providers.gradleProperty("neoforge_minecraft_version").get()
val neoforgeNeoVersion = providers.gradleProperty("neoforge_neo_version").get()
val neoforgeModVersion = providers.gradleProperty("neoforge_mod_version").get()
val neoforgeMavenGroup = providers.gradleProperty("neoforge_maven_group").get()
val neoforgeKotlinVersion = providers.gradleProperty("neoforge_kotlin_version").getOrElse("2.3.20")
// The KFF build the jarJar embeds (the four `thedarkcolour` artifacts below). 6.4.0 is the newest
// KFF whose `neoforge.mods.toml` accepts this mod's Minecraft range, and the range the mod file
// declares for KFF itself (`kff_version_range`, floor 6.3.0) contains it.
val kffEmbeddedVersion = "6.4.0"

val modId = "wgpu_mc"
val modName = "Neolectrum"
val modLicense = "MPLv2"
val loaderVersionRange = "[3,)"
// Minecraft 26.1 is the new stable modding baseline; 26.2 is the next feature release.
val minecraftVersionRange = "[$neoforgeMinecraftVersion,$neoforgeMinecraftVersion.999)"

group = neoforgeMavenGroup
version = neoforgeModVersion

base {
        archivesName.set(modId)
}

sourceSets.main {
        resources {
                srcDir("src/generated/resources")
                exclude("**/*.bbmodel")
                exclude("**/.cache")
                exclude("assets/electrum/**")
        }
}

java {
        toolchain {
                // Minecraft 26.1 moved to Java 25.
                languageVersion.set(JavaLanguageVersion.of(25))
        }
        withSourcesJar()
}

kotlin {
        jvmToolchain(25)
}

// ---------------------------------------------------------------------------
// The Kotlin runtime has to be inside the jar.
//
// This module is written in Kotlin, and nothing else on a player's classpath brings the runtime:
// FML and NeoForge are Java, and Minecraft does not ship Kotlin either. A development run does not
// show the omission, because the Kotlin Gradle plugin puts the stdlib on the *run* classpath - which
// is why it survived until the first jar was started in a real instance. There the game dies the
// moment a Kotlin class is loaded, and the first one it loads is the mixin that runs on `Main.main`:
//
//   java.lang.NoClassDefFoundError: kotlin/jvm/internal/Intrinsics
//
// `jarJar` embeds the stdlib under `META-INF/jarjar/` next to the metadata FML's jar-in-jar loader
// reads, so the published `<name>-all.jar` carries its own runtime. KotlinForForge ships one as
// well; two copies is the ordinary jar-in-jar situation and the versions are compatible, so an
// instance that has it keeps working - an instance that does not is the case this fixes.
// ---------------------------------------------------------------------------
dependencies {
        constraints {
                implementation("org.jetbrains.kotlin:kotlin-stdlib:$neoforgeKotlinVersion") {
                        because("The Kotlin plugin adds the stdlib at its own version; pin it to the one jarJar embeds.")
                }
        }

        // Adding a dependency to `jarJar` is what enables the `jarJar` task; its output (classifier
        // `all`) is the artifact `assemble` and `publish` hand out.
        add("jarJar", "org.jetbrains.kotlin:kotlin-stdlib:$neoforgeKotlinVersion")

        // **Kotlin for Forge, inside the jar.** The `-all` suffix promised dependencies and shipped
        // half of them: the stdlib rode along, but the mod still demanded KFF as a separate
        // download (`modLoader="kotlinforforge"` cannot even parse this file without the language
        // provider somewhere on the classpath). The four KFF artifacts close that gap; together
        // with the stdlib above they are exactly what a standalone Kotlin for Forge install
        // provides, so a fresh install needs exactly one jar:
        //
        //   kotlinforforge-neoforge - the Kotlin runtime carrier: its own `META-INF/jarjar/`
        //                             nests kotlin-reflect, stdlib, coroutines and serialization,
        //                             which FML loads recursively as libraries.
        //   kffmod-neoforge         - the `kotlinforforge` mod itself, whose `neoforge.mods.toml`
        //                             satisfies the dependency this file declares below.
        //   kfflib-neoforge         - KFF's own library classes.
        //   kfflang-neoforge        - the language provider (`META-INF/services`); the thing
        //                             `modLoader="kotlinforforge"` resolves to.
        //
        // **One migration note for installs that already carry a standalone KFF**: FML refuses
        // two mods with the same id, and the old `kotlinforforge-*.jar` in `mods/` and this
        // embedded `kffmod-neoforge` both declare one - the old file has to go once after
        // updating. (A mods-folder KFF the OLD way was the only way; new installs never see
        // the question.)
        add("jarJar", "thedarkcolour:kotlinforforge-neoforge:$kffEmbeddedVersion")
        add("jarJar", "thedarkcolour:kffmod-neoforge:$kffEmbeddedVersion")
        add("jarJar", "thedarkcolour:kfflib-neoforge:$kffEmbeddedVersion")
        add("jarJar", "thedarkcolour:kfflang-neoforge:$kffEmbeddedVersion")
}

val localRuntime = configurations.named("localRuntime")
configurations.named("runtimeClasspath") {
        extendsFrom(localRuntime.get())
}

dependencies {
        implementation("net.neoforged:neoforge:$neoforgeNeoVersion")
}

repositories {
        maven {
                name = "NeoForged"
                setUrl("https://maven.neoforged.net/releases")
        }
        // Kotlin for Forge's own maven: the four KFF artifacts the jarJar embeds below live here
        // (and nowhere on Maven Central), and the KFF Gradle plugin came from the same URL through
        // the settings script - this is the plain-dependency twin of that.
        maven {
                name = "Kotlin for Forge"
                setUrl("https://thedarkcolour.github.io/KotlinForForge/")
        }
        mavenCentral()
}

// ---------------------------------------------------------------------------
// Native library packaging
//
// The JNI bridge is not built here. It is the output of the Rust engine, which lives in its own
// repository - a checkout *beside* this one by default, `../wgpu-mc-neo` - where
// `cargo build --release` writes `rust/target/release/wgpu_mc_jni.<ext>`. This task takes that
// binary, and the PDB the same build leaves next to it on Windows, out of the engine's output
// directory and into this module's resources, which is how they reach the jar.
//
// Android libraries travel the same road with one turn added: the engine's Android builds are
// cross-compiles, so their libraries live in `rust/target/<triple>/release/` rather than the
// host's `target/release/`, and a jar that has to run on more than one platform cannot have them
// flat under one name. Each Android library the build finds goes under
// `assets/wgpu_mc/natives/android-<abi>/` - the directory `WgpuNative` probes first on a device
// of that ABI - with the host platform's library staying flat, exactly as it always was. The
// ABIs packaged are the ones the engine has built, or the ones `wgpu_mc_android_abis` names:
//
//   -Pwgpu_mc_android_abis=aarch64-linux-android
//
// The engine checkout is looked for in this order, so a machine that keeps it somewhere else - a CI
// job, a second clone, a worktree - only has to say where:
//
//   -Pwgpu_mc_rust_dir=D:/engines/wgpu-mc-neo/rust     (or WGPU_MC_RUST_DIR in the environment)
//
// With no built engine there, `copyNatives` is skipped with a warning instead of failing the build:
// the jar then carries no natives, which is a jar for everyone who has the library from somewhere
// else, and one that cannot run the renderer itself.
// ---------------------------------------------------------------------------

/** This repository's root, and the engine checkout the build assumes when nothing says otherwise. */
val modDirectory = layout.projectDirectory.asFile
val defaultEngineDirectory = File(modDirectory, "../wgpu-mc-neo/rust")

/**
 * A path from a property or from the environment, resolved the way a build file names one: an
 * absolute path is taken as it is, a relative one against this repository's root rather than
 * against the working directory Gradle happened to be started from.
 */
fun engineDirectory(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(modDirectory, path)
}

val rustProjectDir = providers.gradleProperty("wgpu_mc_rust_dir")
        .orElse(providers.environmentVariable("WGPU_MC_RUST_DIR"))
        .map { engineDirectory(it) }
        .orElse(defaultEngineDirectory)

val rustReleaseDir = rustProjectDir.map { File(it, "target/release") }
val nativeLibraryFileName = System.mapLibraryName("wgpu_mc_jni")
val nativeLibrary = rustReleaseDir.map { File(it, nativeLibraryFileName) }

// The Android cross-compiles, and the jar directory each one's library goes under. A triple the
// engine has not built simply has no file, and a Copy task with no existing source for it copies
// nothing - so this list is what the build *looks for*, not what it requires.
val androidTripleAbis = mapOf(
        "aarch64-linux-android" to "android-arm64-v8a",
        "armv7-linux-androideabi" to "android-armeabi-v7a",
        "x86_64-linux-android" to "android-x86_64",
        "i686-linux-android" to "android-x86",
)

// The Apple cross-compiles, and the jar directory each one's library goes under - the same story as
// the Android list above, with the loader probing `ios-arm64/` on an iPhone and the `macos-*/` one on
// a desktop Mac. A triple the engine has not built simply has no file, and a Copy task with no
// existing source for it copies nothing: on the Linux CI that builds the Android release, none of
// these exist, and the jar then carries no Apple libraries - which is why the release workflow has a
// macOS job that builds them and hands them over before this task runs.
val appleTripleDirs = mapOf(
        "aarch64-apple-ios" to "ios-arm64",
        "aarch64-apple-darwin" to "macos-arm64",
        "x86_64-apple-darwin" to "macos-x86_64",
)

/**
 * The triples whose libraries this build packages: every one the engine has built, or the
 * `wgpu_mc_android_abis` list (comma-separated, from a `-P` or the environment) when a build
 * wants to ship fewer.
 */
val androidAbis: Map<String, String> = run {
        val requested = providers.gradleProperty("wgpu_mc_android_abis")
                .orElse(providers.environmentVariable("WGPU_MC_ANDROID_ABIS"))
                .orNull
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
        if (requested.isNullOrEmpty()) androidTripleAbis else androidTripleAbis.filterKeys { it in requested }
}

// Android libraries keep the ELF name on every host - `System.mapLibraryName` answers the host's
// own convention, and a Windows-hosted build must not go looking for a `.dll` that the NDK never
// produced.
val androidLibraryFileName = "libwgpu_mc_jni.so"

// The Apple libraries keep the Mach-O name on every host, for the same reason: the macOS CI job
// produces `libwgpu_mc_jni.dylib`, and a Linux-hosted build must not rename the file it never
// produced. The loader's own name comes from `System.mapLibraryName`, which answers `.dylib` on
// the Apple JVMs these are built for.
val appleLibraryFileName = "libwgpu_mc_jni.dylib"

// The PDB the release build writes beside the library, when the profile asks for one (`debug =
// "line-tables-only"` in `rust/Cargo.toml`). It rides along into this module's resources, and into
// the published jar with them, for one reason: the game unpacks both into the launcher's natives
// directory, and PIX resolves a timing capture's function names through the PDB that sits *there* -
// a debugger looks for symbols beside the module, not inside the mod's jar. Absent when the Rust build
// produced none, which is not an error.
val nativeSymbols = rustReleaseDir.map { File(it, nativeLibraryFileName.substringBeforeLast('.') + ".pdb") }

val copyNatives = tasks.register<Copy>("copyNatives") {
        description = "Copies the JNI bridge built by the Rust engine checkout into this module's resources."
        group = "build"

        from(nativeLibrary) {
                into("assets/$modId/natives")
        }
        from(nativeSymbols) {
                into("assets/$modId/natives")
        }
        androidAbis.forEach { (triple, abi) ->
                from(rustProjectDir.map { File(it, "target/$triple/release/$androidLibraryFileName") }) {
                        into("assets/$modId/natives/$abi")
                }
        }
        appleTripleDirs.forEach { (triple, dir) ->
                from(rustProjectDir.map { File(it, "target/$triple/release/$appleLibraryFileName") }) {
                        into("assets/$modId/natives/$dir")
                }
        }
        into(layout.buildDirectory.dir("generated/natives"))

        onlyIf("the Rust engine has been built") {
                val library = nativeLibrary.get()
                val androidLibraries = androidAbis.keys
                        .map { triple -> File(rustProjectDir.get(), "target/$triple/release/$androidLibraryFileName") }
                        .filter { it.isFile }
                val appleLibraries = appleTripleDirs.keys
                        .map { triple -> File(rustProjectDir.get(), "target/$triple/release/$appleLibraryFileName") }
                        .filter { it.isFile }

                when {
                        library.isFile -> true
                        // An Android-only jar is a legitimate result of this task - the Android
                        // release is built on Linux CI that never produces a host library - so the
                        // desktop library being absent is a warning, not a skip, when the Android
                        // half made it. The Apple libraries travel the same road the other way: the
                        // macOS job builds them and the Linux job packs them, and a jar whose Apple
                        // half is missing still runs every Android device.
                        androidLibraries.isNotEmpty() || appleLibraries.isNotEmpty() -> {
                                logger.warn(
                                        "wgpu-mc: no host native library at {}, but {} Android and {} Apple " +
                                                "libraries were packaged - this jar is a mobile/cross jar.",
                                        library.path,
                                        androidLibraries.size,
                                        appleLibraries.size,
                                )
                                true
                        }
                        else -> {
                                logger.warn(
                                        "wgpu-mc: no native library at {} and no Android or Apple library under {} - " +
                                                "skipping copyNatives, so this jar carries no natives and cannot " +
                                                "run the renderer. Build it with `cargo build --release` in {}, or " +
                                                "point this build at the checkout that has it: -Pwgpu_mc_rust_dir=<path> " +
                                                "(or the WGPU_MC_RUST_DIR environment variable).",
                                        library.path,
                                        rustProjectDir.get().path,
                                        rustProjectDir.get().path
                                )
                                false
                        }
                }
        }
}

sourceSets.main {
        resources.srcDir(copyNatives.map { it.destinationDir })
}

val unpackExports = tasks.register<Copy>("unpackExports") {
        onlyIf { layout.buildDirectory.file("resources/main/export.zip").get().asFile.exists() }
        from(zipTree(layout.buildDirectory.file("resources/main/export.zip")))
        into(layout.buildDirectory.dir("resources/main"))
        finalizedBy("deleteExports")
}

tasks.register<Delete>("deleteExports") {
        description = "Deletes the exported files after they have been processed."
        delete(layout.buildDirectory.file("resources/main/export.zip"))
}

tasks.named<ProcessResources>("processResources") {
        dependsOn(copyNatives)
        finalizedBy(unpackExports, "deleteExports")

        val replaceProperties = mapOf(
                "minecraft_version" to neoforgeMinecraftVersion,
                "minecraft_version_range" to minecraftVersionRange,
                "neo_version" to neoforgeNeoVersion,
                "loader_version_range" to loaderVersionRange,
                // Kotlin for Forge's loader range, `neoforge.mods.toml`'s `loaderVersion` and the
                // kfflib dependency range. Lived only in gradle.properties until a build without
                // it in the expansion map taught processResources to fail on it.
                "kff_version_range" to providers.gradleProperty("kff_version_range").get(),
                "mod_id" to modId,
                "mod_name" to modName,
                "mod_license" to modLicense,
                "mod_version" to neoforgeModVersion
        )
        inputs.properties(replaceProperties)

        filesMatching("META-INF/neoforge.mods.toml") {
                expand(replaceProperties)
        }
}

tasks.named<Jar>("jar") {
        dependsOn(unpackExports, "deleteExports")

        // The PDB stays in the jar. It is ~47 MB of line tables uncompressed, and it is the only way a
        // capture taken on a machine that runs the published jar can show function names - which is the
        // machine a report about a slow frame comes from. `jarJar` copies this task's output into the
        // published jar, so the symbols travel with the library they match.
}

listOf("runClient", "runData", "runGameTestServer", "runServer").forEach { runTaskName ->
        tasks.matching { it.name == runTaskName }.configureEach {
                dependsOn(unpackExports)
        }
}

// ---------------------------------------------------------------------------
// NeoForge's early loading window, turned off for development runs.
//
// It draws its splash screen through OpenGL from Minecraft's render thread, which this mod cannot
// survive without an abort - a production instance hits exactly that, and
// `dev.birb.wgpu.backend.EarlyWindow` now takes the screen out of the way there (the README has the
// stack and the reasoning). This task is the cruder, older answer to the same problem: it writes
// FML's own switch, which is read before any mod is loaded, so a dev run never creates the screen at
// all and opens straight into the game window.
//
// The cost is that the dev loop does not exercise `EarlyWindow`, so this is deliberately only a
// convenience - deleting `earlyWindowControl` from runs/client/config/fml.toml makes runClient take
// the same path a player's instance does. The task edits that one key and leaves the rest of the
// file to FML, which fills in everything it does not find.
// ---------------------------------------------------------------------------
val configureEarlyWindow = tasks.register("configureEarlyWindow") {
        description = "Disables NeoForge's early loading window, which needs a GL context this mod never creates."
        group = "neoforge"

        val configFile = layout.projectDirectory.file("runs/client/config/fml.toml").asFile
        outputs.file(configFile)

        doLast {
                val key = "earlyWindowControl"
                val wanted = "$key = false"

                if (!configFile.exists()) {
                        // The rest of the file is FML's to write: its config spec fills in every key it does
                        // not find, so seeding just this one is enough.
                        configFile.parentFile.mkdirs()
                        configFile.writeText("#Written by the wgpu-mc build; see configureEarlyWindow in build.gradle.kts\n$wanted\n")
                        logger.lifecycle("Seeded ${configFile.path} with $wanted")
                        return@doLast
                }

                val lines = configFile.readLines()
                val rewritten = lines.map { line ->
                        if (line.trimStart().startsWith("$key ") || line.trimStart().startsWith("$key=")) wanted else line
                }

                if (rewritten != lines) {
                        configFile.writeText(rewritten.joinToString("\n", postfix = "\n"))
                        logger.lifecycle("Set $wanted in ${configFile.path}")
                }
        }
}

tasks.matching { it.name == "runClient" }.configureEach {
        dependsOn(configureEarlyWindow)
}

// The backend binds the Rust C ABI through java.lang.foreign, which is a restricted method on
// JDK 24+. Without this flag a dev run logs a warning per downcall; a production launcher is
// expected to pass the same flag, and the mod still works if it does not. Both names are needed:
// the mod's own classes are loaded as the named module `wgpu_mc`, while the FFM calls that reach
// the JDK from the unnamed module (the class path) are covered by ALL-UNNAMED.
//
// The guard is the *interface*, not `JavaExec`: what a run task has to be here is something that
// forks a JVM and takes arguments for it, which is what `jvmArgs` is a method of. NeoGradle's run
// tasks happen to *be* `JavaExec` today - `help --task runClient` says so - but that is a property
// of the plugin, not of the flag: a run task from another plugin, or a decorated one, implements
// `JavaForkOptions` without extending `JavaExec`, and a guard that named the class would silently
// stop applying to it.
//
// The flag goes on as a *provider*, not through `jvmArgs`, and that is not a style choice: NeoGradle
// configures the run tasks from its own run config after this block has run, and it *replaces* their
// argument list. Checked with an init script that read the task back - `runClient.jvmArgs` came out
// as the plugin's own `[--sun-misc-unsafe-memory-access=allow, --enable-native-access=ALL-UNNAMED,
// --add-opens, ...]` with this flag nowhere in it, at `projectsEvaluated` and again at
// `taskGraph.whenReady`. So the dev loop has been running *without* the module half of the flag,
// which is the half the mod's own classes need - they are loaded as the named module `wgpu_mc`, and
// `--enable-native-access=ALL-UNNAMED` covers the class path, not them. The symptom is a warning per
// downcall and nothing else, which is why it went unnoticed.
//
// A provider is a second list that the command line is built from, and a later `setJvmArgs` cannot
// drop it.
tasks.matching { it.name.startsWith("run") }.configureEach {
        if (this is JavaForkOptions) {
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                        listOf("--enable-native-access=ALL-UNNAMED,wgpu_mc")
                })
        }
}

// Diagnostics - the frame/trace dumps behind `dev.birb.wgpu.backend.Diagnostics` - are switched on
// per run instead of from here, because the launcher's JVM arguments turned out to be one more
// thing that can silently not arrive:
//
//   gradlew runClient -Dwgpu_mc.diagnostics=true     # or
//   New-Item runs/client/wgpu-dump-frames            # no launcher support needed
//
// The marker file is the one that works everywhere, so it is the documented one.

tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
                rename { "${it}_${base.archivesName.get()}" }
        }
}

tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
}

/**
 * Fails the build if a mixin's **class initialiser** reads a Minecraft class.
 *
 * This is a crash rather than a style rule, and a crash on the title screen. A mixin is applied to a
 * Minecraft class and its own `<clinit>` runs at whatever moment that class is first initialised - which
 * for `TextureAtlas` is reached from `Sheets`' own initialiser, before `Sheets` has published its
 * fields. Reading a Minecraft class from there starts *its* initialiser, and if that one reads a field
 * of the class that is still initialising, the field is `null`:
 *
 * ```
 * java.lang.NullPointerException: Cannot invoke
 *   "net.minecraft.client.renderer.SpriteMapper.defaultNamespaceApply(String)"
 *   because "net.minecraft.client.resources.Sheets.BLOCKS_MAPPER" is null
 *     at ModelBakery.<clinit>
 *     at TextureAtlas.<clinit>          <- our mixin's static field was being built here
 *     at RenderTypes.createMovingBlockSetup
 *     at Sheets.<clinit>                <- still initialising
 * ```
 *
 * The fix in that case was to move the read into a nested class, so it happens on first *use* rather
 * than at class load. This task is what stops the next one: anything a mixin needs from the game at
 * class-load time has to be deferred the same way.
 *
 * Only the class initialiser is read, and by constant-pool reference rather than by compiling against
 * it - a mixin method body may call Minecraft as freely as it likes, because by then the class is in use
 * and every initialiser involved has long finished.
 */
val checkMixinClassInitialisers by tasks.registering {
        description = "Fails if a mixin's static initialiser reads a Minecraft class."
        group = "verification"

        val classesDir = layout.buildDirectory.dir("classes/java/main")
        val mixinsDir = classesDir.map { it.dir("dev/birb/wgpu/mixin") }
        val classpath = sourceSets["main"].runtimeClasspath

        inputs.dir(mixinsDir).withPropertyName("mixinClasses")
        inputs.files(classpath).withPropertyName("classpath")

        doLast {
                val root = mixinsDir.get().asFile
                if (!root.exists()) {
                        logger.warn("wgpu-mc: no compiled mixins at {}; run compileJava first", root)
                        return@doLast
                }

                // `Jvm.current()` and not `System.getProperty("java.home")`: this task has to disassemble with
                // the same JDK Gradle is running on, or `javap` reads a class file it does not understand.
                val javap = Jvm.current().javaExecutable.absolutePath
                val classes = classesDir.get().asFile
                val offenders = mutableListOf<String>()

                root.walkTopDown().filter { it.extension == "class" }.forEach { file ->
                        val binaryName = file.relativeTo(classes).path
                                .removeSuffix(".class")
                                .replace(File.separatorChar, '.')

                        val disassembly = providers.exec {
                                commandLine(javap, "-classpath", classes.absolutePath, "-c", "-p", binaryName)
                                isIgnoreExitValue = true
                        }.standardOutput.asText.get()

                        // Only the `static {};` block, which runs when the class is *initialised* - the thing that
                        // happens at whatever moment Minecraft first touches the class this mixin is applied to.
                        // Everything inside a method is a different question, and is not this task's business.
                        val lines = disassembly.lines()
                        val start = lines.indexOfFirst { it.trim() == "static {};" }
                        if (start < 0) {
                                return@forEach
                        }

                        // The block runs until the next member, which javap indents less than the instructions in
                        // it: every instruction of ours is indented, and the closing brace is not.
                        val initialiser = lines.drop(start + 1).takeWhile { it.isBlank() || it.startsWith(" ") }

                        initialiser.filter { it.contains("net/minecraft/") }.forEach { line ->
                                offenders.add("$binaryName: ${line.trim()}")
                        }
                }

                if (offenders.isNotEmpty()) {
                        throw GradleException(
                                "wgpu-mc: a mixin's class initialiser reads a Minecraft class, which can run before " +
                                        "that class has finished initialising and take the game down on the title screen " +
                                        "with a `null` field. Move the read into a nested holder class so it happens on " +
                                        "first use:\n" + offenders.joinToString("\n") { "  $it" }
                        )
                }
        }
}

tasks.named("check") {
        dependsOn(checkMixinClassInitialisers)
}

publishing {
        publications {
                register<MavenPublication>("mavenJava") {
                        from(components["java"])
                }
        }
        repositories {
                maven {
                        url = uri(layout.projectDirectory.dir("repo"))
                }
        }
}
