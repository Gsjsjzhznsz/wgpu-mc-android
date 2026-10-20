// One-project build: the mod is compiled here, from this directory, against the shared trees one
// directory up. The plugin repositories are the part that matters - loom is not on the Gradle
// Plugin Portal's default mirror set for every version, and the toolchain resolver is what lets
// Gradle fetch the Java 17 a 1.20.1 build compiles against when the host JVM is newer (CI runs
// Java 25, local machines run whatever they have).
pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			setUrl("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	// Java toolchain auto-provisioning: Minecraft 1.20.1 is a Java 17 game, and the JVM running
	// this build may be 21 or 25. Without the resolver a toolchain the host does not have stops
	// the build at configuration time with a table of "no matching toolchain" rows.
	id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "neolectrum-1-20-1-fabric"
