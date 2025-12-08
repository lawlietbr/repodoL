package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.FormBody // <<<<< CORREÇÃO 1: Adicionar FormBody

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

    // ESSENCIAL: Diz ao Cloudstream quais URLs este Extractor suporta

    override suspend fun getUrl(
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    println("FilemoonExtractor: getUrl - INÍCIO")
    val videoId = extractVideoId(url)
    if (videoId.isEmpty()) return

    val playerEmbedUrl = if (url.contains("fembed.sx")) {
        "https://fembed.sx/e/$videoId"
    } else {
        "https://filemoon.in/e/$videoId"
    }

    val apiUrl = "https://fembed.sx/api.php?s=$videoId&c="
    
    // =========================================================
    // 1. FAZER GET INICIAL PARA OBTER COOKIES DE SESSÃO
    // =========================================================
    println("FilemoonExtractor: Fazendo GET em $playerEmbedUrl para obter cookies...")
    
    val initialResponse = app.get(playerEmbedUrl, headers = getHeaders(playerEmbedUrl, referer))
    
    if (!initialResponse.isSuccessful) {
        println("FilemoonExtractor: ERRO: GET inicial falhou com status ${initialResponse.code}")
        return
    }

    // O Cloudstream/okhttp gerencia os cookies automaticamente,
    // mas vamos garantir que a URL do GET seja usada como Referer para o POST.
    val sessionCookies = initialResponse.cookies // Captura os cookies da primeira requisição
    println("FilemoonExtractor: Cookies de sessão obtidos: ${sessionCookies.size} item(s)")

    // 2. CORPO DA REQUISIÇÃO POST (Form Data)
    val formDataMap = mapOf(
        "action" to "getPlayer",
        "lang" to "DUB", 
        "key" to "MA==" 
    )
    
    val requestBody = FormBody.Builder().apply {
        formDataMap.forEach { (key, value) ->
            add(key, value)
        }
    }.build()

    // 3. HEADERS para o POST
    val postHeaders = getHeaders(apiUrl, playerEmbedUrl).toMutableMap()
    postHeaders["Origin"] = playerEmbedUrl.substringBefore("/e/") 
    
    // Remover o "Upgrade-Insecure-Requests" que não faz sentido no POST da API
    postHeaders.remove("Upgrade-Insecure-Requests") 
    
    println("FilemoonExtractor: Fazendo requisição POST para Player HTML (com cookies de sessão)")

    // 4. Executar o POST
    val response = app.post(
        apiUrl, 
        headers = postHeaders,
        requestBody = requestBody,
        referer = playerEmbedUrl,
        cookies = sessionCookies // <<<<< CHAVE: USAR OS COOKIES DA SESSÃO AQUI
    )

    // ... (o restante do código de verificação e extração de M3U8 permanece o mesmo) ...
    if (!response.isSuccessful) {
        println("FilemoonExtractor: ERRO: POST falhou com status ${response.code}. Resposta do corpo: ${response.text.take(500)}") // Adicione log do corpo para debug
        return
    }

    val finalPlayerHtml = response.text
    println("FilemoonExtractor: Player HTML obtido (${finalPlayerHtml.length} chars)")
    // ... (continua a extração do M3U8) ...
    // ...
}

            // 5. Extrair M3U8 do HTML de resposta
            val m3u8Url = extractM3u8Url(finalPlayerHtml) // <<<<< CORREÇÃO: Método adicionado abaixo

            if (m3u8Url != null) {
                println("FilemoonExtractor: M3U8 FINAL encontrado: $m3u8Url")

                // 6. Gerar Streams M3U8
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = playerEmbedUrl,
                    headers = getHeaders(m3u8Url, playerEmbedUrl) // <<<<< CORREÇÃO: Método adicionado abaixo
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
    }
    
    // ====================================================================
    // MÉTODOS AUXILIARES REINSERIDOS (CORRIGEM 'Unresolved reference')
    // ====================================================================

    private fun extractVideoId(url: String): String {
        // Implementação do extractVideoId que você tinha
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
        // Implementação do extractM3u8Url que você tinha
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
        // Implementação do getHeaders que você tinha
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
