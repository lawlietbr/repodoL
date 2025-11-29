buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")  // ← CORRETO
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

subprojects {
    // ← SÓ ISSO. Nada de android.library ou kotlin-android aqui!
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    extensions.configure<com.lagradost.cloudstream3.gradle.CloudstreamExtension>("cloudstream") {
        repo = System.getenv("GITHUB_REPOSITORY") ?: "euluan1912/cloudstream-brazil-providers"
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")  // ← CORRETO

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.19.1")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDirectory)
}