package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * Extractor para Filemoon e Fembed
 * Suporta URLs:
 * - https://filemoon.in/e/{id}
 * - https://fembed.sx/e/{id}
 * - https://fembed.sx/v/{id}
 */
open class Filemoon : ExtractorApi() {
    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.in"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("FilemoonExtractor: Processando URL: $url")
        
        // Extrair ID do vídeo
        val videoId = extractVideoId(url)
        if (videoId.isEmpty()) {
            println("FilemoonExtractor: Não consegui extrair ID da URL")
            return
        }
        
        println("FilemoonExtractor: Video ID: $videoId")
        
        try {
            // Se for URL do fembed, converter para filemoon
            val processedUrl = if (url.contains("fembed.sx")) {
                "https://filemoon.in/e/$videoId"
            } else {
                url
            }
            
            println("FilemoonExtractor: Acessando: $processedUrl")
            
            // Fazer requisição com headers
            val playerResponse = app.get(
                processedUrl,
                headers = getHeaders(processedUrl, referer)
            ).text
            
            println("FilemoonExtractor: Página carregada (${playerResponse.length} chars)")
            
            // Procurar iframe
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframeMatch = iframeRegex.find(playerResponse)
            
            if (iframeMatch != null) {
                var iframeUrl = iframeMatch.groupValues[1]
                println("FilemoonExtractor: Iframe encontrado: $iframeUrl")
                
                // Garantir que a URL do iframe seja completa
                if (!iframeUrl.startsWith("http")) {
                    iframeUrl = "https:$iframeUrl"
                }
                
                // Acessar o iframe
                val iframeResponse = app.get(
                    iframeUrl,
                    headers = getHeaders(iframeUrl, processedUrl)
                ).text
                
                println("FilemoonExtractor: Iframe carregado (${iframeResponse.length} chars)")
                
                // Procurar URL m3u8
                val m3u8Url = extractM3u8Url(iframeResponse)
                
                if (m3u8Url != null) {
                    println("FilemoonExtractor: M3U8 encontrado: ${m3u8Url.take(100)}...")
                    
                    // Extrair streams do m3u8
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = m3u8Url,
                        referer = iframeUrl,
                        headers = getHeaders(m3u8Url, iframeUrl)
                    ).forEach(callback)
                    
                    println("FilemoonExtractor: Links gerados com sucesso")
                    return
                }
            }
            
            // Tentar extrair URL m3u8 diretamente da página
            val directM3u8 = extractM3u8Url(playerResponse)
            if (directM3u8 != null) {
                println("FilemoonExtractor: M3U8 encontrado diretamente")
                
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = directM3u8,
                    referer = processedUrl,
                    headers = getHeaders(directM3u8, processedUrl)
                ).forEach(callback)
                return
            }
            
            println("FilemoonExtractor: Não consegui encontrar URL m3u8")
            
        } catch (e: Exception) {
            println("FilemoonExtractor: ERRO - ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun extractVideoId(url: String): String {
        // Extrair ID de diferentes formatos
        val patterns = listOf(
            Regex("""/e/(\d+)"""),            // /e/1421
            Regex("""/v/([a-zA-Z0-9]+)"""),   // /v/abc123
            Regex("""embed/([a-zA-Z0-9]+)""") // embed/abc123
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        
        // Fallback: último segmento
        return url.substringAfterLast("/").substringBefore("?").substringBefore("-")
    }
    
    private fun extractM3u8Url(html: String): String? {
        // Padrões para encontrar URLs m3u8
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""src\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""hls\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https://[^\s"']+\.m3u8[^\s"']*)""")
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(html)
            for (match in matches) {
                val url = match.groupValues.getOrNull(1) ?: continue
                if (url.contains(".m3u8")) {
                    return url
                }
            }
        }
        
        return null
    }
    
    private fun getHeaders(url: String, referer: String?): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Referer" to (referer ?: "https://fembed.sx/"),
            "Origin" to "https://fembed.sx",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "max-age=0"
        )
    }
}
