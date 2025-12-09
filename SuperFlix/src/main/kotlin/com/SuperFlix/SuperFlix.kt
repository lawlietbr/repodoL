package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Configurações do sniffer
    private val sniffingEnabled = true
    private val debugNetwork = true

    // Padrões para detectar URLs de vídeo
    private val videoPatterns = listOf(
        Regex("""\.(mp4|m4v|mkv|avi|mov|flv|wmv|webm|3gp|ts|m4s)($|\?)""", RegexOption.IGNORE_CASE),
        Regex("""\.m3u8($|\?)""", RegexOption.IGNORE_CASE),
        Regex("""\.mpd($|\?)""", RegexOption.IGNORE_CASE),
        Regex("""manifest\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""master\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""video.*\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""/video/|/stream/|/media/|/hls/|/dash/""", RegexOption.IGNORE_CASE),
        Regex("""\.googlevideo\.com/""", RegexOption.IGNORE_CASE),
        Regex("""cdn\d*\.|stream\d*\.|vod\d*\.|video\d*\.""", RegexOption.IGNORE_CASE)
    )

    // Padrões para filtrar URLs não-desejadas
    private val filterPatterns = listOf(
        "analytics", "google-analytics", "doubleclick", "googlesyndication",
        "adservice", "facebook.com/tr", "googletagmanager", "googletagservices",
        "tracking", "pixel", "beacon", "ads", "adx", "banner", "logo", "icon",
        "thumbnail", "poster", "placeholder", "sprite", "preview", "teaser", "tracker", "stat"
    )

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

        // Primeiro: Usar sniffing se habilitado
        if (sniffingEnabled) {
            println("SuperFlix: loadLinks - Usando network sniffing")
            try {
                val sniffedUrls = sniffVideoUrls(data)
                println("SuperFlix: loadLinks - URLs encontradas via sniffing: ${sniffedUrls.size}")
                
                if (sniffedUrls.isNotEmpty()) {
                    sniffedUrls.forEach { url ->
                        println("SuperFlix: loadLinks - Tentando extractor para: $url")
                        if (loadExtractor(url, subtitleCallback, callback)) {
                            println("SuperFlix: loadLinks - Extractor funcionou para: $url")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                println("SuperFlix: loadLinks - Erro no sniffing: ${e.message}")
            }
        }

        // Segundo: Método tradicional
        println("SuperFlix: loadLinks - Tentando método tradicional")
        try {
            val document = app.get(data).document
            
            // Procurar iframes
            val iframes = document.select("iframe[src]")
            println("SuperFlix: loadLinks - Iframes encontrados: ${iframes.size}")
            
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    println("SuperFlix: loadLinks - Tentando iframe: $iframeSrc")
                    if (loadExtractor(fixUrl(iframeSrc), subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            // Procurar links diretos
            val videoLinks = document.select("a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mpd'], button[data-url]")
            println("SuperFlix: loadLinks - Links/buttons de vídeo: ${videoLinks.size}")
            
            for (link in videoLinks) {
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: link.attr("data-url")
                if (!href.isNullOrBlank() && isVideoUrl(href)) {
                    println("SuperFlix: loadLinks - Tentando link: $href")
                    if (loadExtractor(fixUrl(href), subtitleCallback, callback)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: loadLinks - Erro no método tradicional: ${e.message}")
        }

        // Terceiro: Extractor genérico
        println("SuperFlix: loadLinks - Tentando extractor genérico")
        return try {
            loadExtractor(data, subtitleCallback, callback)
        } catch (e: Exception) {
            println("SuperFlix: loadLinks - Erro final: ${e.message}")
            false
        }
    }

    // ========== MÉTODOS DE NETWORK SNIFFING ==========
    
    private suspend fun sniffVideoUrls(pageUrl: String): List<String> {
        val videoUrls = mutableSetOf<String>()
        
        try {
            println("SuperFlix: sniffVideoUrls - Iniciando sniffing em: $pageUrl")
            val document = app.get(pageUrl, headers = getDefaultHeaders()).document
            val html = document.html()
            println("SuperFlix: sniffVideoUrls - HTML obtido: ${html.length} chars")
            
            // Método 1: URLs do HTML
            val urlPattern = Regex("""https?://[^\s"'<>{}()]+""")
            val allUrls = urlPattern.findAll(html).map { it.value }.toList()
            println("SuperFlix: sniffVideoUrls - Todas as URLs: ${allUrls.size}")
            
            allUrls.filter { isVideoUrl(it) }.forEach { url ->
                println("SuperFlix: sniffVideoUrls - URL de vídeo encontrada: $url")
                videoUrls.add(url)
            }
            
            // Método 2: Tags específicas
            extractVideoUrlsFromHtml(document, videoUrls)
            
            // Método 3: Scripts
            extractVideoUrlsFromScripts(document, videoUrls)
            
            // Método 4: Iframes
            extractVideoUrlsFromIframes(document, videoUrls)
            
            // Método 5: JSON
            extractVideoUrlsFromJsonData(document, videoUrls)
            
        } catch (e: Exception) {
            println("SuperFlix: sniffVideoUrls - Erro: ${e.message}")
        }
        
        val filtered = videoUrls.filter { isVideoUrl(it) && !shouldFilterUrl(it) }.toList()
        println("SuperFlix: sniffVideoUrls - URLs finais: ${filtered.size}")
        return filtered
    }
    
    private fun extractVideoUrlsFromHtml(document: Element, urlSet: MutableSet<String>) {
        // Tags <video>
        document.select("video").forEach { video ->
            video.attr("src")?.let { if (isVideoUrl(it)) urlSet.add(fixUrl(it)) }
            video.select("source").forEach { source ->
                source.attr("src")?.let { if (isVideoUrl(it)) urlSet.add(fixUrl(it)) }
            }
            video.attr("data-src")?.let { if (isVideoUrl(it)) urlSet.add(fixUrl(it)) }
        }
        
        // Atributos data-*
        document.select("[data-src], [data-url], [data-file], [data-video], [data-source]").forEach { element ->
            val src = element.attr("data-src") ?: element.attr("data-url") ?: 
                     element.attr("data-file") ?: element.attr("data-video") ?: 
                     element.attr("data-source")
            if (isVideoUrl(src)) urlSet.add(fixUrl(src))
        }
        
        // Links
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            if (isVideoUrl(href)) urlSet.add(fixUrl(href))
        }
    }
    
    private fun extractVideoUrlsFromScripts(document: Element, urlSet: MutableSet<String>) {
        document.select("script").forEach { script ->
            val content = script.html()
            
            val patterns = listOf(
                Regex("""["'](https?://[^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']"""),
                Regex("""\.setup\s*\({[^}]*["']file["']\s*:\s*["']([^"']+)["']"""),
                Regex("""sources\s*:\s*\[([^\]]+)\]""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(content).forEach { match ->
                    val url = match.groupValues.getOrNull(1) ?: return@forEach
                    if (isVideoUrl(url)) urlSet.add(fixUrl(url))
                }
            }
        }
    }
    
    private fun extractVideoUrlsFromIframes(document: Element, urlSet: MutableSet<String>) {
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.startsWith("about:")) {
                if (isVideoUrl(src) || src.contains("player") || src.contains("embed")) {
                    urlSet.add(fixUrl(src))
                }
            }
        }
    }
    
    private fun extractVideoUrlsFromJsonData(document: Element, urlSet: MutableSet<String>) {
        document.select("script[type='application/ld+json']").forEach { script ->
            try {
                val jsonText = script.html()
                val patterns = listOf(
                    Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
                    Regex(""""embedUrl"\s*:\s*"([^"]+)""""),
                    Regex(""""url"\s*:\s*"([^"]+)""""),
                    Regex(""""src"\s*:\s*"([^"]+)"""")
                )
                
                patterns.forEach { pattern ->
                    pattern.findAll(jsonText).forEach { match ->
                        val url = match.groupValues.getOrNull(1) ?: return@forEach
                        if (isVideoUrl(url)) urlSet.add(fixUrl(url))
                    }
                }
            } catch (e: Exception) {}
        }
    }
    
    // ========== MÉTODOS AUXILIARES ==========
    
    private fun isVideoUrl(url: String): Boolean {
        if (url.isBlank() || url.length < 10) return false
        val isVideo = videoPatterns.any { it.containsMatchIn(url) }
        val isValidUrl = url.startsWith("http") && url.contains("://")
        return isVideo && isValidUrl
    }
    
    private fun shouldFilterUrl(url: String): Boolean {
        return filterPatterns.any { url.contains(it, ignoreCase = true) }
    }
    
    private fun getDefaultHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to mainUrl
        )
    }
    
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
            } catch (e: Exception) {}
        }
        
        return JsonLdInfo()
    }
    
    data class NetworkRequest(
        val url: String,
        val headers: Map<String, String>
    )
}