rootProject.name = "mixin-sandbox"

includeBuild("nasty-jvm-util") {
    dependencySubstitution {
        substitute(module("com.grappenmaker:nasty-jvm-util")).using(project(":"))
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}