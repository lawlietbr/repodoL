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
    
    // CORREÇÃO DOS POSTERS - Múltiplas tentativas
    val posterUrl = try {
        // Tentativa 1: Imagem com data-src (lazy loading)
        selectFirst("img[data-src]")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
            ?.replace("/w300/", "/original/")
            ?.replace("/w200/", "/original/")
        
        // Tentativa 2: Imagem com src normal
        ?: selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
            ?.replace("/w300/", "/original/")
            ?.replace("/w200/", "/original/")
        
        // Tentativa 3: Imagem dentro da thumbnail
        ?: selectFirst("div.post-thumbnail img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
            ?.replace("/w300/", "/original/")
            ?.replace("/w200/", "/original/")
        
        // Tentativa 4: Imagem da figura
        ?: selectFirst("figure img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
            ?.replace("/w300/", "/original/")
            ?.replace("/w200/", "/original/")
        
        // Tentativa 5: Qualquer imagem com w500 no URL
        ?: selectFirst("img[src*='w500']")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
        
    } catch (e: Exception) {
        null
    }

    val year = selectFirst("span.year")?.text()?.toIntOrNull()
    val isSerie = href.contains("/serie/")
    val type = if (isSerie) TvType.TvSeries else TvType.Movie

    // DEBUG: Ver o que está sendo encontrado
    // println("DEBUG: Title: $title, Poster: $posterUrl, Year: $year, Serie: $isSerie")

    return if (isSerie) {
        newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
        }
    } else {
        newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
        }
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
    
    // CORREÇÃO DO POSTER
    val poster = try {
        var posterUrl: String? = null
        
        // Tentativa 1: Imagem TPostBg
        posterUrl = document.selectFirst("div.bghd img.TPostBg")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }
        
        // Tentativa 2: Data-src
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("div.bghd img.TPostBg")?.attr("data-src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it).replace("/w1280/", "/original/") }
        }
        
        // Tentativa 3: Qualquer imagem w1280
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("img[src*='w1280']")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it).replace("/w1280/", "/original/") }
        }
        
        posterUrl
    } catch (e: Exception) {
        null
    }
    
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

    val isSerie = url.contains("/serie/")

    return if (isSerie) {
        // ========== CORREÇÃO: PEGAR EPISÓDIOS DA PÁGINA DA SÉRIE ==========
        val episodes = parseSeriesEpisodesFromPage(document, url)
        
        println("ULTRA CINE DEBUG: Encontrados ${episodes.size} episódios para a série '$title'")

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
        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

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

private fun parseSeriesEpisodesFromPage(doc: org.jsoup.nodes.Document, seriesUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    // DEBUG: Ver estrutura da página
    // println("ULTRA CINE DEBUG: Analisando página da série: $seriesUrl")
    
    // ESTRATÉGIA 1: Procura por seções de temporada
    // Geralmente tem estrutura como: <div class="season"> <h3>Temporada 1</h3> ...
    doc.select("div.season, section.season, .season-cn, .temporada").forEachIndexed { seasonIndex, seasonDiv ->
        val seasonNum = seasonIndex + 1
        
        // Tenta extrair número da temporada do texto
        val seasonText = seasonDiv.selectFirst("h2, h3, h4, .season-title")?.text() ?: ""
        val seasonMatch = Regex("""(?:Temporada|Season|Temp\.?)\s*(\d+)""", RegexOption.IGNORE_CASE).find(seasonText)
        val actualSeasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: seasonNum
        
        // Procura episódios dentro da temporada
        seasonDiv.select("a[href*='assistirseriesonline'], a[href*='/embed/'], li.episode, .episodio, .episode-item").forEachIndexed { epIndex, episodeEl ->
            val href = episodeEl.attr("href")
            if (href.isNotBlank()) {
                // Extrai IDs da URL: https://assistirseriesonline.icu/embed/17967#19180_354321
                val ids = extractIdsFromEpisodeUrl(href)
                if (ids.isNotEmpty()) {
                    val epId = ids.first() // Usa o primeiro ID como ID do episódio
                    val title = episodeEl.text().takeIf { it.isNotBlank() } ?: "Episódio ${epIndex + 1}"
                    val epNum = extractEpisodeNumber(title) ?: (epIndex + 1)
                    
                    episodes.add(newEpisode(epId) {
                        this.name = cleanEpisodeTitle(title)
                        this.season = actualSeasonNum
                        this.episode = epNum
                        // Opcional: adicionar poster do episódio se existir
                        this.posterUrl = episodeEl.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    })
                }
            }
        }
        
        // Se não encontrou por links, procura por botões/divs com data-id
        if (episodes.isEmpty() || episodes.size <= seasonIndex * 10) {
            seasonDiv.select("button[data-id], li[data-id], div[data-id], [data-episode-id]").forEachIndexed { epIndex, element ->
                val epId = element.attr("data-id").ifBlank { element.attr("data-episode-id") }
                if (epId.isNotBlank() && epId.matches(Regex("\\d+"))) {
                    val title = element.text().takeIf { it.isNotBlank() } ?: "Episódio ${epIndex + 1}"
                    val epNum = extractEpisodeNumber(title) ?: (epIndex + 1)
                    
                    episodes.add(newEpisode(epId) {
                        this.name = cleanEpisodeTitle(title)
                        this.season = actualSeasonNum
                        this.episode = epNum
                    })
                }
            }
        }
    }
    
    // ESTRATÉGIA 2: Se não encontrou por temporadas, procura todos os links/episódios na página
    if (episodes.isEmpty()) {
        var episodeCount = 1
        var currentSeason = 1
        
        // Procura todos os links de episódio
        doc.select("a[href*='assistirseriesonline'], a[href*='/embed/']").forEach { link ->
            val href = link.attr("href")
            val ids = extractIdsFromEpisodeUrl(href)
            
            if (ids.isNotEmpty()) {
                val epId = ids.first()
                val title = link.text().takeIf { it.isNotBlank() } ?: "Episódio $episodeCount"
                val epNum = extractEpisodeNumber(title) ?: episodeCount
                
                // Verifica se é uma nova temporada (procura por "Temporada" no texto anterior)
                val parentText = link.parent()?.text() ?: ""
                if (parentText.contains("Temporada") || parentText.contains("Season")) {
                    val seasonMatch = Regex("""(?:Temporada|Season)\s*(\d+)""").find(parentText)
                    currentSeason = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentSeason
                }
                
                episodes.add(newEpisode(epId) {
                    this.name = cleanEpisodeTitle(title)
                    this.season = currentSeason
                    this.episode = epNum
                })
                
                episodeCount++
            }
        }
    }
    
    // ESTRATÉGIA 3: Procura por números de episódio no texto
    if (episodes.isEmpty()) {
        val episodePattern = Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
        val text = doc.text()
        val matches = episodePattern.findAll(text).toList()
        
        if (matches.isNotEmpty()) {
            matches.forEachIndexed { index, match ->
                val epNum = match.groupValues[1].toIntOrNull() ?: (index + 1)
                // Cria um ID fictício baseado no número do episódio
                val fakeId = "episode_${epNum}"
                
                episodes.add(newEpisode(fakeId) {
                    this.name = "Episódio $epNum"
                    this.season = 1
                    this.episode = epNum
                })
            }
        }
    }
    
    // Ordena por temporada e episódio
    return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
}

// Extrai IDs de URLs como: https://assistirseriesonline.icu/embed/17967#19180_354321
private fun extractIdsFromEpisodeUrl(url: String): List<String> {
    val ids = mutableListOf<String>()
    
    // Padrão 1: /embed/ID#OUTRO_ID
    val pattern1 = Regex("""/embed/(\d+)(?:#(\d+[_\d]*))?""")
    val match1 = pattern1.find(url)
    if (match1 != null) {
        ids.add(match1.groupValues[1]) // Primeiro ID (17967)
        if (match1.groupValues[2].isNotBlank()) {
            ids.add(match1.groupValues[2]) // Segundo ID depois do # (19180_354321)
        }
    }
    
    // Padrão 2: /episodio/ID
    val pattern2 = Regex("""/episodio/(\d+)""")
    val match2 = pattern2.find(url)
    if (match2 != null) {
        ids.add(match2.groupValues[1])
    }
    
    // Padrão 3: ID numérico simples
    val pattern3 = Regex("""\b(\d{4,})\b""") // IDs com 4+ dígitos
    val matches3 = pattern3.findAll(url)
    matches3.forEach { match ->
        ids.add(match.value)
    }
    
    return ids.distinct()
}

// Extrai número do episódio do título
private fun extractEpisodeNumber(title: String): Int? {
    val patterns = listOf(
        Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""Ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""^(\d+)[\s\.\-]""")
    )
    
    for (pattern in patterns) {
        val match = pattern.find(title)
        if (match != null) {
            return match.groupValues[1].toIntOrNull()
        }
    }
    
    return null
}

// Limpa o título do episódio
private fun cleanEpisodeTitle(title: String): String {
    var cleaned = title.trim()
    
    // Remove prefixos comuns
    val prefixes = listOf("Assistir", "Ver", "Watch", "Episódio", "Ep.", "E")
    for (prefix in prefixes) {
        if (cleaned.startsWith(prefix, ignoreCase = true)) {
            cleaned = cleaned.substring(prefix.length).trim()
        }
    }
    
    // Remove números no início
    cleaned = cleaned.replaceFirst(Regex("""^\d+[\s\.\-]+"""), "").trim()
    
    return if (cleaned.isBlank()) "Episódio" else cleaned
}

  parseDuration(durationText)
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
        // DETERMINA A URL FINAL baseada no tipo de data
        val finalUrl = when {
            // Caso 1: ID com hash (ex: "17967#19180_354321")
            data.contains("#") && data.matches(Regex("""\d+#[\d_]+""")) -> {
                val parts = data.split("#")
                "https://assistirseriesonline.icu/embed/${parts[0]}#${parts[1]}"
            }
            // Caso 2: ID simples (ex: "354321")
            data.matches(Regex("^\\d+$")) -> {
                // Tenta como episódio do assistirseriesonline
                "https://assistirseriesonline.icu/episodio/$data"
            }
            // Caso 3: URL do ultracine com ID
            data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                val id = data.substringAfterLast("/")
                "https://assistirseriesonline.icu/episodio/$id"
            }
            // Caso 4: URL normal
            else -> data
        }

        // FAZ A REQUISIÇÃO
        val res = app.get(finalUrl, referer = mainUrl, timeout = 30)
        val html = res.text
        
        // ========== DETECTOR ESPECÍFICO PARA JW PLAYER ==========
        val jwPlayerPattern = Regex("""<video[^>]+class=["'][^"']*jw[^"']*["'][^>]+src=["'](https?://[^"']+)["']""")
        val jwMatches = jwPlayerPattern.findAll(html).toList()
        
        if (jwMatches.isNotEmpty()) {
            for (match in jwMatches) {
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotBlank() && 
                    (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) &&
                    !videoUrl.contains("banner") && 
                    !videoUrl.contains("ads")) {
                    
                    val quality = extractQualityFromUrl(videoUrl)
                    val isM3u8 = videoUrl.contains(".m3u8")
                    
                    val linkName = if (quality != Qualities.Unknown.value) {
                        "${this.name} (${quality}p)"
                    } else {
                        "${this.name} (Série)"
                    }
                    
                    val link = newExtractorLink(
                        source = this.name,
                        name = linkName,
                        url = videoUrl
                    )
                    callback.invoke(link)
                    return true
                }
            }
        }
        
        // ========== DETECTOR DE GOOGLE STORAGE ==========
        val googlePattern = Regex("""https?://storage\.googleapis\.com/[^"'\s<>]+\.mp4[^"'\s<>]*""")
        val googleMatches = googlePattern.findAll(html).toList()
        
        if (googleMatches.isNotEmpty()) {
            for (match in googleMatches) {
                val videoUrl = match.value
                if (videoUrl.isNotBlank() && !videoUrl.contains("banner")) {
                    val link = newExtractorLink(
                        source = this.name,
                        name = "${this.name} (Google Storage)",
                        url = videoUrl
                    )
                    callback.invoke(link)
                    return true
                }
            }
        }
        
        // ========== DETECTOR GENÉRICO DE MP4 ==========
        val mp4Pattern = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
        val mp4Matches = mp4Pattern.findAll(html).toList()
        
        if (mp4Matches.isNotEmpty()) {
            for (match in mp4Matches) {
                val videoUrl = match.value
                if (videoUrl.isNotBlank() && 
                    !videoUrl.contains("banner") && 
                    !videoUrl.contains("ads") &&
                    videoUrl.length > 30) {
                    
                    val link = newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl
                    )
                    callback.invoke(link)
                    return true
                }
            }
        }
        
        // ========== DETECTOR DE M3U8 ==========
        val m3u8Pattern = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
        val m3u8Matches = m3u8Pattern.findAll(html).toList()
        
        if (m3u8Matches.isNotEmpty()) {
            for (match in m3u8Matches) {
                val videoUrl = match.value
                if (videoUrl.isNotBlank() && !videoUrl.contains("banner")) {
                    val link = newExtractorLink(
                        source = this.name,
                        name = "${this.name} (HLS)",
                        url = videoUrl
                    )
                    callback.invoke(link)
                    return true
                }
            }
        }
        
        // ========== ESTRATÉGIA PARA IFREMES E BOTÕES ==========
        val doc = res.document
        
        // A. Tenta iframes (EmbedPlay)
        val iframes = doc.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                if (src.contains("embedplay") || src.contains("player") || 
                    src.contains("stream") || src.contains("video")) {
                    
                    if (loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
        }
        
        // B. Tenta botões com data-source (EmbedPlay)
        val buttons = doc.select("button[data-source], a[data-source]")
        for (button in buttons) {
            val source = button.attr("data-source")
            if (source.isNotBlank()) {
                if (loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
        }
        
        // C. Tenta elementos com data-id
        val serverElements = doc.select("[data-id]")
        for (element in serverElements) {
            val dataId = element.attr("data-id")
            if (dataId.isNotBlank() && dataId.length > 3) {
                val possibleUrls = listOf(
                    "https://assistirseriesonline.icu/player/$dataId",
                    "https://assistirseriesonline.icu/embed/$dataId",
                    "https://assistirseriesonline.icu/video/$dataId"
                )
                
                for (apiUrl in possibleUrls) {
                    try {
                        val apiRes = app.get(apiUrl, referer = finalUrl)
                        val apiHtml = apiRes.text
                        
                        val apiVideoPattern = Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""")
                        val apiMatches = apiVideoPattern.findAll(apiHtml)
                        
                        for (apiMatch in apiMatches) {
                            val videoUrl = apiMatch.value
                            if (videoUrl.isNotBlank() && !videoUrl.contains("banner")) {
                                val link = newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = videoUrl
                                )
                                callback.invoke(link)
                                return true
                            }
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }
        
        // ========== FALLBACK PARA SÉRIES ==========
        if (finalUrl.contains("assistirseriesonline") || 
            finalUrl.contains("/episodio/") || 
            finalUrl.contains("/embed/") ||
            data.matches(Regex("^\\d+$")) ||
            data.contains("#")) {
            return true
        }
        
        false
        
    } catch (e: Exception) {
        e.printStackTrace()
        if (data.matches(Regex("^\\d+$")) || 
            data.contains("assistirseriesonline") || 
            data.contains("#") ||
            data.contains("/episodio/") ||
            data.contains("/embed/")) {
            return true
        }
        false
    }
}

// Função auxiliar para extrair qualidade
private fun extractQualityFromUrl(url: String): Int {
    val qualityPattern = Regex("""/(\d+)p?/""")
    val match = qualityPattern.find(url)
    
    if (match != null) {
        val qualityNum = match.groupValues[1].toIntOrNull()
        return when (qualityNum) {
            360 -> 360
            480 -> 480
            720 -> 720
            1080 -> 1080
            2160 -> 2160
            else -> Qualities.Unknown.value
        }
    }
    
    return when {
        url.contains("360p", ignoreCase = true) -> 360
        url.contains("480p", ignoreCase = true) -> 480
        url.contains("720p", ignoreCase = true) -> 720
        url.contains("1080p", ignoreCase = true) -> 1080
        url.contains("2160p", ignoreCase = true) -> 2160
        else -> Qualities.Unknown.value
    }
  }
}