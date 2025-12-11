version = 1

cloudstream {
    description = "SuperFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("lietbr")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://superflix21.lol/assets/logo.png"
}

android {
    compileSdk = 34 
    buildFeatures.buildConfig = true

    namespace = "com.lietrepo.superflix"
    defaultConfig {
        minSdk = 21

        
        buildConfigField(
            "String",
            "TMDB_API_KEY", 
            "\"$tmdbApiKey\"" 
        )
    }
}