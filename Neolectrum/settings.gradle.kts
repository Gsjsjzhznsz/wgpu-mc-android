// Neolectrum is a repository of its own: the mod is built here, from this directory, and the Rust
// engine it drives is a separate checkout (by default beside this one, `../wgpu-mc-neo`).
//
// That makes this a one-project build, and the plugin repositories are the part that matters:
// NeoGradle and Kotlin for Forge are not on the Gradle Plugin Portal, and a settings script is the
// only place a plugin can be resolved from before the build script itself is compiled.
pluginManagement {
	repositories {
		maven {
			name = "NeoForged"
			setUrl("https://maven.neoforged.net/releases")
		}
		maven {
			name = "Kotlin for Forge"
			setUrl("https://thedarkcolour.github.io/KotlinForForge/")
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

rootProject.name = "neolectrum"
