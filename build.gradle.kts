plugins {
    kotlin("jvm") version "1.9.22"
}

group = "com.grappenmaker"
version = "0.1"

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

dependencies {
    implementation("io.github.770grappenmaker:mappings-util:0.1.2")
    implementation("com.grappenmaker:nasty-jvm-util") {
        capabilities {
            requireCapabilities("com.grappenmaker:nasty-jvm-util-reflect")
        }
    }

    api("org.spongepowered:mixin:0.8.5")
    implementation("com.google.guava:guava:21.0")
    implementation("com.google.code.gson:gson:2.2.4")
}

kotlin {
    jvmToolchain(8)
    explicitApi()
}

tasks {
    jar {
        manifest {
            attributes(
                "Premain-Class" to "com.grappenmaker.mixin.MixinSandboxKt",
                "Agent-Class" to "com.grappenmaker.mixin.MixinSandboxKt",
                "Can-Retransform-Classes" to "true"
            )
        }

        from(configurations.runtimeClasspath.map { c -> c.map { if (it.isDirectory) it else zipTree(it) } })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}