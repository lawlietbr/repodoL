// =========================================================================
// CRÍTICO: Define onde o Gradle deve procurar por PLUGINS (Resolve o erro: Plugin not found)
// =========================================================================
pluginManagement {
    repositories {
        // Obrigatório: Repositório padrão do Gradle para plugins
        gradlePluginPortal()
        // Repositórios padrões para a maioria dos plugins
        mavenCentral()
        google() 
        // Repositório para dependências do Cloudstream (usado para plugins e libs)
        maven("https://jitpack.io")
    }
}

// =========================================================================
// Define onde o Gradle deve procurar por ARTEFATOS/DEPENDÊNCIAS (Libs como okhttp, jsoup)
// =========================================================================
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Define o nome do projeto raiz
rootProject.name = "CloudstreamPlugins"

// Inclusão manual dos módulos (Lista todos os seus provedores)
include(":AnimeFHD")
include(":AnimesCloud")
include(":AnimesDigital")
include(":AnimesOnlineNet")
include(":Anroll")
include(":DonghuaNoSekai")
include(":NovelasFlix")
include(":StarckFilmes")
include(":SuperFlix")
include(":TopFilmes")
include(":Vizer")
// include(":PobreFlix") // (Deixei os comentados como exemplo)
// include(":Streamberry")
// include(":NetCine")
