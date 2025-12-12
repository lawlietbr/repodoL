package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // ============ CONFIGURAÇÃO DO PROXY CORRIGIDA ============
    private val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") ||
                     href.contains("/serie/") ||
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))
        val isMovie = !isSerie && !isAnime

        return when {
            isAnime -> {
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            isSerie -> {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                             title.contains("(Anime)", ignoreCase = true)
                val isSerie = badge.contains("série") || badge.contains("serie") ||
                             href.contains("/serie/") ||
                             (!isAnime && (badge.contains("tv") || href.contains("/tv/")))

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] Iniciando load para URL: $url")
        
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        println("🔍 [DEBUG] Título encontrado no site: $title")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] Título limpo: $cleanTitle | Ano: $year")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)
        println("🔍 [DEBUG] Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")

        println("🔍 [DEBUG] Buscando no TMDB...")
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        if (tmdbInfo == null) {
            println("⚠️ [DEBUG] TMDB não retornou informações!")
        } else {
            println("✅ [DEBUG] TMDB OK! Título: ${tmdbInfo.title}, Ano: ${tmdbInfo.year}")
            println("✅ [DEBUG] Poster URL: ${tmdbInfo.posterUrl}")
            println("✅ [DEBUG] Backdrop URL: ${tmdbInfo.backdropUrl}")
            println("✅ [DEBUG] Overview: ${tmdbInfo.overview?.take(50)}...")
            println("✅ [DEBUG] Atores: ${tmdbInfo.actors?.size ?: 0}")
            println("✅ [DEBUG] Trailer: ${tmdbInfo.youtubeTrailer}")
        }

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            println("✅ [DEBUG] Criando resposta COM dados do TMDB")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            println("⚠️ [DEBUG] Criando resposta APENAS com dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB DEBUG] Iniciando busca no TMDB")
        println("🔍 [TMDB DEBUG] Query: $query")
        println("🔍 [TMDB DEBUG] Ano: $year")
        println("🔍 [TMDB DEBUG] Tipo: ${if (isTv) "TV" else "Movie"}")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            // ============ URL CORRIGIDA PARA ROTAS DO SEU PROXY ============
            // Seu proxy tem a rota: "/search?query=avatar&type=movie&year=2023"
            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            
            println("🔗 [TMDB DEBUG] URL da busca: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta: ${response.code}")
            println("📡 [TMDB DEBUG] Tamanho da resposta: ${response.text.length} caracteres")
            
            // Log dos primeiros 500 caracteres da resposta
            val responsePreview = response.text.take(500)
            println("📡 [TMDB DEBUG] Prévia da resposta: $responsePreview...")
            
            if (response.code != 200) {
                println("❌ [TMDB DEBUG] Erro HTTP: ${response.code}")
                return null
            }
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>()
            
            if (searchResult == null) {
                println("❌ [TMDB DEBUG] Falha no parsing da resposta de busca")
                return null
            }
            
            println("✅ [TMDB DEBUG] Parsing OK! Resultados: ${searchResult.results.size}")
            
            // Verifica se temos resultados
            val result = searchResult.results.firstOrNull()
            if (result == null) {
                println("❌ [TMDB DEBUG] Nenhum resultado encontrado para: $query")
                return null
            }
            
            println("✅ [TMDB DEBUG] Primeiro resultado:")
            println("   ID: ${result.id}")
            println("   Título: ${result.title ?: result.name}")
            println("   Data: ${result.release_date ?: result.first_air_date}")
            println("   Poster: ${result.poster_path}")
            
            // Busca detalhes completos (com créditos, vídeos, etc)
            println("🔍 [TMDB DEBUG] Buscando detalhes para ID: ${result.id}")
            val details = getTMDBDetailsWithFullCredits(result.id, isTv)
            
            if (details == null) {
                println("❌ [TMDB DEBUG] Falha ao buscar detalhes")
                return null
            }
            
            println("✅ [TMDB DEBUG] Detalhes OK!")
            println("   Overview: ${details.overview?.take(50)}...")
            println("   Backdrop: ${details.backdrop_path}")
            println("   Runtime: ${details.runtime}")
            println("   Gêneros: ${details.genres?.size ?: 0}")
            println("   Atores: ${details.credits?.cast?.size ?: 0}")
            println("   Vídeos: ${details.videos?.results?.size ?: 0}")
            
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else {
                    null
                }
            }

            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)
            if (youtubeTrailer != null) {
                println("✅ [TMDB DEBUG] Trailer encontrado: $youtubeTrailer")
            } else {
                println("⚠️ [TMDB DEBUG] Nenhum trailer encontrado")
            }
            
            // Para séries, busca informações de temporadas
            val seasonsEpisodes = if (isTv && details != null) {
                println("🔍 [TMDB DEBUG] Buscando temporadas para série...")
                getTMDBAllSeasons(result.id)
            } else {
                emptyMap()
            }
            
            if (isTv) {
                println("✅ [TMDB DEBUG] Temporadas: ${seasonsEpisodes.size}")
                seasonsEpisodes.forEach { (season, episodes) ->
                    println("   Temporada $season: ${episodes.size} episódios")
                }
            }

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = allActors,
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null,
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO na busca do TMDB:")
            e.printStackTrace()
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) {
            println("⚠️ [TMDB DEBUG] Lista de vídeos vazia")
            return null
        }
        
        println("🔍 [TMDB DEBUG] Analisando ${videos.size} vídeos")
        videos.forEachIndexed { index, video ->
            println("   Vídeo $index: ${video.type} - ${video.site} - Oficial: ${video.official}")
        }
        
        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                video.site == "YouTube" && (video.type == "Clip" || video.type == "Featurette") && video.official == true ->
                    Triple(video.key, 6, "YouTube Clip Oficial")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) ->
            "https://www.youtube.com/watch?v=$key"
        }
    }

    private suspend fun getTMDBDetailsWithFullCredits(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB DEBUG] Buscando detalhes para ID $id (Tipo: ${if (isTv) "TV" else "Movie"})")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            // ============ URL CORRIGIDA PARA ROTAS DO SEU PROXY ============
            // Seu proxy tem as rotas: "/movie/550" e "/tv/1399"
            // Vou adicionar o append_to_response como query parameter
            val url = "$TMDB_PROXY_URL/$type/$id?append_to_response=credits,videos"
                     
            println("🔗 [TMDB DEBUG] URL de detalhes: $url")
            
            val response = app.get(url, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta de detalhes: ${response.code}")
            println("📡 [TMDB DEBUG] Tamanho da resposta: ${response.text.length} caracteres")
            
            if (response.code != 200) {
                println("❌ [TMDB DEBUG] Erro HTTP: ${response.code}")
                val errorPreview = response.text.take(200)
                println("📡 [TMDB DEBUG] Erro: $errorPreview")
                return null
            }
            
            val responsePreview = response.text.take(500)
            println("📡 [TMDB DEBUG] Prévia da resposta de detalhes: $responsePreview...")
            
            val details = response.parsedSafe<TMDBDetailsResponse>()
            
            if (details == null) {
                println("❌ [TMDB DEBUG] Falha no parsing dos detalhes")
                return null
            }
            
            println("✅ [TMDB DEBUG] Parsing de detalhes OK!")
            return details
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO ao buscar detalhes:")
            e.printStackTrace()
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        println("🔍 [TMDB DEBUG] Buscando todas as temporadas para série ID: $seriesId")
        
        return try {
            // ============ URL CORRIGIDA PARA ROTAS DO SEU PROXY ============
            // Seu proxy tem a rota: "/tv/1399"
            val seriesDetailsUrl = "$TMDB_PROXY_URL/tv/$seriesId"
            println("🔗 [TMDB DEBUG] URL temporadas: $seriesDetailsUrl")
            
            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta de temporadas: ${seriesResponse.code}")
            
            if (seriesResponse.code != 200) {
                println("❌ [TMDB DEBUG] Erro HTTP: ${seriesResponse.code}")
                return emptyMap()
            }
            
            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>()

            if (seriesDetails == null) {
                println("❌ [TMDB DEBUG] Falha no parsing das temporadas")
                return emptyMap()
            }

            println("✅ [TMDB DEBUG] Parsing de temporadas OK! Total: ${seriesDetails.seasons.size} temporadas")
            
            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    println("🔍 [TMDB DEBUG] Buscando episódios da temporada $seasonNumber")
                    
                    val seasonData = getTMDBSeasonDetails(seriesId, seasonNumber)
                    seasonData?.episodes?.let { episodes ->
                        seasonsEpisodes[seasonNumber] = episodes
                        println("✅ [TMDB DEBUG] Temporada $seasonNumber: ${episodes.size} episódios")
                    }
                }
            }

            seasonsEpisodes
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO ao buscar temporadas:")
            e.printStackTrace()
            emptyMap()
        }
    }

    private suspend fun getTMDBSeasonDetails(seriesId: Int, seasonNumber: Int): TMDBSeasonResponse? {
        println("🔍 [TMDB DEBUG] Buscando detalhes da temporada $seasonNumber")
        
        return try {
            // ============ IMPORTANTE: SEU PROXY NÃO TEM ROTA PARA TEMPORADAS ============
            // Pelas rotas disponíveis, seu proxy não tem rota para /tv/{id}/season/{number}
            // Vou tentar uma rota alternativa ou retornar null
            println("⚠️ [TMDB DEBUG] Seu proxy não tem rota para temporadas específicas")
            println("⚠️ [TMDB DEBUG] Rotas disponíveis: /tv/{id} apenas")
            return null
            
            // Se o proxy tivesse a rota, seria assim:
            // val url = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber"
            // println("🔗 [TMDB DEBUG] URL da temporada: $url")
            // 
            // val response = app.get(url, timeout = 10_000)
            // println("📡 [TMDB DEBUG] Status da resposta da temporada: ${response.code}")
            // 
            // val seasonData = response.parsedSafe<TMDBSeasonResponse>()
            // 
            // if (seasonData == null) {
            //     println("❌ [TMDB DEBUG] Falha no parsing da temporada $seasonNumber")
            //     return null
            // }
            // 
            // println("✅ [TMDB DEBUG] Parsing da temporada $seasonNumber OK!")
            // println("   Episódios: ${seasonData.episodes.size}")
            // println("   Data de estreia: ${seasonData.air_date}")
            // 
            // return seasonData
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO ao buscar temporada $seasonNumber:")
            e.printStackTrace()
            null
        }
    }

    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        println("🏗️ [DEBUG] Criando resposta com dados TMDB")
        
        return if (isAnime || isSerie) {
            println("🏗️ [DEBUG] Criando série/Anime")
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime,
                isSerie = isSerie
            )

            println("🏗️ [DEBUG] Total de episódios extraídos: ${episodes.size}")
            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = type,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres

                tmdbInfo.actors?.let { actors ->
                    println("🏗️ [DEBUG] Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    println("🏗️ [DEBUG] Adicionando trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Recomendações: ${siteRecommendations.size}")
            }
        } else {
            println("🏗️ [DEBUG] Criando filme")
            val playerUrl = findPlayerUrl(document)
            println("🏗️ [DEBUG] Player URL: $playerUrl")

            newMovieLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = playerUrl ?: url
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.duration = tmdbInfo.duration

                tmdbInfo.actors?.let { actors ->
                    println("🏗️ [DEBUG] Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    println("🏗️ [DEBUG] Adicionando trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Recomendações: ${siteRecommendations.size}")
            }
        }
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val recommendations = document.select(".recs-grid .rec-card, .recs-grid a").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?:
                           element.selectFirst(".rec-title")?.text() ?:
                           element.attr("title") ?:
                           return@mapNotNull null

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
                val isSerie = href.contains("/serie/") || href.contains("/tv/")
                val isMovie = !isSerie && !isAnime

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        
        println("🔍 [DEBUG] Recomendações encontradas no site: ${recommendations.size}")
        return recommendations
    }

    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): List<Episode> {
        println("🔍 [DEBUG] Extraindo episódios da URL: $url")
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link, [class*='episode']")
        println("🔍 [DEBUG] Elementos de episódio encontrados: ${episodeElements.size}")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) {
                        println("⚠️ [DEBUG] Elemento $index sem data-url/href")
                        return@forEachIndexed
                    }

                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)
                    if (tmdbEpisode != null) {
                        println("✅ [DEBUG] Episódio $index: S${seasonNumber}E${epNumber} - Dados TMDB encontrados")
                    } else {
                        println("⚠️ [DEBUG] Episódio $index: S${seasonNumber}E${epNumber} - Sem dados TMDB")
                    }

                    val episode = createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        isAnime = isAnime,
                        isSerie = isSerie
                    )

                    episodes.add(episode)
                } catch (e: Exception) {
                    println("❌ [DEBUG] Erro ao processar episódio $index:")
                    e.printStackTrace()
                }
            }
        } else {
            println("⚠️ [DEBUG] Nenhum elemento padrão encontrado, tentando seletores alternativos")
            document.select("[class*='episodio']").forEach { element ->
                try {
                    val link = element.selectFirst("a[href*='episode'], a[href*='episodio'], button[data-url]")
                    val dataUrl = link?.attr("data-url") ?: link?.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEach

                    val epNumber = extractEpisodeNumber(element, episodes.size + 1)
                    val seasonNumber = 1

                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)

                    val episode = createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        isAnime = isAnime,
                        isSerie = isSerie
                    )

                    episodes.add(episode)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        println("✅ [DEBUG] Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) {
            return null
        }
        
        val episodes = tmdbInfo.seasonsEpisodes[season]
        if (episodes == null) {
            println("⚠️ [DEBUG] Temporada $season não encontrada no TMDB")
            return null
        }
        
        return episodes.find { it.episode_number == episode }
    }

    private fun createEpisode(
        dataUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        element: Element,
        tmdbEpisode: TMDBEpisode?,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): Episode {
        println("🏗️ [DEBUG] Criando episódio S${seasonNumber}E${episodeNumber}")
        
        val episodeName = tmdbEpisode?.name ?:
                         element.selectFirst(".ep-title, .title, .episode-title, h3, h4")?.text()?.trim() ?:
                         "Episódio $episodeNumber"
        
        println("🏗️ [DEBUG] Nome do episódio: $episodeName")
        
        return newEpisode(fixUrl(dataUrl)) {
            this.name = episodeName
            this.season = seasonNumber
            this.episode = episodeNumber

            this.posterUrl = tmdbEpisode?.still_path?.let { "$tmdbImageUrl/w300$it" } ?:
                            element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            val descriptionBuilder = StringBuilder()

            tmdbEpisode?.overview?.let { overview ->
                descriptionBuilder.append(overview)
                println("🏗️ [DEBUG] Overview do TMDB: ${overview.take(50)}...")
            }

            tmdbEpisode?.air_date?.let { airDate ->
                try {
                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                    val date = dateFormatter.parse(airDate)
                    this.date = date.time
                    println("🏗️ [DEBUG] Data de exibição: $airDate")
                } catch (e: Exception) {
                }
            }

            val duration = when {
                isAnime -> tmdbEpisode?.runtime ?: 24
                else -> tmdbEpisode?.runtime ?: 0
            }

            if (duration > 0) {
                if (descriptionBuilder.isNotEmpty()) {
                    descriptionBuilder.append("\n\nDuração: ${duration}min")
                } else {
                    descriptionBuilder.append("Duração: ${duration}min")
                }
                println("🏗️ [DEBUG] Duração: ${duration}min")
            }

            if ((isSerie || isAnime) && descriptionBuilder.isEmpty()) {
                element.selectFirst(".ep-desc, .description, .synopsis")?.text()?.trim()?.let { siteDescription ->
                    if (siteDescription.isNotBlank()) {
                        descriptionBuilder.append(siteDescription)
                        println("🏗️ [DEBUG] Descrição do site: ${siteDescription.take(50)}...")
                    }
                }
            }

            this.description = descriptionBuilder.toString().takeIf { it.isNotEmpty() }
            println("🏗️ [DEBUG] Descrição final: ${this.description?.take(50)}...")
        }
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        println("🏗️ [DEBUG] Criando resposta APENAS com dados do site")
        
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }
        println("🏗️ [DEBUG] Poster do site: $poster")

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = description ?: synopsis
        println("🏗️ [DEBUG] Plot do site: ${plot?.take(50)}...")

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()
        println("🏗️ [DEBUG] Tags do site: $tags")

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (isAnime || isSerie) {
            println("🏗️ [DEBUG] Criando série/Anime (apenas site)")
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesWithTMDBInfo(document, url, null, isAnime, isSerie)

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Série criada com ${episodes.size} episódios")
            }
        } else {
            println("🏗️ [DEBUG] Criando filme (apenas site)")
            val playerUrl = findPlayerUrl(document)
            println("🏗️ [DEBUG] Player URL: $playerUrl")
            
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("🔍 [DEBUG] Player URL encontrado no botão: $url")
            return url
        }
        
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("🔍 [DEBUG] Player URL encontrado no iframe: $url")
            return url
        }
        
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        val url = videoLink?.attr("href")
        if (url != null) {
            println("🔍 [DEBUG] Player URL encontrado no link: $url")
        } else {
            println("⚠️ [DEBUG] Nenhum player URL encontrado")
        }
        
        return url
    }

    // ============ CLASSES DE DADOS ============
    
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap()
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}