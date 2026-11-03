import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("io.github.ben-manes.versions") version "0.67.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.6.1")
    }
}

rootProject.name = "mozgobolt"

gradle.rootProject {
    tasks.withType<DependencyUpdatesTask>().configureEach {
        revision = "release"
        rejectPreReleases = true
        checkConstraints = true
    }
}
