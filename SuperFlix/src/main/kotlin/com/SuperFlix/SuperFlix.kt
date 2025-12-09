package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.delay

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
    private val maxSniffingTime = 15000L // 15 segundos máximo

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
        "analytics",
        "google-analytics",
        "doubleclick",
        "googlesyndication",
        "adservice",
        "facebook.com/tr",
        "googletagmanager",
        "googletagservices",
        "tracking",
        "pixel",
        "beacon",
        "ads",
        "adx",
        "banner",
        "logo",
        "icon",
        "thumbnail",
        "poster",
        "placeholder",
        "sprite",
        "preview",
        "teaser",
        "tracker",
        "stat"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("SuperFlix: getMainPage - Iniciando, page=$page, request=${request.name}, data=${request.data}")
        val url = request.data + if (page > 1) "?page=$page" else ""
        println("SuperFlix: getMainPage - URL final: $url")

        val document = app.get(url).document
        println("SuperFlix: getMainPage - Documento obtido, título: ${document.title()}")

        val home = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            println("SuperFlix: getMainPage - Processando elemento: ${element.tagName()}")
            element.toSearchResult()?.let { 
                home.add(it)
                println("SuperFlix: getMainPage - Item adicionado: ${it.name}")
            }
        }

        if (home.isEmpty()) {
            println("SuperFlix: getMainPage - Nenhum item encontrado nos seletores padrão, tentando fallback")
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                println("SuperFlix: getMainPage - Fallback - Link encontrado: $href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val imgElement = link.selectFirst("img")
                    val altTitle = imgElement?.attr("alt") ?: ""

                    val titleElement = link.selectFirst(".rec-title, .title, h2, h3")
                    val elementTitle = titleElement?.text() ?: ""

                    val title = if (altTitle.isNotBlank()) altTitle
                        else if (elementTitle.isNotBlank()) elementTitle
                        else href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    println("SuperFlix: getMainPage - Fallback - Título extraído: $title")

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")

                        println("SuperFlix: getMainPage - Fallback - Dados: cleanTitle=$cleanTitle, year=$year, isSerie=$isSerie")

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
                        println("SuperFlix: getMainPage - Fallback - Item adicionado: $cleanTitle")
                    }
                }
            }
        }

        println("SuperFlix: getMainPage - Total de itens encontrados: ${home.size}")
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        println("SuperFlix: toSearchResult - Iniciando para elemento: ${this.tagName()}")

        val titleElement = selectFirst(".rec-title, .movie-title, h2, h3, .title")
        val title = titleElement?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null.also { println("SuperFlix: toSearchResult - Título não encontrado") }

        println("SuperFlix: toSearchResult - Título encontrado: $title")

        val elementHref = attr("href")
        val href = if (elementHref.isNotBlank()) elementHref else selectFirst("a")?.attr("href")

        if (href.isNullOrBlank()) {
            println("SuperFlix: toSearchResult - href não encontrado")
            return null
        }

        println("SuperFlix: toSearchResult - href encontrado: $href")

        val imgElement = selectFirst("img")
        val posterSrc = imgElement?.attr("src")
        val posterDataSrc = imgElement?.attr("data-src")

        val poster = if (posterSrc.isNullOrBlank()) {
            posterDataSrc?.let { fixUrl(it) }
        } else {
            fixUrl(posterSrc)
        }

        println("SuperFlix: toSearchResult - Poster encontrado: $poster")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let {
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        println("SuperFlix: toSearchResult - Ano encontrado: $year")

        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        println("SuperFlix: toSearchResult - isSerie=$isSerie, cleanTitle=$cleanTitle")

        return if (isSerie) {
            println("SuperFlix: toSearchResult - Criando resposta para série")
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            println("SuperFlix: toSearchResult - Criando resposta para filme")
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("SuperFlix: search - Iniciando busca por: $query")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        println("SuperFlix: search - URL de busca: $searchUrl")

        val document = app.get(searchUrl).document
        println("SuperFlix: search - Documento obtido, título: ${document.title()}")

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            println("SuperFlix: search - Processando elemento de busca: ${element.tagName()}")
            element.toSearchResult()?.let { 
                results.add(it)
                println("SuperFlix: search - Resultado adicionado: ${it.name}")
            }
        }

        println("SuperFlix: search - Total de resultados: ${results.size}")
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("SuperFlix: load - Iniciando carregamento de: $url")

        val document = app.get(url).document
        println("SuperFlix: load - Documento obtido, título: ${document.title()}")

        val html = document.html()
        println("SuperFlix: load - HTML obtido (${html.length} chars)")

        if (html.length < 100) {
            println("SuperFlix: load - ALERTA: HTML muito curto, possivelmente bloqueado")
        }

        val jsonLd = extractJsonLd(html)
        println("SuperFlix: load - JSON-LD extraído: title=${jsonLd.title}, type=${jsonLd.type}")

        val titleElement = document.selectFirst("h1, .title")
        val scrapedTitle = titleElement?.text()

        val title = jsonLd.title ?: scrapedTitle ?: return null.also { 
            println("SuperFlix: load - ERRO: Título não encontrado")
        }

        println("SuperFlix: load - Título final: $title")

        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        println("SuperFlix: load - Ano: $year")

        val ogImageElement = document.selectFirst("meta[property='og:image']")
        val ogImage = ogImageElement?.attr("content")

        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: ogImage?.let { fixUrl(it) }?.replace("/w500/", "/original/")

        println("SuperFlix: load - Poster: $poster")

        val descriptionElement = document.selectFirst("meta[name='description']")
        val metaDescription = descriptionElement?.attr("content")

        val synopsisElement = document.selectFirst(".syn, .description")
        val synopsisText = synopsisElement?.text()

        val plot = jsonLd.description ?: metaDescription ?: synopsisText

        println("SuperFlix: load - Plot (${plot?.length ?: 0} chars): ${plot?.take(100)}...")

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }
        println("SuperFlix: load - Tags encontradas: ${tags.size} - ${tags.take(5)}")

        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        println("SuperFlix: load - Atores encontrados: ${actors.size}")

        val director = jsonLd.director?.firstOrNull()
        println("SuperFlix: load - Diretor: $director")

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"
        println("SuperFlix: load - isSerie=$isSerie (URL tem '/serie/': ${url.contains("/serie/")}, JSON type: ${jsonLd.type})")

        return if (isSerie) {
            println("SuperFlix: load - Processando como SÉRIE")
            val episodes = extractEpisodesFromButtons(document, url)
            println("SuperFlix: load - Episódios encontrados: ${episodes.size}")

            episodes.forEachIndexed { index, episode ->
                println("SuperFlix: load - Episódio $index: ${episode.name}, URL: ${episode.data}")
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            println("SuperFlix: load - Processando como FILME")
            val playerUrl = findPlayerUrl(document)
            println("SuperFlix: load - URL do player encontrada: $playerUrl")

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
    println("SuperFlix: loadLinks - INÍCIO")
    println("SuperFlix: loadLinks - Carregando links de: $data")
    println("SuperFlix: loadLinks - isCasting: $isCasting")

    if (data.isEmpty()) {
        println("SuperFlix: loadLinks - ERRO CRÍTICO: URL vazia")
        return false
    }

    // Se sniffing estiver habilitado, usar nosso sistema de detecção
    if (sniffingEnabled) {
        println("SuperFlix: loadLinks - Usando sistema de network sniffing")
        
        try {
            // Usar sniffing para encontrar URLs de vídeo
            val sniffedUrls = sniffVideoUrls(data)
            println("SuperFlix: loadLinks - URLs encontradas via sniffing: ${sniffedUrls.size}")
            
            if (sniffedUrls.isNotEmpty()) {
                println("SuperFlix: loadLinks - Listando URLs encontradas:")
                sniffedUrls.forEachIndexed { index, url ->
                    println("SuperFlix: loadLinks -   [$index] $url")
                    
                    // Para cada URL encontrada, tentar usar extractor
                    if (loadExtractor(url, subtitleCallback, callback)) {
                        println("SuperFlix: loadLinks - Extractor encontrou links para: $url")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: loadLinks - ERRO no sniffing: ${e.message}")
            e.printStackTrace()
        }
    }

    // Método tradicional: procurar iframes primeiro
    println("SuperFlix: loadLinks - Tentando método tradicional (iframes)")
    try {
        val document = app.get(data).document
        
        // Procurar por iframes primeiro (players embutidos)
        val iframes = document.select("iframe[src]")
        println("SuperFlix: loadLinks - Iframes encontrados: ${iframes.size}")
        
        for (iframe in iframes) {
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                println("SuperFlix: loadLinks - Tentando extrair do iframe: $iframeSrc")
                if (loadExtractor(fixUrl(iframeSrc), subtitleCallback, callback)) {
                    return true
                }
            }
        }
        
        // Procurar por links de vídeo
        val videoLinks = document.select("a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mpd'], button[data-url]")
        println("SuperFlix: loadLinks - Links/buttons de vídeo encontrados: ${videoLinks.size}")
        
        for (link in videoLinks) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: link.attr("data-url")
            if (!href.isNullOrBlank() && isVideoUrl(href)) {
                println("SuperFlix: loadLinks - Tentando extrair do link: $href")
                if (loadExtractor(fixUrl(href), subtitleCallback, callback)) {
                    return true
                }
            }
        }
        
    } catch (e: Exception) {
        println("SuperFlix: loadLinks - ERRO no método tradicional: ${e.message}")
    }

    // Último recurso: tentar extractor genérico na URL original
    println("SuperFlix: loadLinks - Último recurso: extractor genérico")
    return try {
        val result = loadExtractor(data, subtitleCallback, callback)
        println("SuperFlix: loadLinks - loadExtractor retornou: $result")
        result
    } catch (e: Exception) {
        println("SuperFlix: loadLinks - ERRO FINAL: ${e.message}")
        false
    }
}
    // ========== MÉTODOS DE NETWORK SNIFFING ==========
    
   private suspend fun sniffVideoUrls(pageUrl: String): List<String> {
    val videoUrls = mutableSetOf<String>()
    
    try {
        println("SuperFlix: sniffVideoUrls - Iniciando sniffing em: $pageUrl")
        
        // Método 1: Analisar HTML da página
        println("SuperFlix: sniffVideoUrls - Método 1: Analisando HTML")
        val document = app.get(pageUrl, headers = getDefaultHeaders()).document
        val html = document.html()
        println("SuperFlix: sniffVideoUrls - HTML obtido (${html.length} chars)")
        
        // Extrair todas as URLs do HTML primeiro
        println("SuperFlix: sniffVideoUrls - Extraindo todas as URLs do HTML...")
        val urlPattern = Regex("""https?://[^\s"'<>{}()]+""")
        val allUrls = urlPattern.findAll(html).map { it.value }.toList()
        println("SuperFlix: sniffVideoUrls - Total de URLs encontradas no HTML: ${allUrls.size}")
        
        // Filtrar URLs de vídeo
        val filteredUrls = allUrls.filter { isVideoUrl(it) }
        println("SuperFlix: sniffVideoUrls - URLs de vídeo encontradas: ${filteredUrls.size}")
        filteredUrls.forEach { url ->
            println("SuperFlix: sniffVideoUrls -   URL encontrada: $url")
            videoUrls.add(url)
        }
        
        // Extrair de tags específicas
        extractVideoUrlsFromHtml(document, videoUrls)
        
        // Método 2: Analisar requisições de rede via WebView (simulado)
        println("SuperFlix: sniffVideoUrls - Método 2: Simulando network sniffing")
        val networkRequests = simulateNetworkSniffing(pageUrl)
        println("SuperFlix: sniffVideoUrls - Requisições de rede simuladas: ${networkRequests.size}")
        extractVideoUrlsFromNetwork(networkRequests, videoUrls)
        
        // Método 3: Analisar scripts JavaScript
        println("SuperFlix: sniffVideoUrls - Método 3: Analisando scripts JavaScript")
        extractVideoUrlsFromScripts(document, videoUrls)
        
        // Método 4: Analisar iframes
        println("SuperFlix: sniffVideoUrls - Método 4: Analisando iframes")
        val iframes = document.select("iframe")
        println("SuperFlix: sniffVideoUrls - Iframes encontrados: ${iframes.size}")
        extractVideoUrlsFromIframes(document, videoUrls)
        
        // Método 5: Analisar dados JSON
        println("SuperFlix: sniffVideoUrls - Método 5: Analisando dados JSON")
        val jsonScripts = document.select("script[type='application/ld+json']")
        println("SuperFlix: sniffVideoUrls - Scripts JSON-LD encontrados: ${jsonScripts.size}")
        extractVideoUrlsFromJsonData(document, videoUrls)
        
    } catch (e: Exception) {
        println("SuperFlix: sniffVideoUrls - Erro: ${e.message}")
        e.printStackTrace()
    }
    
    println("SuperFlix: sniffVideoUrls - Total de URLs de vídeo coletadas: ${videoUrls.size}")
    val filtered = videoUrls.filter { url -> isVideoUrl(url) && !shouldFilterUrl(url) }.toList()
    println("SuperFlix: sniffVideoUrls - URLs após filtro: ${filtered.size}")
    filtered.forEachIndexed { index, url ->
        println("SuperFlix: sniffVideoUrls -   [$index] $url")
    }
    
    return filtered
}

private fun extractVideoUrlsFromHtml(document: Element, urlSet: MutableSet<String>) {
    println("SuperFlix: extractVideoUrlsFromHtml - Extraindo de tags HTML específicas")
    
    // Extrair tags <video>
    val videos = document.select("video")
    println("SuperFlix: extractVideoUrlsFromHtml - Tags <video> encontradas: ${videos.size}")
    videos.forEach { video ->
        video.attr("src")?.let { src ->
            if (isVideoUrl(src)) {
                println("SuperFlix: extractVideoUrlsFromHtml - src de <video>: $src")
                urlSet.add(fixUrl(src))
            }
        }
        
        val sources = video.select("source")
        println("SuperFlix: extractVideoUrlsFromHtml - Tags <source> encontradas: ${sources.size}")
        sources.forEach { source ->
            source.attr("src")?.let { src ->
                if (isVideoUrl(src)) {
                    println("SuperFlix: extractVideoUrlsFromHtml - src de <source>: $src")
                    urlSet.add(fixUrl(src))
                }
            }
        }
        
        video.attr("data-src")?.let { dataSrc ->
            if (isVideoUrl(dataSrc)) {
                println("SuperFlix: extractVideoUrlsFromHtml - data-src de <video>: $dataSrc")
                urlSet.add(fixUrl(dataSrc))
            }
        }
    }
    
    // Extrair atributos data-*
    val dataElements = document.select("[data-src], [data-url], [data-file], [data-video], [data-source]")
    println("SuperFlix: extractVideoUrlsFromHtml - Elementos com atributos data-*: ${dataElements.size}")
    dataElements.forEach { element ->
        val src = element.attr("data-src") ?: 
                 element.attr("data-url") ?: 
                 element.attr("data-file") ?: 
                 element.attr("data-video") ?: 
                 element.attr("data-source")
        if (!src.isNullOrBlank() && isVideoUrl(src)) {
            println("SuperFlix: extractVideoUrlsFromHtml - data-*: $src")
            urlSet.add(fixUrl(src))
        }
    }
    
    // Extrair links com extensões de vídeo
    val links = document.select("a[href]")
    println("SuperFlix: extractVideoUrlsFromHtml - Links <a> encontrados: ${links.size}")
    var videoLinksFound = 0
    links.forEach { link ->
        val href = link.attr("href")
        if (isVideoUrl(href)) {
            println("SuperFlix: extractVideoUrlsFromHtml - Link de vídeo: $href")
            urlSet.add(fixUrl(href))
            videoLinksFound++
        }
    }
    println("SuperFlix: extractVideoUrlsFromHtml - Links de vídeo encontrados: $videoLinksFound")
}

private suspend fun simulateNetworkSniffing(url: String): List<NetworkRequest> {
    println("SuperFlix: simulateNetworkSniffing - Simulando sniffing em: $url")
    val networkRequests = mutableListOf<NetworkRequest>()
    
    try {
        // Simular carregamento da página e extrair URLs
        val response = app.get(url, headers = getDefaultHeaders())
        val html = response.text
        println("SuperFlix: simulateNetworkSniffing - HTML obtido: ${html.length} chars")
        
        // Extrair URLs do conteúdo
        extractUrlsFromText(html, networkRequests)
        
        println("SuperFlix: simulateNetworkSniffing - URLs extraídas do texto: ${networkRequests.size}")
        
    } catch (e: Exception) {
        println("SuperFlix: simulateNetworkSniffing - Erro: ${e.message}")
    }
    
    return networkRequests
}

private fun extractUrlsFromText(text: String, requests: MutableList<NetworkRequest>) {
    // Primeiro, mostrar um trecho do texto para debug
    val sampleText = text.take(5000)
    println("SuperFlix: extractUrlsFromText - Analisando texto (amostra): ${sampleText.length} chars")
    println("SuperFlix: extractUrlsFromText - Amostra: ${sampleText.take(200)}...")
    
    // Padrão para encontrar URLs
    val urlPattern = Regex("""https?://[^\s"'<>{}()]+""")
    
    val allUrls = urlPattern.findAll(text).map { it.value }.toList()
    println("SuperFlix: extractUrlsFromText - Todas as URLs encontradas: ${allUrls.size}")
    
    // Filtrar apenas URLs de vídeo
    val videoUrls = allUrls.filter { isVideoUrl(it) }
    println("SuperFlix: extractUrlsFromText - URLs de vídeo encontradas: ${videoUrls.size}")
    
    videoUrls.forEach { url ->
        println("SuperFlix: extractUrlsFromText - URL de vídeo: $url")
        requests.add(NetworkRequest(url, emptyMap()))
    }
    
    // Padrões específicos para streaming
    println("SuperFlix: extractUrlsFromText - Buscando padrões específicos de streaming...")
    val streamingPatterns = listOf(
        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
        Regex("""["'](https?://[^"']+\.mpd[^"']*)["']"""),
        Regex("""file\s*:\s*["']([^"']+)["']"""),
        Regex("""src\s*:\s*["']([^"']+)["']"""),
        Regex("""url\s*:\s*["']([^"']+)["']"""),
        Regex("""hls\s*:\s*["']([^"']+)["']"""),
        Regex("""dash\s*:\s*["']([^"']+)["']"""),
        Regex("""manifest\s*:\s*["']([^"']+)["']"""),
        Regex("""source\s*:\s*["']([^"']+)["']"""),
        Regex("""videoUrl\s*:\s*["']([^"']+)["']"""),
        Regex("""streamUrl\s*:\s*["']([^"']+)["']""")
    )
    
    var patternsFound = 0
    streamingPatterns.forEach { pattern ->
        val matches = pattern.findAll(text).toList()
        if (matches.isNotEmpty()) {
            println("SuperFlix: extractUrlsFromText - Padrão '${pattern.pattern.take(20)}...' encontrou ${matches.size} matches")
            matches.forEach { match ->
                val url = match.groupValues.getOrNull(1) ?: return@forEach
                if (isVideoUrl(url)) {
                    println("SuperFlix: extractUrlsFromText - URL via padrão: $url")
                    requests.add(NetworkRequest(fixUrl(url), emptyMap()))
                    patternsFound++
                }
            }
        }
    }
    println("SuperFlix: extractUrlsFromText - URLs via padrões específicos: $patternsFound")
}

// Adicionar este método para debug do isVideoUrl
private fun debugIsVideoUrl(url: String): Boolean {
    if (url.isBlank() || url.length < 10) {
        println("SuperFlix: debugIsVideoUrl - URL muito curta ou vazia")
        return false
    }
    
    if (!url.startsWith("http")) {
        println("SuperFlix: debugIsVideoUrl - URL não começa com http: $url")
        return false
    }
    
    val videoMatches = videoPatterns.mapIndexed { index, pattern ->
        val matches = pattern.containsMatchIn(url)
        if (matches) {
            println("SuperFlix: debugIsVideoUrl - Padrão $index encontrou match: ${pattern.pattern.take(30)}...")
        }
        matches
    }
    
    val isVideo = videoMatches.any { it }
    println("SuperFlix: debugIsVideoUrl - $url é vídeo? $isVideo")
    return isVideo
}

// Atualizar o método isVideoUrl para usar debug
private fun isVideoUrl(url: String): Boolean {
    // Para debug, comentar esta linha e usar a versão de debug
    // return debugIsVideoUrl(url)
    
    if (url.isBlank() || url.length < 10) return false
    
    // Verificar padrões de vídeo
    val isVideo = videoPatterns.any { it.containsMatchIn(url) }
    
    // Verificar se parece ser uma URL válida
    val isValidUrl = url.startsWith("http") && url.contains("://")
    
    return isVideo && isValidUrl
}
    
    private fun shouldFilterUrl(url: String): Boolean {
        return filterPatterns.any { url.contains(it, ignoreCase = true) }
    }
    
    private fun detectQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080") || url.contains("fullhd", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720") || url.contains("hd", ignoreCase = true) -> Qualities.P720.value
            url.contains("480") || url.contains("sd", ignoreCase = true) -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            url.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
    
    private fun detectSourceFromUrl(url: String): String {
        return when {
            url.contains(".m3u8") -> "HLS Stream"
            url.contains(".mpd") -> "MPEG-DASH"
            url.contains("googlevideo") -> "Google Video"
            url.contains("youtube") -> "YouTube"
            url.contains("vimeo") -> "Vimeo"
            url.contains("filemoon") -> "Filemoon"
            url.contains("fembed") -> "Fembed"
            else -> "Direct Stream"
        }
    }
    
    private fun getDefaultHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "max-age=0"
        )
    }
    
    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        println("SuperFlix: extractEpisodesFromButtons - Iniciando extração de episódios")
        val episodes = mutableListOf<Episode>()

        val buttons = document.select("button.bd-play[data-url]")
        println("SuperFlix: extractEpisodesFromButtons - Botões encontrados: ${buttons.size}")

        buttons.forEachIndexed { index, button ->
            println("SuperFlix: extractEpisodesFromButtons - Processando botão $index")
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            println("SuperFlix: extractEpisodesFromButtons - URL: $fembedUrl, Season: $season, Episode: $episodeNum")

            var episodeTitle = "Episódio $episodeNum"

            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4")
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                    println("SuperFlix: extractEpisodesFromButtons - Título encontrado: $episodeTitle")
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
            println("SuperFlix: extractEpisodesFromButtons - Episódio adicionado: $episodeTitle")
        }

        println("SuperFlix: extractEpisodesFromButtons - Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        println("SuperFlix: findPlayerUrl - Iniciando busca por URL do player")

        // Procurar por botões com data-url (principal método)
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("SuperFlix: findPlayerUrl - URL encontrada em botão: $url")
            return url
        }
        println("SuperFlix: findPlayerUrl - Nenhum botão com data-url encontrado")

        // Procurar por iframes
        val iframes = document.select("iframe")
        println("SuperFlix: findPlayerUrl - Iframes encontrados: ${iframes.size}")

        iframes.forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            println("SuperFlix: findPlayerUrl - Iframe $index: $src")
        }

        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("SuperFlix: findPlayerUrl - URL encontrada em iframe: $url")
            return url
        }
        println("SuperFlix: findPlayerUrl - Nenhum iframe relevante encontrado")

        // Procurar por links de vídeo
        val videoLinks = document.select("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        println("SuperFlix: findPlayerUrl - Links de vídeo encontrados: ${videoLinks.size}")

        if (videoLinks.isNotEmpty()) {
            // CORREÇÃO: Usar safe call para first() e attr()
            val firstVideoLink = videoLinks.firstOrNull()
            val url = firstVideoLink?.attr("href")
            if (!url.isNullOrBlank()) {
                println("SuperFlix: findPlayerUrl - URL encontrada em link: $url")
                return url
            }
        }

        println("SuperFlix: findPlayerUrl - Nenhuma URL de player encontrada")
        return null
    }

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
        println("SuperFlix: extractJsonLd - Iniciando extração de JSON-LD")

        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)

        println("SuperFlix: extractJsonLd - Encontrados ${matches.count()} scripts JSON-LD")

        matches.forEachIndexed { index, match ->
            try {
                println("SuperFlix: extractJsonLd - Processando JSON-LD $index")
                val json = match.groupValues[1].trim()
                println("SuperFlix: extractJsonLd - JSON (${json.length} chars): ${json.take(200)}...")

                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {
                    println("SuperFlix: extractJsonLd - JSON é do tipo Movie ou TVSeries")

                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    println("SuperFlix: extractJsonLd - Título extraído: $title")

                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    println("SuperFlix: extractJsonLd - Imagem extraída: $image")

                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    println("SuperFlix: extractJsonLd - Descrição extraída (${description?.length ?: 0} chars)")

                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }
                    println("SuperFlix: extractJsonLd - Gêneros extraídos: ${genres?.size ?: 0} - ${genres?.take(3)}")

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
                // Continuar para o próximo JSON-LD
            }
        }
        
        return JsonLdInfo()
    }
    
    data class NetworkRequest(
        val url: String,
        val headers: Map<String, String>
    )
}