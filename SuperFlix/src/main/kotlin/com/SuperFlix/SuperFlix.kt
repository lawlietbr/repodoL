package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    // IMPORTANTE: Se o teste falhar novamente, altere esta linha para um domínio 
    // que você sabe que funciona e que não está sendo bloqueado pelo site.
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Headers simplificados (anti-bloqueio)
    private val defaultHeaders = mapOf(
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    // A função toSearchResponse está correta, pois a página inicial funciona
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

    // A função getMainPage funciona e está correta
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

    // CORREÇÃO: Tenta o URL de pesquisa com a barra garantida
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val response = app.get(url, isBrowser = true) 
        val document = response.document

        // O erro "did not return any search responses" acontece aqui se o documento
        // for uma página de erro ou CAPTCHA.
        return document.select("a.card").mapNotNull { it.toSearchResponse() }
    }

    // CORREÇÃO: load também usa headers para evitar o bloqueio e "Título não encontrado"
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, isBrowser = true) 
        val document = response.document

        val isMovie = url.contains("/filme/")

        // O fallback do título é crucial aqui, pois a página pode estar vazia
        val title = document.selectFirst("h1.text-3xl")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: throw ErrorLoadingException("Título não encontrado. Bloqueio de página detectado?") // Adiciona a mensagem de bloqueio
            
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val plot = document.selectFirst("p.text-gray-400")?.text()?.trim()
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

    // loadLinks já está usando headers
    override suspend fun loadLinks(
        data: String,
        isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isMovie) {
            return loadExtractor(data, data, subtitleCallback, callback)
        } else {
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
