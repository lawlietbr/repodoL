package com.SuperFlix

import android.content.Context
import com.lagradost.cloudstream3.extractors.Filemoon
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.registerExtractor

@CloudstreamPlugin
class SuperFlixProviderPlugin: Plugin() {
    override fun load(context: Context) {
        println("SuperFlixProviderPlugin: load - INICIANDO PLUGIN")
        
        // Registrar o provider principal
        registerMainAPI(SuperFlix())
        println("SuperFlixProviderPlugin: load - SuperFlix provider registrado")

        // Registrar o extractor do Filemoon
        registerExtractorAPI(Filemoon())
        println("SuperFlixProviderPlugin: load - Filemoon extractor registrado")
        
        // Alternativa: Registrar diretamente
        registerExtractor(Filemoon())
        println("SuperFlixProviderPlugin: load - Filemoon extractor registrado com registerExtractor")
    }
}