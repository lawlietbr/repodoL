package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt"
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
        "$mainUrl/category/misterio/" to "Mistério",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val doc = app.get(url).document
        val items = doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title, h3") ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(titleEl.text(), href, TvType.Movie) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url).document
        return doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
    }
// ... (dentro da classe UltraCine)

// ... (dentro da classe UltraCine)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // 1. EXTRAÇÃO DE METADADOS (TUDO NA PARTE SUPERIOR)
        
        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        
        // Extrai o poster e limpa a URL se necessário (melhorando o seletor original)
        val poster = document.selectFirst("div.bghd img.TPostBg, div.bghd img")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { url ->
                val fullUrl = if (url.startsWith("//")) "https:$url" else url
                fullUrl.replace("/w1280/", "/original/")
            }
        }
        
        // Extração de outras variáveis
        val year = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.text()?.substringAfter("far\">")?.toIntOrNull()
        val durationText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.text()?.substringAfter("far\">")
        // O rating original da página (ex: 8.5)
        val rating = document.selectFirst("div.vote-cn span.vote span.num")?.text()?.toDoubleOrNull() 
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        // Variável genres (anteriormente tags)
        val genres = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        
        // Variável actors
        val actors = document.selectFirst("aside.fg1 ul.cast-lst p")?.select("a")?.mapNotNull { 
            val name = it.text().trim()
            if (name.isNotBlank()) Actor(name, null) else null // Não há link ou imagem fácil para o ator aqui
        } ?: emptyList() // Retorna lista vazia se nada for encontrado
        
        val trailerUrl = document.selectFirst("div.mdl-cn div.video iframe")?.let { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
        }

        // Variáveis de Iframe/Player
        val iframeElement = document.selectFirst("iframe[src*='assistirseriesonline']")
        val iframeUrl = iframeElement?.let { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
        }

        val isSerie = url.contains("/serie/")
        
        // 2. RETORNO PARA SÉRIE OU FILME
        
        return if (isSerie) {
            
            // Variável episodes declarada antes de ser usada
            val episodes = if (iframeUrl != null) {
                // Se iframe for encontrado, tenta extrair episódios dele
                val iframeDocument = app.get(iframeUrl).document
                parseSeriesEpisodes(iframeDocument, iframeUrl)
            } else {
                emptyList()
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                // CORREÇÃO: Usa o construtor público Score(it, null)
                this.score = null
                this.tags = genres
                addActors(actors)
                trailerUrl?.let { addTrailer(it) } // Usa trailerUrl em vez de trailer
            }

        } else {
            
            // Filmes - A DATA é o iframeUrl (ou string vazia se for null)
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                // CORREÇÃO: Usa o construtor público Score(it, null)
                this.score = null
                this.tags = genres
                this.duration = parseDuration(durationText.orEmpty()) // Usa durationText
                addActors(actors)
                trailerUrl?.let { addTrailer(it) } // Usa trailerUrl em vez de trailer
            }
        }
    }

    // Função auxiliar para parsing de episódios, deve estar dentro da classe UltraCine
        // Função auxiliar para parsing de episódios
    private suspend fun parseSeriesEpisodes(iframeDocument: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seasons = iframeDocument.select("header.header ul.header-navigation li")
        
        for (seasonElement in seasons) {
            // CORREÇÃO: Usa 'return@for' para pular para o próximo 'seasonElement' no loop
            val seasonNumber = seasonElement.attr("data-season-number").toIntOrNull() ?: continue 
            val seasonId = seasonElement.attr("data-season-id")
            
            val seasonEpisodes = iframeDocument.select("li[data-season-id='$seasonId']")
                .mapNotNull { episodeElement ->
                    val episodeId = episodeElement.attr("data-episode-id")
                    val episodeTitle = episodeElement.selectFirst("a")?.text() ?: return@mapNotNull null
                    
                    val episodeNumber = episodeTitle.substringBefore(" - ").toIntOrNull() ?: 1
                    val cleanTitle = if (episodeTitle.contains(" - ")) {
                        episodeTitle.substringAfter(" - ")
                    } else {
                        episodeTitle
                    }
                    
                    // Usa newEpisode (para evitar deprecation)
                    newEpisode(episodeId) {
                        this.name = cleanTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }
            
            episodes.addAll(seasonEpisodes)
        }
        
        return episodes
    }
    
    
    // ... (loadLinks e parseDuration devem seguir a última versão que te enviei, garantindo que estejam DENTRO da classe UltraCine)
    
    // --- O BLOCO loadLinks E AS FUNÇÕES AUXILIARES DEVEM ESTAR DENTRO DA CLASSE ---
    
    // ... (dentro da classe UltraCine, após a função load)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        // Se a DATA é o ID numérico do episódio (usado na função parseSeriesEpisodes)
        if (data.matches(Regex("\\d+"))) {
            val episodeUrl = "https://assistirseriesonline.icu/episodio/$data"
            
            try {
                // Carrega a página do episódio
                val episodeDocument = app.get(episodeUrl).document
            
                // Procura o botão de embed play
                val embedPlayButton = episodeDocument.selectFirst("button[data-source*='embedplay.upns.pro']") 
                    ?: episodeDocument.selectFirst("button[data-source*='embedplay.upn.one']")
                
                if (embedPlayButton != null) {
                    val embedPlayLink = embedPlayButton.attr("data-source")
                    
                    if (embedPlayLink.isNotBlank()) {
                        loadExtractor(embedPlayLink, episodeUrl, subtitleCallback, callback)
                        return true
                    }
                }
                
                // Procura um iframe de player único como fallback
                val singlePlayerIframe = episodeDocument.selectFirst("div.play-overlay div#player iframe")
                if (singlePlayerIframe != null) {
                    val singlePlayerSrc = singlePlayerIframe.attr("src")
                    if (singlePlayerSrc.isNotBlank()) {
                        loadExtractor(singlePlayerSrc, episodeUrl, subtitleCallback, callback)
                        return true
                    }
                }
            } catch (e: Exception) {
                // Não é necessário imprimir o stack trace em produção, mas pode ser útil para debug.
                // e.printStackTrace() 
            }
                
        // Se a DATA é uma URL HTTP (usado para filmes ou links diretos)
        } else if (data.startsWith("http")) {
            try {
                // Se a data já é o link do extrator (data-source do filme), tenta direto
                if (data.contains("embedplay.upns.") || data.contains("playembedapi.")) {
                    loadExtractor(data, data, subtitleCallback, callback)
                    return true
                }
                
                // Caso seja uma URL de iframe ou player que ainda precisa ser resolvida
                val iframeDocument = app.get(data).document
            
                val embedPlayButton = iframeDocument.selectFirst("button[data-source*='embedplay.upns.pro']")
                    ?: iframeDocument.selectFirst("button[data-source*='embedplay.upn.one']")
                
                if (embedPlayButton != null) {
                    val embedPlayLink = embedPlayButton.attr("data-source")
                    
                    if (embedPlayLink.isNotBlank()) {
                        loadExtractor(embedPlayLink, data, subtitleCallback, callback)
                        return true
                    }
                }
                
                // Fallback para iframe simples
                val singlePlayerIframe = iframeDocument.selectFirst("div.play-overlay div#player iframe")
                if (singlePlayerIframe != null) {
                    val singlePlayerSrc = singlePlayerIframe.attr("src")
                    if (singlePlayerSrc.isNotBlank()) {
                        loadExtractor(singlePlayerSrc, data, subtitleCallback, callback)
                        return true
                    }
                }
            } catch (e: Exception) {
                // e.printStackTrace()
            }
        }
        
        return false
    }

// ... (Função parseDuration permanece)

    private fun parseDuration(text: String?): Int? { // Mude de 'text: String' para 'text: String?'
    if (text.isNullOrBlank()) return null
       
        val h = Regex("(\\d+)h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)m").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (h > 0 || m > 0) h * 60 + m else null
    }
} // <-- FECHAMENTO CORRETO DA CLASSE UltraCine
