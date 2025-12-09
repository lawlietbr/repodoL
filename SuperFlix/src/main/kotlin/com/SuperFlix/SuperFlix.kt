package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val useMpv = true // Ativar player MPV para HLS

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("SuperFlix: getMainPage - page=$page, request=${request.name}")
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
                    val imgElement = link.selectFirst("img")
                    val altTitle = imgElement?.attr("alt") ?: ""

                    val titleElement = link.selectFirst(".rec-title, .title, h2, h3")
                    val elementTitle = titleElement?.text() ?: ""

                    val title = if (altTitle.isNotBlank()) altTitle
                        else if (elementTitle.isNotBlank()) elementTitle
                        else href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
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
        val titleElement = selectFirst(".rec-title, .movie-title, h2, h3, .title")
        val title = titleElement?.text() ?: selectFirst("img")?.attr("alt") ?: return null

        val elementHref = attr("href")
        val href = if (elementHref.isNotBlank()) elementHref else selectFirst("a")?.attr("href")
        if (href.isNullOrBlank()) return null

        val imgElement = selectFirst("img")
        val posterSrc = imgElement?.attr("src")
        val posterDataSrc = imgElement?.attr("data-src")
        val poster = if (posterSrc.isNullOrBlank()) {
            posterDataSrc?.let { fixUrl(it) }
        } else {
            fixUrl(posterSrc)
        }

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
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl).document

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("SuperFlix: load - URL: $url")
        val document = app.get(url).document

        val jsonLd = extractJsonLd(document.html())
        val titleElement = document.selectFirst("h1, .title")
        val scrapedTitle = titleElement?.text()
        val title = jsonLd.title ?: scrapedTitle ?: return null

        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: ogImage?.let { fixUrl(it) }?.replace("/w500/", "/original/")

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description")?.text()
        val plot = jsonLd.description ?: description ?: synopsis

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }
        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        val director = jsonLd.director?.firstOrNull()

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"

        return if (isSerie) {
            println("SuperFlix: load - É uma série")
            val episodes = extractEpisodesFromButtons(document, url)
            println("SuperFlix: load - Episódios encontrados: ${episodes.size}")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            println("SuperFlix: load - É um filme")
            val playerUrl = findPlayerUrl(document)
            println("SuperFlix: load - Player URL encontrada: $playerUrl")

            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: loadLinks - INÍCIO para: $data")

        if (data.isEmpty()) {
            println("SuperFlix: loadLinks - ERRO: URL vazia")
            return false
        }

        var success = false
        val foundLinks = mutableListOf<ExtractorLink>()

        // ESTRATÉGIA 1: Verificar se é uma URL direta de player (Fembed/Filemoon/etc)
        if (isDirectVideoUrl(data)) {
            println("SuperFlix: loadLinks - URL parece ser de player direto")
            success = loadExtractor(data, subtitleCallback, callback)
            if (success) {
                println("SuperFlix: loadLinks - Extractor funcionou para URL direta")
                return true
            }
        }

        // ESTRATÉGIA 2: Buscar URLs de vídeo
        if (!success) {
            println("SuperFlix: loadLinks - Iniciando busca avançada de URLs...")
            
            try {
                val document = app.get(data).document
                val html = document.html()
                
                // Método A: Sniffing de URLs de mídia
                val sniffedUrls = sniffMediaUrls(html, data)
                println("SuperFlix: loadLinks - URLs sniffadas: ${sniffedUrls.size}")
                
                for (mediaUrl in sniffedUrls) {
                    println("SuperFlix: loadLinks - Tentando URL sniffada: $mediaUrl")
                    
                    // Verificar se é URL HLS (.m3u8) - USANDO A NOVA API
                    if (mediaUrl.contains(".m3u8")) {
                        val quality = extractQualityFromM3u8Url(mediaUrl)
                        println("SuperFlix: loadLinks - Encontrado .m3u8 (Qualidade: $quality)")
                        
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "SuperFlix HLS (${quality}p)",
                                url = mediaUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = mainUrl
                                this.headers = getHeaders()
                                this.quality = quality
                            }
                        )
                        success = true
                    }
                    // Verificar se é segmento .ts (como na imagem)
                    else if (mediaUrl.contains(".ts") && mediaUrl.contains("seg-")) {
                        println("SuperFlix: loadLinks - Encontrado segmento .ts")
                        
                        // Tentar extrair URL .m3u8 do segmento .ts
                        val m3u8Url = extractM3u8FromTsUrl(mediaUrl)
                        if (m3u8Url != null) {
                            println("SuperFlix: loadLinks - Extraído .m3u8 do .ts: $m3u8Url")
                            val quality = extractQualityFromM3u8Url(m3u8Url)
                            
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "SuperFlix HLS (${quality}p)",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                    this.headers = getHeaders()
                                    this.quality = quality
                                }
                            )
                            success = true
                        }
                    }
                    // Outros tipos de mídia (MP4, etc.)
                    else if (mediaUrl.contains(".mp4") || mediaUrl.contains(".mkv") || mediaUrl.contains(".avi")) {
                        val quality = guessQualityFromUrl(mediaUrl)
                        println("SuperFlix: loadLinks - Encontrado vídeo direto: $mediaUrl (Qualidade: $quality)")
                        
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "SuperFlix Direct (${quality}p)",
                                url = mediaUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.headers = getHeaders()
                                this.quality = quality
                            }
                        )
                        success = true
                    }
                }
                
                // Método B: Extrair URLs de iframes/players
                if (!success) {
                    val playerUrls = extractPlayerUrls(document)
                    println("SuperFlix: loadLinks - URLs de player encontradas: ${playerUrls.size}")
                    
                    for (playerUrl in playerUrls) {
                        println("SuperFlix: loadLinks - Tentando player: $playerUrl")
                        if (loadExtractor(playerUrl, subtitleCallback, callback)) {
                            success = true
                            break
                        }
                    }
                }
                
                // Método C: Extrair de botões e atributos data-*
                if (!success) {
                    val buttonUrls = extractButtonUrls(document)
                    println("SuperFlix: loadLinks - URLs de botões: ${buttonUrls.size}")
                    
                    for (buttonUrl in buttonUrls) {
                        println("SuperFlix: loadLinks - Tentando botão: $buttonUrl")
                        if (loadExtractor(buttonUrl, subtitleCallback, callback)) {
                            success = true
                            break
                        }
                    }
                }
                
            } catch (e: Exception) {
                println("SuperFlix: loadLinks - Erro durante busca: ${e.message}")
                e.printStackTrace()
            }
        }

        // ESTRATÉGIA 3: Fallback para extractor genérico
        if (!success) {
            println("SuperFlix: loadLinks - Fallback: usando extractor genérico")
            success = loadExtractor(data, subtitleCallback, callback)
        }

        println("SuperFlix: loadLinks - FIM: ${if (success) "SUCESSO" else "FALHA"}")
        return success
    }

    // ========== NOVOS MÉTODOS COM API CORRETA ==========

    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "accept" to "*/*",
            "accept-encoding" to "gzip, deflate, br, zstd",
            "accept-language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "cache-control" to "no-cache",
            "dnt" to "1",
            "origin" to mainUrl,
            "pragma" to "no-cache",
            "referer" to mainUrl,
            "sec-ch-ua" to "\"Google Chrome\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "cross-site",
            "sec-gpc" to "1",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "X-Requested-With" to "XMLHttpRequest",
            "Range" to "bytes=0-"
        )
    }

    private fun sniffMediaUrls(html: String, baseUrl: String): List<String> {
        val mediaUrls = mutableListOf<String>()
        
        // Padrões específicos baseados na imagem
        val patterns = listOf(
            Regex("""https?://[^\s"']*?/seg-\d+-v\d+-a\d+\.ts[^\s"']*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"']*?\.m3u8[^\s"']*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"']*?\.(?:mp4|mpd|mkv|avi|mov|wmv|flv|webm)[^\s"']*""", RegexOption.IGNORE_CASE),
            Regex("""["'](file|src|url)["']\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""\.setup\s*\([^)]*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(html)
            for (match in matches) {
                var url = match.value
                if (match.groupValues.size > 2) {
                    url = match.groupValues[2]
                } else if (match.groupValues.size > 1 && match.groupValues[1].startsWith("http")) {
                    url = match.groupValues[1]
                }
                
                if (isValidMediaUrl(url)) {
                    val fullUrl = if (url.startsWith("//")) "https:$url"
                        else if (url.startsWith("/")) "$mainUrl$url"
                        else if (!url.startsWith("http")) "$mainUrl/$url"
                        else url
                    
                    if (!mediaUrls.contains(fullUrl)) {
                        println("SuperFlix: sniffMediaUrls - Encontrada: $fullUrl")
                        mediaUrls.add(fullUrl)
                    }
                }
            }
        }
        
        return mediaUrls.distinct()
    }

    private fun isValidMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        
        val ignorePatterns = listOf(
            "google-analytics", "doubleclick", "facebook", "twitter",
            "instagram", "analytics", "tracking", "pixel", "beacon",
            "ads", "adserver", "banner", "sponsor"
        )
        
        if (ignorePatterns.any { url.contains(it, ignoreCase = true) }) {
            return false
        }
        
        val mediaExtensions = listOf(
            ".m3u8", ".ts", ".mp4", ".mpd", ".mkv", ".avi", 
            ".mov", ".wmv", ".flv", ".webm", "/hls/", "/stream/",
            "/video/", "videoplayback", "seg-"
        )
        
        return mediaExtensions.any { url.contains(it, ignoreCase = true) }
    }

    private fun extractM3u8FromTsUrl(tsUrl: String): String? {
        try {
            // Padrão: https://domain.com/hls2/XX/XXXXX/XXXX/seg-24-v1-a1.ts
            val pattern1 = Regex("""(https?://[^/]+/hls2/[^/]+/[^/]+/[^/]+/)[^/]+\.ts""")
            val match1 = pattern1.find(tsUrl)
            if (match1 != null) {
                val baseUrl = match1.groupValues[1]
                return baseUrl + "index.m3u8"
            }
            
            // Outro padrão
            val pattern2 = Regex("""(https?://[^/]+/.+/)[^/]+\.ts""")
            val match2 = pattern2.find(tsUrl)
            if (match2 != null) {
                val baseUrl = match2.groupValues[1]
                return baseUrl + "playlist.m3u8"
            }
            
        } catch (e: Exception) {
            println("SuperFlix: extractM3u8FromTsUrl - Erro: ${e.message}")
        }
        
        return null
    }

    private fun extractQualityFromM3u8Url(url: String): Int {
        val qualityPatterns = mapOf(
            Regex("""360p?|360""") to 360,
            Regex("""480p?|480""") to 480,
            Regex("""720p?|720""") to 720,
            Regex("""1080p?|1080|fullhd""") to 1080,
            Regex("""2160p?|4k|uhd""") to 2160
        )
        
        for ((pattern, quality) in qualityPatterns) {
            if (pattern.containsMatchIn(url.lowercase())) {
                return quality
            }
        }
        
        // Extrair do padrão de segmento
        val tsPattern = Regex("""seg-(\d+)-v\d+-a\d+""")
        val tsMatch = tsPattern.find(url)
        if (tsMatch != null) {
            val segmentNum = tsMatch.groupValues[1].toIntOrNull()
            if (segmentNum != null) {
                return when {
                    segmentNum < 10 -> 360
                    segmentNum < 20 -> 480
                    segmentNum < 30 -> 720
                    else -> 1080
                }
            }
        }
        
        return Qualities.Unknown.value
    }

    private fun guessQualityFromUrl(url: String): Int {
        val qualityPatterns = mapOf(
            Regex("""360p""", RegexOption.IGNORE_CASE) to 360,
            Regex("""480p""", RegexOption.IGNORE_CASE) to 480,
            Regex("""720p""", RegexOption.IGNORE_CASE) to 720,
            Regex("""1080p""", RegexOption.IGNORE_CASE) to 1080,
            Regex("""2160p|4k""", RegexOption.IGNORE_CASE) to 2160
        )
        
        for ((pattern, quality) in qualityPatterns) {
            if (pattern.containsMatchIn(url.lowercase())) {
                return quality
            }
        }
        
        return Qualities.Unknown.value
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains("fembed") || 
               url.contains("filemoon") || 
               url.contains("streamtape") || 
               url.contains("voe") ||
               url.contains("dood") ||
               url.contains(".m3u8") ||
               url.contains(".mp4")
    }

    private fun extractPlayerUrls(document: org.jsoup.nodes.Document): List<String> {
        val playerUrls = mutableListOf<String>()
        
        // Iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.startsWith("javascript:")) {
                playerUrls.add(fixUrl(src))
            }
        }
        
        return playerUrls.distinct()
    }

    private fun extractButtonUrls(document: org.jsoup.nodes.Document): List<String> {
        val buttonUrls = mutableListOf<String>()
        
        // Botões com data-url
        document.select("[data-url]").forEach { element ->
            val dataUrl = element.attr("data-url")
            if (dataUrl.isNotBlank()) {
                buttonUrls.add(fixUrl(dataUrl))
            }
        }
        
        // Links de player
        document.select("a[href*='watch'], a[href*='player']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                buttonUrls.add(fixUrl(href))
            }
        }
        
        return buttonUrls.distinct()
    }

    // ========== MÉTODOS AUXILIARES EXISTENTES ==========

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val buttons = document.select("button.bd-play[data-url]")

        buttons.forEach { button ->
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

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Botões com data-url
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        // Iframes
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        // Links de vídeo
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
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

                    val year = Regex("\"dateCreated\":\"(\\d{4})").find(json)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("\"copyrightYear\":(\\d{4})").find(json)?.groupValues?.get(1)?.toIntOrNull()

                    val type = if (json.contains("\"@type\":\"TVSeries\"")) "TVSeries" else "Movie"

                    return JsonLdInfo(
                        title = title,
                        year = year,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continuar para o próximo JSON
            }
        }

        return JsonLdInfo()
    }
}