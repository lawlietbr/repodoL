android {
    compileSdk = 34 
    buildFeatures.buildConfig = true
    namespace = "com.lietrepo.superflix"

    defaultConfig {
        minSdk = 21

        // USE GRADLE PROPERTY (não env var)
        val tmdbApiKey = project.findProperty("TMDB_API_KEY") as? String ?: ""
        
        println("=== DEBUG BUILD ===")
        println("TMDB_API_KEY do Gradle: ${tmdbApiKey.length} chars")
        
        if (tmdbApiKey.isNotEmpty()) {
            println("✅ Chave encontrada: ${tmdbApiKey.take(4)}...")
        } else {
            println("❌ CHAVE VAZIA NO GRADLE!")
            // Fallback para env var
            val envKey = System.getenv("TMDB_API_KEY") ?: ""
            println("Fallback env var: ${envKey.length} chars")
        }

        buildConfigField(
            "String",
            "TMDB_API_KEY", 
            "\"$tmdbApiKey\""
        )
        
        // ADICIONE ISSO para debug extra
        buildConfigField(
            "Boolean",
            "DEBUG_MODE",
            "true"
        )
    }
}

// Tarefa customizada para debug (OPCIONAL - pode remover)
tasks.register("printDebugInfo") {
    doLast {
        println("=== DEBUG INFO ===")
        println("BuildConfig será gerado com:")
        println("TMDB_API_KEY: ${project.findProperty("TMDB_API_KEY")?.take(8)}...")
        println("==================")
    }
}