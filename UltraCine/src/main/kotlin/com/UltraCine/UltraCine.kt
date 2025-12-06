package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/documentario/" to "Documentário",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/familia/" to "Família",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/kids/" to "Kids",
        "$mainUrl/category/misterio/" to "Misterio",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("div.post-thumbnail figure img")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: selectFirst("div.post-thumbnail figure img")?.attr("data-src")
            ?.let { fixUrl(it).replace("/w500/", "/original/") }

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.bghd img.TPostBg")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("div.bghd img.TPostBg")?.attr("data-src")
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }

        val yearText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.ownText()
        val year = yearText?.toIntOrNull()

        val durationText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.ownText()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }

        val actors = document.select("aside.fg1 ul.cast-lst p a").map {
            Actor(it.text(), it.attr("href"))
        }

        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("div.mdl-cn div.video iframe")?.attr("data-src")

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            val episodes = if (iframeUrl != null) {
                try {
                    val iframeDoc = app.get(iframeUrl).document
                    parseSeriesEpisodes(iframeDoc)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = null
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(durationText)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseSeriesEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        doc.select("header.header ul.header-navigation li").forEach { seasonEl ->
            val seasonNum = seasonEl.attr("data-season-number").toIntOrNull() ?: return@forEach
            val seasonId = seasonEl.attr("data-season-id")

            doc.select("li[data-season-id='$seasonId']").mapNotNull { epEl ->
                val epId = epEl.attr("data-episode-id")
                if (epId.isBlank()) return@mapNotNull null

                val title = epEl.selectFirst("a")?.text() ?: "Episódio"
                val epNum = title.substringBefore(" - ").toIntOrNull() ?: 1

                newEpisode(epId) {
                    this.name = title.substringAfter(" - ").takeIf { it.isNotEmpty() } ?: title
                    this.season = seasonNum
                    this.episode = epNum
                }
            }.also { episodes.addAll(it) }
        }

        return episodes
    }

    // Função auxiliar para converter duração (ex: "2h 15m" ou "135m") → minutos
    private fun parseDuration(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val regex = Regex("""(\d+)h.*?(\d+)m""")
        val match = regex.find(duration)
        return if (match != null) {
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            h * 60 + m
        } else {
            Regex("""(\d+)m""").find(duration)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (data.isBlank()) return false

    return try {
        // DETERMINA A URL FINAL
        val finalUrl = when {
            // ID numérico (série)
            data.matches(Regex("^\\d+$")) -> {
                "https://assistirseriesonline.icu/episodio/$data"
            }
            // URL do ultracine com ID
            data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                val id = data.substringAfterLast("/")
                "https://assistirseriesonline.icu/episodio/$id"
            }
            // URL normal
            else -> data
        }

        // FAZ A REQUISIÇÃO
        val res = app.get(finalUrl, referer = mainUrl, timeout = 30)
        val html = res.text
        
        // ========== DETECTOR ESPECÍFICO PARA JW PLAYER ==========
        
        // 1. Procura por elementos <video> do JW Player
        val jwPlayerPattern = Regex("""<video[^>]+class=["'][^"']*jw[^"']*["'][^>]+src=["'](https?://[^"']+)["']""")
        val jwMatches = jwPlayerPattern.findAll(html).toList()
        
        if (jwMatches.isNotEmpty()) {
            jwMatches.forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.contains(".mp4") && videoUrl.contains("storage.googleapis.com")) {
                    // ENCONTROU O VÍDEO DIRETO!
                    val quality = extractQualityFromUrl(videoUrl)
                    val isM3u8 = videoUrl.contains(".m3u8")
                    
                    // USANDO newExtractorLink CORRETAMENTE
                    val link = newExtractorLink(
                        source = this.name,
                        name = "${this.name} (${if (quality != Qualities.Unknown.value) "${quality}p" else "Direct"})",
                        url = videoUrl
                    ) {
                        // Configurações adicionais
                        this.referer = finalUrl
                        this.quality = quality
                        this.isM3u8 = isM3u8
                    }
                    callback.invoke(link)
                    return true
                }
            }
        }
        
        // 2. Procura por links do Google Storage (fallback)
        val googleStoragePattern = Regex("""https?://storage\.googleapis\.com/[^"'\s<>]+\.mp4[^"'\s<>]*""")
        val googleMatches = googleStoragePattern.findAll(html).toList()
        
        if (googleMatches.isNotEmpty()) {
            googleMatches.forEach { match ->
                val videoUrl = match.value
                if (videoUrl.isNotBlank() && !videoUrl.contains("banner")) {
                    val quality = extractQualityFromUrl(videoUrl)
                    
                    val link = newExtractorLink(
                        source = this.name,
                        name = "${this.name} (${if (quality != Qualities.Unknown.value) "${quality}p" else "Google"})",
                        url = videoUrl
                    ) {
                        this.referer = finalUrl
                        this.quality = quality
                        this.isM3u8 = false
                    }
                    callback.invoke(link)
                    return true
                }
            }
        }
        
        // 3. Procura por padrão genérico de MP4 no JW Player
        val mp4Pattern = Regex("""src=["'](https?://[^"']+\.mp4(?:#mp4/chunk/[^"']*)?)["']""")
        val mp4Matches = mp4Pattern.findAll(html).toList()
        
        if (mp4Matches.isNotEmpty()) {
            mp4Matches.forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotBlank() && !videoUrl.contains("banner")) {
                    val quality = extractQualityFromUrl(videoUrl)
                    
                    val link = newExtractorLink(
                        source = this.name,
                        name = "${this.name} (${if (quality != Qualities.Unknown.value) "${quality}p" else "MP4"})",
                        url = videoUrl
                    ) {
                        this.referer = finalUrl
                        this.quality = quality
                        this.isM3u8 = false
                    }
                    callback.invoke(link)
                    return true
                }
            }
        }
        
        // 4. Se for uma página do assistirseriesonline, procura por iframes/botões
        if (finalUrl.contains("assistirseriesonline")) {
            val doc = res.document
            
            // A. Procura iframe do player
            val playerIframe = doc.selectFirst("iframe[src*='player'], iframe[src*='embed']")
            if (playerIframe != null) {
                val src = playerIframe.attr("src")
                if (src.isNotBlank() && loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
            
            // B. Procura botão play
            val playButton = doc.selectFirst("button[data-source], a[data-source]")
            if (playButton != null) {
                val source = playButton.attr("data-source")
                if (source.isNotBlank() && loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
        }
        
        // 5. ESTRATÉGIA PARA FILMES (mantém o que já funciona)
        if (!finalUrl.contains("assistirseriesonline")) {
            val doc = res.document
            
            // Tenta iframes (EmbedPlay)
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
            
            // Tenta botões com data-source
            doc.select("button[data-source]").forEach { button ->
                val source = button.attr("data-source")
                if (source.isNotBlank() && loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
        }
        
        false
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

// Função auxiliar para extrair qualidade
private fun extractQualityFromUrl(url: String): Int {
    // Extrai do padrão: .../chunk/1/544794072/2097152/360p/h264...
    val qualityPattern = Regex("""/(\d+)p?/""")
    val match = qualityPattern.find(url)
    
    if (match != null) {
        val qualityNum = match.groupValues[1].toIntOrNull()
        return when (qualityNum) {
            360 -> Qualities.P360.value
            480 -> Qualities.P480.value
            720 -> Qualities.P720.value
            1080 -> Qualities.P1080.value
            2160 -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
    
    // Fallback para busca textual
    return when {
        url.contains("360p", ignoreCase = true) -> Qualities.P360.value
        url.contains("480p", ignoreCase = true) -> Qualities.P480.value
        url.contains("720p", ignoreCase = true) -> Qualities.P720.value
        url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
        url.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
        else -> Qualities.Unknown.value
    }
}