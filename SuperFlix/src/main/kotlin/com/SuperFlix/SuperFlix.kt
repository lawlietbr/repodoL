package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder

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
        
        // PRIMEIRA TENTATIVA: estrutura dos recomendados
        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }
        
        // SEGUNDA TENTATIVA: todos os links de filmes/séries
        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val title = link.selectFirst("img")?.attr("alt")
                        ?: link.selectFirst(".rec-title, .title, h2, h3, .movie-title")?.text()
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
        
        // Remover duplicados
        val uniqueHome = home.distinctBy { it.url }
        return newHomePageResponse(request.name, uniqueHome)
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
        
        // Primeiro: estrutura dos recomendados
        document.select("div.recs-grid a.rec-card").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }
        
        // Segundo: qualquer link de filme/série
        if (results.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                val title = link.selectFirst("img")?.attr("alt") 
                    ?: link.selectFirst("h2, h3, .title")?.text()
                    ?: href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()
                
                if (title.isNotBlank() && href.isNotBlank()) {
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
                    
                    results.add(searchResponse)
                }
            }
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        // Extrair dados do JSON-LD
        val jsonLd = extractJsonLd(html)
        
        val title = jsonLd.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // Poster em qualidade original
        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
        
        // Sinopse
        val plot = jsonLd.description ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".syn, .description")?.text()
        
        // Gêneros
        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }
        
        // Atores
        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        
        // Diretor
        val director = jsonLd.director?.firstOrNull()
        
        // Encontrar iframe do Fembed
        val iframe = document.selectFirst("iframe[src*='fembed']")
        val fembedUrl = iframe?.attr("src")
        
        // Se não tiver iframe, tentar extrair do JSON-LD (TMDB ID)
        val finalFembedUrl = fembedUrl ?: jsonLd.tmdbId?.let { "https://fembed.sx/e/$it" }
        
        // Verificar se é série
        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"
        
        return if (isSerie) {
            // Para séries, criar um episódio com o próprio link
            val episodes = listOf(
                newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            )
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, finalFembedUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    // Função para extrair JSON-LD
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
                    
                    // Extrair título
                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    
                    // Extrair poster
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    
                    // Extrair descrição
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    
                    // Extrair gêneros
                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }
                    
                    // Extrair atores
                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }
                    
                    // Extrair diretor
                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }
                    
                    // Extrair TMDB ID
                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')
                    
                    // Determinar tipo
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        return try {
            // Se já for URL do Fembed
            if (data.contains("fembed.sx")) {
                return loadExtractor(data, mainUrl, subtitleCallback, callback)
            }
            
            // Se for URL do SuperFlix, procurar iframe
            val finalUrl = if (data.startsWith("http")) data else fixUrl(data)
            val res = app.get(finalUrl, referer = mainUrl)
            val doc = res.document
            
            // Procurar iframe do Fembed
            val iframe = doc.selectFirst("iframe[src*='fembed']")
            val fembedUrl = iframe?.attr("src")
            
            if (fembedUrl != null) {
                return loadExtractor(fembedUrl, finalUrl, subtitleCallback, callback)
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}