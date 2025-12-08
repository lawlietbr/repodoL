package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.FormBody

/**
 * Extractor para Filemoon e Fembed
 * Suporta URLs:
 * - https://filemoon.in/e/{id}
 * - https://fembed.sx/e/{id}
 * - https://fembed.sx/v/{id}
 */
class Filemoon : ExtractorApi() {
    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.in"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("FilemoonExtractor: getUrl - INÍCIO")
        val videoId = extractVideoId(url)
        if (videoId.isEmpty()) return

        // 1. URLs
        val playerEmbedUrl = if (url.contains("fembed.sx")) {
            "https://fembed.sx/e/$videoId"
        } else {
            "https://filemoon.in/e/$videoId"
        }

        val apiUrl = "https://fembed.sx/api.php?s=$videoId&c="

        try {
            // 2. FAZER GET INICIAL PARA OBTER COOKIES DE SESSÃO
            println("FilemoonExtractor: Fazendo GET em $playerEmbedUrl para obter cookies...")

            val initialResponse = app.get(playerEmbedUrl, headers = getHeaders(playerEmbedUrl, referer))

            if (!initialResponse.isSuccessful) {
                println("FilemoonExtractor: ERRO: GET inicial falhou com status ${initialResponse.code}")
                return
            }

            val sessionCookies = initialResponse.cookies
            println("FilemoonExtractor: Cookies de sessão obtidos: ${sessionCookies.size} item(s)")

            // 3. PREPARAR O POST BODY
            val formDataMap = mapOf(
                "action" to "getPlayer",
                "lang" to "DUB",
                "key" to "MTg1ODIz" // Novo token

            )

            val requestBody = FormBody.Builder().apply {
                formDataMap.forEach { (key, value) ->
                    add(key, value)
                }
            }.build()

            // 4. PREPARAR HEADERS
            val postHeaders = getHeaders(apiUrl, playerEmbedUrl).toMutableMap()
            postHeaders["Origin"] = playerEmbedUrl.substringBefore("/e/")
            postHeaders.remove("Upgrade-Insecure-Requests")

            println("FilemoonExtractor: Fazendo requisição POST para Player HTML")

            // 5. Executar o POST
            val response = app.post(
                apiUrl,
                headers = postHeaders,
                requestBody = requestBody,
                referer = playerEmbedUrl,
                cookies = sessionCookies
            )

            // 6. PROCESSAR RESPOSTA
            if (!response.isSuccessful) {
                println("FilemoonExtractor: ERRO: POST falhou com status ${response.code}. Resposta do corpo: ${response.text.take(500)}")
                return
            }

            val finalPlayerHtml = response.text
            println("FilemoonExtractor: Player HTML obtido (${finalPlayerHtml.length} chars)")
println("FilemoonExtractor: DEBUG HTML: $finalPlayerHtml")
            // 7. Extrair M3U8
            val m3u8Url = extractM3u8Url(finalPlayerHtml)

            if (m3u8Url != null) {
                println("FilemoonExtractor: M3U8 FINAL encontrado: $m3u8Url")

                // 8. Gerar Streams M3U8
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = playerEmbedUrl,
                    headers = getHeaders(m3u8Url, playerEmbedUrl)
                )

                println("FilemoonExtractor: ${links.size} link(s) gerado(s)")
                links.forEach(callback)
                return
            } else {
                println("FilemoonExtractor: ERRO: Não encontrou URL M3U8 no HTML FINAL do POST.")
            }

        } catch (e: Exception) {
            println("FilemoonExtractor: EXCEÇÃO: ${e.message}")
            e.printStackTrace()
        }
        // FIM DE getUrl
    }
    
    // ====================================================================
    // MÉTODOS AUXILIARES (DEVE ESTAR DENTRO DA CLASSE, MAS FORA DE getUrl)
    // ====================================================================

    private fun extractVideoId(url: String): String {
        val patterns = listOf(
            Regex("""/e/(\d+)"""),            
            Regex("""/v/([a-zA-Z0-9]+)"""),   
            Regex("""embed/([a-zA-Z0-9]+)""") 
        )

        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return url.substringAfterLast("/").substringBefore("?").substringBefore("-")
    }

    private fun extractM3u8Url(html: String): String? {
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
