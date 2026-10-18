pluginManagement {
        repositories {
                maven("https://maven.fabricmc.net/")
                mavenCentral()
                gradlePluginPortal()
        }
}

// **A second loader for the same renderer.** This module compiles the sources that live in
// `../Neolectrum/src` against Fabric, excluding the handful of files whose job is loader work
// (the `@Mod` entrypoints and NeoForge's early loading screen), and adds its own entrypoints
// and metadata in `src/main` here.
rootProject.name = "wgpu_mc-fabric"
