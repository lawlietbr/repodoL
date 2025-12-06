package com.UltraCine

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// Importa as classes que estão no mesmo pacote (com.UltraCine)
// Se PlayEmbedApiSite e EmbedPlayExtractor estão no mesmo diretório, 
// o import completo 'com.UltraCine.NomeDaClasse' pode ser necessário, 
// ou apenas use o nome da classe, dependendo da configuração.
import com.UltraCine.PlayEmbedApiSite
import com.UltraCine.EmbedPlayExtractor

@CloudstreamPlugin
class UltraCineProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(UltraCine()) 
        
        // Registro dos Extratores (Use o nome exato das classes)
        registerExtractorAPI(PlayEmbedApiSite())
        registerExtractorAPI(EmbedPlayExtractor()) 
    }
}

