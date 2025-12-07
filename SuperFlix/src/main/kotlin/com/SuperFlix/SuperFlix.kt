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

    // Headers completos
    private val defaultHeaders = mapOf(
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )
    
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

        val list = document.select("a.card").mapNotNull { element -> 
            val title = element.attr("title")
            val url = fixUrl(element.attr("href"))
            
            if (title.isNullOrEmpty() || url.isNullOrEmpty()) return@mapNotNull null

            val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()
            val cleanTitle = title.substringBeforeLast("(").trim()

            val type = if (url.contains("/filme/")) TvType.Movie else TvType.TvSeries
            
            // CORREÇÃO: Força o retorno do objeto para resolver o escopo
            return@mapNotNull newSearchResponse(cleanTitle, url, type) {
                this.posterUrl = element.selectFirst("img")?.attr("data-src")
                    .takeIf { it?.isNotEmpty() == true } 
                    ?: element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                this.year = year
            }
        }

        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
    val url = "$mainUrl/?s=$query"
    val response = app.get(url, headers = defaultHeaders)
    val document = response.document 

    // Tentamos seletores comuns que englobam o resultado completo,
    // incluindo o link com a classe '.card' ou '.movie-card'
    val results = document.select("a.card, a.movie-card, div.result-item a, a.card-img-top").mapNotNull { it.toSearchResponse() }

    // >>>>> CÓDIGO DE DIAGNÓSTICO <<<<<
    if (results.isEmpty()) {
        // Pega as primeiras 150 caracteres do HTML recebido
        val errorHtml = document.html().take(150)
        // Lança um erro que você verá no log do Cloudstream
        throw ErrorLoadingException("ERRO BUSCA: Nenhum resultado. HTML Recebido (150 chars): $errorHtml")
    }
    // >>>>> FIM DO CÓDIGO DE DIAGNÓSTICO <<<<<
    
    return results
}

    // DENTRO DA FUNÇÃO load(url: String)

// ... (Outras extrações)

// 3. TAGS/GÊNEROS (Seleção direta pela classe .chip)
val tags = document.select("a.chip").map { it.text().trim() }.filter { it.isNotEmpty() }

// 4. ELENCO (ATORES): Estratégia de EXCLUSÃO REFORÇADA
val allDivLinks = document.select("div a").map { it.text().trim() }
val chipTexts = tags.toSet() 

// Atores = Todos os links de DIVs - Links que são Tags
val actors = allDivLinks
    .filter { linkText -> linkText !in chipTexts } // Remove tags
    .filter { linkText -> !linkText.contains("Assista sem anúncios") } // Remove propaganda
    .filter { it.isNotEmpty() && it.length > 2 }   // Remove ruídos
    .distinct() 
    .take(15) // Limita a lista de atores para segurança
    .toList()

// ... (O restante do código permanece o mesmo)



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
