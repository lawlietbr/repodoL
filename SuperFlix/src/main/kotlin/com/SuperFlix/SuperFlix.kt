package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }

        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val title = link.selectFirst("img")?.attr("alt")
                        ?: link.selectFirst(".rec-title, .title, h2, h3)")?.text()
                        ?: href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")

                        val searchResponse = if (isSerie) {
                            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }

                        home.add(searchResponse)
                    }
                }
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".rec-title, .movie-title, h2, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null

        val href = attr("href") ?: selectFirst("a")?.attr("href") ?: return null

        val poster = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let {
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        val jsonLd = extractJsonLd(html)

        val title = jsonLd.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")

        val plot = jsonLd.description ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".syn, .description")?.text()

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }

        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()

        val director = jsonLd.director?.firstOrNull()

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"

        return if (isSerie) {
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            val videoData = findFembedUrl(document) ?: ""

            newMovieLoadResponse(title, url, TvType.Movie, videoData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("button.bd-play[data-url]").forEach { button ->
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            var episodeTitle = "Episódio $episodeNum"

            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4")
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }

        return episodes
    }

    private fun findFembedUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        val anyButton = document.selectFirst("button[data-url*='fembed']")
        if (anyButton != null) {
            return anyButton.attr("data-url")
        }

        val html = document.html()
        val patterns = listOf(
            Regex("""https?://[^"'\s]*fembed[^"'\s]*/e/\w+"""),
            Regex("""data-url=["'](https?://[^"']*fembed[^"']+)["']"""),
            Regex("""src\s*[:=]\s*["'](https?://[^"']*fembed[^"']+)["']""")
        )

        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Carregando links de: $data")
        
        // CORRIGIDO: Extrair ID corretamente
        val videoId = extractVideoId(data)
        if (videoId.isEmpty()) {
            println("SuperFlix: ERRO - Não consegui extrair ID do vídeo")
            return false
        }
        
        println("SuperFlix: ID do vídeo extraído: $videoId")
        println("SuperFlix: URL completa: $data")
        
        return try {
            extractFembedVideo(videoId, data, callback)
        } catch (e: Exception) {
            println("SuperFlix: ERRO - ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun extractVideoId(data: String): String {
        // Extrair ID de diferentes formatos de URL
        val patterns = listOf(
            Regex("""/e/(\d+)"""),                   // https://fembed.sx/e/85517
            Regex("""/e/(\d+)/\d+-\d+"""),           // https://fembed.sx/e/85517/1-1
            Regex("""/v/([a-zA-Z0-9]+)"""),          // https://fembed.sx/v/abc123
            Regex("""\?.*[&?]id=([^&]+)""")          // ?id=12345
        )
        
        for (pattern in patterns) {
            val match = pattern.find(data)
            if (match != null && match.groupValues.size > 1) {
                val id = match.groupValues[1]
                println("SuperFlix: ID extraído via padrão '$pattern': $id")
                return id
            }
        }
        
        // Fallback: pegar último segmento antes de parâmetros
        val simpleId = data.substringAfterLast("/").substringBefore("?").substringBefore("-")
        println("SuperFlix: ID fallback: $simpleId")
        return simpleId
    }

    private suspend fun extractFembedVideo(
        videoId: String,
        originalUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Extraindo vídeo do Fembed ID: $videoId")
        println("SuperFlix: URL original: $originalUrl")
        
        val baseUrl = "https://fembed.sx"
        val apiUrl = "$baseUrl/api.php?s=$videoId&c="
        
        println("SuperFlix: API URL: $apiUrl")
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to baseUrl,
            "Origin" to baseUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        // PASSO 1: Tentar Dublado
        println("SuperFlix: === PASSO 1: Tentando Dublado ===")
        
        val postData = mapOf(
            "action" to "getPlayer",
            "lang" to "DUB",
            "key" to Base64.getEncoder().encodeToString("0".toByteArray())
        )
        
        try {
            println("SuperFlix: Enviando POST para: $apiUrl")
            println("SuperFlix: Dados: $postData")
            
            val response = app.post(apiUrl, headers = headers, data = postData)
            
            println("SuperFlix: Status code: ${response.code}")
            println("SuperFlix: Sucesso? ${response.isSuccessful}")
            
            if (response.isSuccessful) {
                val responseText = response.text
                println("SuperFlix: Resposta completa (${responseText.length} chars):")
                println("SuperFlix: Primeiros 500 chars: ${responseText.take(500)}")
                println("SuperFlix: Últimos 500 chars: ${responseText.takeLast(500)}")
                
                // Procurar por URL real na resposta
                val m3u8Url = findRealM3u8Url(responseText)
                if (m3u8Url != null) {
                    println("SuperFlix: ✅ URL REAL encontrada: $m3u8Url")
                    
                    // Testar se a URL funciona
                    if (testUrl(m3u8Url)) {
                        println("SuperFlix: ✅ URL testada e funcionando!")
                        addVideoLink(m3u8Url, "Dublado", baseUrl, callback)
                        return true
                    } else {
                        println("SuperFlix: ⚠️ URL encontrada mas não funciona")
                    }
                } else {
                    println("SuperFlix: ❌ Nenhuma URL encontrada na resposta")
                    println("SuperFlix: Analisando resposta para padrões...")
                    
                    // Verificar se é JSON
                    if (responseText.trim().startsWith("{") || responseText.trim().startsWith("[")) {
                        println("SuperFlix: Resposta parece ser JSON")
                        try {
                            // Tentar parsear usando a função correta do CloudStream
                            val json = response.parsedSafe<Map<String, Any>>()
                            if (json != null) {
                                println("SuperFlix: JSON parseado com $json chaves: ${json.keys}")
                                
                                // Procurar em todos os campos
                                for ((key, value) in json) {
                                    val valueType = when (value) {
                                        is String -> "String"
                                        is Number -> "Number"
                                        is Boolean -> "Boolean"
                                        is List<*> -> "List"
                                        is Map<*, *> -> "Map"
                                        else -> value?.javaClass?.simpleName ?: "null"
                                    }
                                    println("SuperFlix: Campo '$key' = tipo: $valueType")
                                    
                                    // Se for string, verificar se contém URL
                                    if (value is String && value.contains("http")) {
                                        println("SuperFlix: Possível URL no campo '$key': $value")
                                        val foundUrl = findRealM3u8Url(value)
                                        if (foundUrl != null && testUrl(foundUrl)) {
                                            addVideoLink(foundUrl, "Dublado", baseUrl, callback)
                                            return true
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("SuperFlix: Erro ao parsear JSON: ${e.message}")
                        }
                    }
                }
            } else {
                println("SuperFlix: ❌ API falhou com código: ${response.code}")
                println("SuperFlix: Mensagem de erro: ${response.text}")
            }
        } catch (e: Exception) {
            println("SuperFlix: ❌ Erro ao obter Dublado: ${e.message}")
            e.printStackTrace()
        }
        
        // PASSO 2: Tentar Legendado
        println("SuperFlix: === PASSO 2: Tentando Legendado ===")
        
        val subPostData = mapOf(
            "action" to "getPlayer",
            "lang" to "SUB",
            "key" to Base64.getEncoder().encodeToString("0".toByteArray())
        )
        
        try {
            val response = app.post(apiUrl, headers = headers, data = subPostData)
            
            println("SuperFlix: Status Legendado: ${response.code}")
            
            if (response.isSuccessful) {
                val responseText = response.text
                println("SuperFlix: Resposta Legendado (${responseText.length} chars)")
                
                val m3u8Url = findRealM3u8Url(responseText)
                if (m3u8Url != null) {
                    println("SuperFlix: URL Legendado encontrada: $m3u8Url")
                    
                    if (testUrl(m3u8Url)) {
                        println("SuperFlix: ✅ URL Legendado testada e funcionando!")
                        addVideoLink(m3u8Url, "Legendado", baseUrl, callback)
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro ao obter Legendado: ${e.message}")
        }
        
        // PASSO 3: Tentar análise mais profunda
        println("SuperFlix: === PASSO 3: Análise profunda ===")
        
        // Tentar acessar a página diretamente
        val videoPageUrl = "$baseUrl/e/$videoId"
        println("SuperFlix: Tentando acessar página: $videoPageUrl")
        
        try {
            val pageResponse = app.get(videoPageUrl, headers = headers)
            if (pageResponse.isSuccessful) {
                val pageHtml = pageResponse.text
                println("SuperFlix: Página HTML obtida (${pageHtml.length} chars)")
                
                // Procurar por iframes ou scripts
                val m3u8Url = findM3u8InPage(pageHtml)
                if (m3u8Url != null) {
                    println("SuperFlix: URL encontrada na página: $m3u8Url")
                    
                    if (testUrl(m3u8Url)) {
                        addVideoLink(m3u8Url, "HTML", baseUrl, callback)
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro ao acessar página: ${e.message}")
        }
        
        println("SuperFlix: ❌❌❌ NENHUM LINK FUNCIONA ❌❌❌")
        return false
    }

    private fun findRealM3u8Url(text: String): String? {
        println("SuperFlix: Procurando URLs m3u8 no texto...")
        
        // Padrões mais específicos baseados nas suas imagens
        val patterns = listOf(
            // Padrão exato das suas imagens
            Regex("""https?://be\d+\.rcr\d+\.[a-z]+\d+\.[a-z0-9]+\.com/hls2/\d+/\d+/[a-z0-9]+_h/master\.m3u8\?[^"'\s]+"""),
            
            // Padrões gerais
            Regex("""https?://[^"'\s]+\.m3u8\?[^"'\s]*t=[^"'\s&]+[^"'\s]*"""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[a-z0-9]+\.rcr\d+\.[a-z]+\d+\.[a-z0-9]+\.com/[^"'\s]+\.m3u8[^"'\s]*)"""),
            
            // Em JSON
            Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["']url["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["']src["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            
            // Segmentos .ts (indicam m3u8 próximo)
            Regex("""https?://[^"'\s]+/seg-\d+-v\d+-a\d+\.ts\?[^"'\s]+""")
        )
        
        for ((index, pattern) in patterns.withIndex()) {
            val matches = pattern.findAll(text)
            var count = 0
            matches.forEach { match ->
                count++
                var url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                url = url.trim()
                
                println("SuperFlix: Padrão $index encontrou: $url")
                
                if (url.isNotBlank() && url.contains(".m3u8")) {
                    // Verificar se tem parâmetros necessários
                    if (url.contains("t=") && url.contains("s=")) {
                        println("SuperFlix: ✅ URL parece válida (tem parâmetros t e s)")
                        return url
                    }
                }
            }
            if (count > 0) {
                println("SuperFlix: Padrão $index encontrou $count ocorrências")
            }
        }
        
        return null
    }

    private fun findM3u8InPage(html: String): String? {
        // Procurar em iframes
        val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
        val iframeMatch = iframePattern.find(html)
        if (iframeMatch != null) {
            val iframeUrl = iframeMatch.groupValues[1]
            println("SuperFlix: Iframe encontrado: $iframeUrl")
            
            // Se o iframe for do próprio Fembed, pode conter o player
            if (iframeUrl.contains("fembed") && iframeUrl.contains("/v/")) {
                return iframeUrl
            }
        }
        
        // Procurar em scripts
        val scriptPattern = Regex("""<script[^>]*>([^<]*)</script>""", RegexOption.DOT_MATCHES_ALL)
        val scriptMatches = scriptPattern.findAll(html)
        
        scriptMatches.forEach { match ->
            val scriptContent = match.groupValues[1]
            if (scriptContent.contains("m3u8") || scriptContent.contains("hls2")) {
                println("SuperFlix: Script contém m3u8/hls2")
                
                // Extrair URLs do script
                val urls = findRealM3u8Url(scriptContent)
                if (urls != null) {
                    return urls
                }
            }
        }
        
        return null
    }

    private suspend fun testUrl(url: String): Boolean {
        println("SuperFlix: Testando URL: $url")
        
        return try {
            val testHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Referer" to "https://fembed.sx/"
            )
            
            val response = app.get(url, headers = testHeaders)
            println("SuperFlix: Teste HTTP: ${response.code}")
            
            if (response.isSuccessful) {
                val content = response.text
                println("SuperFlix: Conteúdo (primeiros 200 chars): ${content.take(200)}")
                
                // Verificar se é um m3u8 válido
                if (content.contains("#EXTM3U")) {
                    if (content.contains(".ts")) {
                        println("SuperFlix: ✅ M3U8 válido (contém EXTM3U e .ts)")
                        return true
                    } else {
                        // Verificar se tem outros segmentos
                        if (content.contains("#EXT-X-STREAM-INF") || content.contains("#EXTINF")) {
                            println("SuperFlix: ⚠️ M3U8 contém EXTM3U mas sem .ts visível")
                            println("SuperFlix: Pode ser uma playlist master")
                            // Pode ser uma playlist master, então ainda é válida
                            return true
                        }
                    }
                }
                println("SuperFlix: ❌ Não é um M3U8 válido")
            }
            false
        } catch (e: Exception) {
            println("SuperFlix: ❌ Erro ao testar URL: ${e.message}")
            false
        }
    }

    private suspend fun addVideoLink(
        url: String,
        language: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        println("SuperFlix: Adicionando link: $language")
        println("SuperFlix: URL final: $url")
        
        // Determinar qualidade
        val quality = determineQualityFromUrl(url)
        println("SuperFlix: Qualidade detectada: $quality")
        
        val link = newExtractorLink(
            source = this.name,
            name = "$name ($language)",
            url = url
        )
        
        callback.invoke(link)
        
        println("SuperFlix: ✅ Link adicionado com sucesso!")
    }

    private fun determineQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080") || url.contains("fullhd") || url.contains("fhd") -> Qualities.P1080.value
            url.contains("720") || url.contains("hd") -> Qualities.P720.value
            url.contains("480") || url.contains("sd") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
        val tmdbId: String? = null,
        val type: String? = null
    )

    private fun extractJsonLd(html: String): JsonLdInfo {
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)

        matches.forEach { match ->
            try {
                val json = match.groupValues[1].trim()
                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {

                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)

                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }

                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }

                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }

                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')

                    val type = if (json.contains("\"@type\":\"Movie\"")) "Movie" else "TVSeries"

                    return JsonLdInfo(
                        title = title,
                        year = null,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        tmdbId = tmdbId,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continua
            }
        }

        return JsonLdInfo()
    }
}