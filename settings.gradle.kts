pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        maven {
            name = "Cotton"
            url = uri("https://server.bbkr.space/artifactory/libs-release/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "mcxr"

include("mcxr-core")
include("mcxr-play")