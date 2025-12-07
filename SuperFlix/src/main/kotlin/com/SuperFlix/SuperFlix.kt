package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Headers completos para simular o navegador e evitar bloqueios de servidor (Cloudflare/Anti-Scraping)
    private val defaultHeaders = mapOf(
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )

    // Helper: Converte Elemento Jsoup em SearchResponse (Usado em getMainPage e search)
    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title")
        val url = fixUrl(this.attr("href"))
        val posterUrl = this.selectFirst("img.card-img")?.attr("src")?.let { fixUrl(it) }

        if (title.isNullOrEmpty() || url.isNullOrEmpty()) return null

        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()
        val cleanTitle = title.substringBeforeLast("(").trim()

        val type = if (url.contains("/filme/")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(cleanTitle, url, type) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    // Helper: Extrai a URL de embed do Fembed
    private fun getFembedUrl(element: Element): String? {
        val iframeSrc = element.selectFirst("iframe#player")?.attr("src")
        if (!iframeSrc.isNullOrEmpty() && iframeSrc.contains("fembed")) {
            return iframeSrc
        }
        val dataUrl = element.selectFirst("button[data-url]")?.attr("data-url")
        if (!dataUrl.isNullOrEmpty() && dataUrl.contains("fembed")) {
            return dataUrl
        }
        return null
    }

    override val mainPage = listOf(
        MainPageData("Lançamentos", "$mainUrl/lancamentos"),
        MainPageData("Últimos Filmes", "$mainUrl/filmes"),
        MainPageData("Últimas Séries", "$mainUrl/series"),
        MainPageData("Últimos Animes", "$mainUrl/animes")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val type = request.data.substringAfterLast("/")
            if (type.contains("genero")) {
                val genre = request.data.substringAfterLast("genero/").substringBefore("/")
                "$mainUrl/genero/$genre/page/$page"
            } else {
                "$mainUrl/$type/page/$page"
            }
        }
        
        val response = app.get(url, headers = defaultHeaders)
        val document = response.document

        val list = document.select("a.card").mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        // Usa headers para tentar evitar o bloqueio de pesquisa
        val response = app.get(url, headers = defaultHeaders)
                val document = response.document // Corrigido


        return document.select("a.card").mapNotNull { it.toSearchResponse() }
    }

        override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders) 
        val document = response.document

        val isMovie = url.contains("/filme/")

        // CORREÇÃO DEFINITIVA DO SELETOR: Tentando seletores mais robustos
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: document.selectFirst("div.col-md-8 h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Não foi possível extrair o título do filme. Por favor, verifique o seletor H1.")
            
        // O restante dos seletores de poster, plot e tags também estão sendo corrigidos
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        // Seletor de plot corrigido (assumindo que seja o primeiro parágrafo após a sinopse)
        val plot = document.selectFirst("div.col-md-8 p:nth-child(2)")?.text()?.trim() 
            ?: document.selectFirst("p.text-gray-400")?.text()?.trim()
            ?: document.selectFirst("div.mt-4 p")?.text()?.trim()

        val tags = document.select("a[href*=/genero/]").map { it.text().trim() }
        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()

        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (isMovie) {
            val embedUrl = getFembedUrl(document)
            newMovieLoadResponse(title, url, type, embedUrl) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val seasons = document.select("div#season-tabs button").mapIndexed { index, element ->
                val seasonName = element.text().trim()
                newEpisode(url) {
                    name = seasonName
                    season = index + 1
                    episode = 1 
                    data = url 
                }
            }
            newTvSeriesLoadResponse(title, url, type, seasons) { 
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isMovie) {
            // Filmes usam o loadExtractor para resolver a URL do Fembed
            return loadExtractor(data, data, subtitleCallback, callback)
        } else {
            // Séries usam headers para carregar a página de episódio
            val response = app.get(data, headers = defaultHeaders) 
            val document = response.document

            val episodeButtons = document.select("button[data-url*=\"fembed\"]")

            for (button in episodeButtons) {
                val embedUrl = button.attr("data-url")
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback) 
                }
            }
            return true
        }
    }
}
