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
        // DETECTA O TIPO DE DADO:
        // 1. Apenas números: ID do episódio (ex: "354356")
        // 2. URL do ultracine.org com números: (ex: "https://ultracine.org/354356")
        // 3. URL completa do assistirseriesonline: (ex: "https://assistirseriesonline.icu/episodio/354356")
        
        val finalUrl = when {
            // Caso 1: Apenas números (ID do episódio)
            data.matches(Regex("^\\d+$")) -> {
                "https://assistirseriesonline.icu/episodio/$data"
            }
            // Caso 2: URL do ultracine com números no final
            data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                // Extrai o ID da URL
                val id = data.substringAfterLast("/")
                "https://assistirseriesonline.icu/episodio/$id"
            }
            // Caso 3: Já é uma URL válida
            else -> data
        }

        println("DEBUG: Carregando URL: $finalUrl") // Para depuração
        
        val res = app.get(finalUrl, referer = mainUrl)
        val doc = res.document
        
        // TENTATIVA 1: Procurar elementos <video> diretos (como o exemplo que você viu)
        doc.select("video[src]").forEach { video ->
            val videoUrl = video.attr("src")
            if (videoUrl.isNotBlank()) {
                println("DEBUG: Encontrado vídeo direto: $videoUrl")
                val link = newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl
                )
                callback.invoke(link)
                return true
            }
        }
        
        // TENTATIVA 2: Procurar elementos com data-src
        doc.select("[data-src]").forEach { element ->
            val videoUrl = element.attr("data-src")
            if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                println("DEBUG: Encontrado data-src: $videoUrl")
                val link = newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl
                )
                callback.invoke(link)
                return true
            }
        }
        
        // TENTATIVA 3: Procurar por iframes (players embed)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                println("DEBUG: Encontrado iframe: $src")
                if (loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
        }
        
        // TENTATIVA 4: Procurar por botões com data-source (EmbedPlay)
        doc.select("button[data-source]").forEach { button ->
            val source = button.attr("data-source")
            if (source.isNotBlank()) {
                println("DEBUG: Encontrado botão data-source: $source")
                if (loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
        }
        
        // TENTATIVA 5: Procurar por scripts JavaScript
        val html = res.text
        val videoPatterns = listOf(
            Regex("""src\s*=\s*["'](https?://[^"' ]+\.mp4[^"' ]*)["']"""),
            Regex("""src\s*=\s*["'](https?://[^"' ]+\.m3u8[^"' ]*)["']"""),
            Regex("""file\s*:\s*["'](https?://[^"' ]+\.mp4[^"' ]*)["']"""),
            Regex("""["'](https?://[^"' ]+storage\.googleapis\.com[^"' ]+\.mp4)["']""")
        )
        
        for (pattern in videoPatterns) {
            val matches = pattern.findAll(html)
            for (match in matches) {
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotBlank() && !videoUrl.contains("banner") && !videoUrl.contains("ads")) {
                    println("DEBUG: Encontrado no regex: $videoUrl")
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
        
        println("DEBUG: Nenhum vídeo encontrado")
        false
    } catch (e: Exception) {
        println("DEBUG: Erro ao carregar links: ${e.message}")
        e.printStackTrace()
        false
      }
   }
}