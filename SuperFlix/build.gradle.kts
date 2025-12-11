version = 1

cloudstream {
    description = "SuperFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("lietbr")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://superflix21.lol/assets/logo.png"
}

plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        
        // DEBUG: Mostrar informações
        println("=== CONFIGURANDO BUILDCONFIG PARA SUPERFLIX ===")
        
        // Tentar obter a chave de várias fontes
        var tmdbApiKey = ""
        
        // 1. Tentar do environment
        val envKey = System.getenv("TMDB_API_KEY")
        if (envKey != null && envKey.isNotEmpty()) {
            tmdbApiKey = envKey
            println("✅ TMDB_API_KEY obtida do environment")
            println("   Tamanho: ${tmdbApiKey.length}")
        } 
        // 2. Tentar do gradle.properties
        else if (project.hasProperty("TMDB_API_KEY")) {
            tmdbApiKey = project.property("TMDB_API_KEY") as String
            println("✅ TMDB_API_KEY obtida do gradle.properties")
            println("   Tamanho: ${tmdbApiKey.length}")
        }
        // 3. Tentar do local.properties
        else {
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                val localProperties = java.util.Properties()
                localProperties.load(localPropertiesFile.inputStream())
                tmdbApiKey = localProperties.getProperty("TMDB_API_KEY", "")
                if (tmdbApiKey.isNotEmpty()) {
                    println("✅ TMDB_API_KEY obtida do local.properties")
                    println("   Tamanho: ${tmdbApiKey.length}")
                } else {
                    println("⚠️  TMDB_API_KEY não encontrada em nenhuma fonte")
                    println("   O plugin funcionará sem dados do TMDB")
                }
            } else {
                println("⚠️  local.properties não encontrado")
            }
        }
        
        // Configurar no BuildConfig
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        
        if (tmdbApiKey.isNotEmpty()) {
            println("✅ TMDB_API_KEY configurada no BuildConfig")
            println("   Primeiros 8 chars: ${tmdbApiKey.substring(0, minOf(8, tmdbApiKey.length))}...")
        } else {
            println("⚠️  AVISO: TMDB_API_KEY será string vazia no BuildConfig")
        }
        
        println("=== FIM CONFIGURAÇÃO ===")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${rootProject.ext["kotlin_version"]}")
    // ... outras dependências
}

// Task de debug personalizada
tasks.register("printDebugInfo") {
    doLast {
        println("=== DEBUG INFO SUPERFLIX ===")
        println("Project dir: ${project.projectDir}")
        println("Build dir: ${project.buildDir}")
        
        // Verificar environment
        val envKey = System.getenv("TMDB_API_KEY")
        println("Environment TMDB_API_KEY: ${if (envKey != null) "DEFINIDA (${envKey.length} chars)" else "NÃO DEFINIDA"}")
        
        // Verificar project properties
        val projectKey = if (project.hasProperty("TMDB_API_KEY")) project.property("TMDB_API_KEY") as String else null
        println("Project property TMDB_API_KEY: ${if (projectKey != null) "DEFINIDA (${projectKey.length} chars)" else "NÃO DEFINIDA"}")
        
        // Verificar local.properties
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            val localProperties = java.util.Properties()
            localProperties.load(localPropertiesFile.inputStream())
            val localKey = localProperties.getProperty("TMDB_API_KEY", "")
            println("Local.properties TMDB_API_KEY: ${if (localKey.isNotEmpty()) "DEFINIDA (${localKey.length} chars)" else "NÃO DEFINIDA"}")
        } else {
            println("Local.properties: arquivo não encontrado")
        }
        
        println("=== FIM DEBUG ===")
    }
}