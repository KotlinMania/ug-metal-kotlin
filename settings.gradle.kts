pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ug-metal-kotlin"

listOf(
    "../half-kotlin",
    "../ug-kotlin",
    "../candle-metal-kernels-kotlin",
).forEach { path ->
    if (file(path).exists()) {
        includeBuild(path)
    }
}
