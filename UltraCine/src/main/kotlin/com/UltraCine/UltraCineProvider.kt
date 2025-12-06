
package com.UltraCine

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class UltraCineProvider : BasePlugin() {
        override fun load() {
        registerMainAPI(UltraCine())
        
        // 1. REGISTRA A CLASSE QUE LIDA COM 'playembedapi.site'
        registerExtractorAPI(PlayEmbedApiSite()) 
        
        // 2. REGISTRA A CLASSE QUE LIDA COM 'embedplay.upns.ink'
        registerExtractorAPI(EmbedPlayExtractor()) // Use o nome exato da classe no seu EmbedPlayExtractor.kt
    }
