package com.AnimeFire

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import org.jsoup.Jsoup

object AnimeFireVideoExtractor {
    private val itagQualityMap = mapOf(
        18 to 360,
        22 to 720,
        37 to 1080,
        59 to 480,
        43 to 360,
        44 to 480,
        45 to 720,
        46 to 1080,
    )
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResponse = app.get(url)
        val doc = Jsoup.parse(pageResponse.text)
        
        val hasBloggerIframe = doc.selectFirst("iframe[src*='blogger.com/video.g']") != null
        
        return if (hasBloggerIframe) {
            extractBloggerVideo(doc, url, name, callback)
        } else {
            extractLightspeedVideo(url, name, callback)
        }
    }
    
    private suspend fun extractBloggerVideo(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val iframe = doc.selectFirst("iframe[src*='blogger.com/video.g']") ?: return false
            val iframeUrl = iframe.attr("src")
            
            val iframeResponse = app.get(iframeUrl, headers = mapOf(
                "Referer" to originalUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            extractFromBloggerHtml(iframeResponse.text, iframeUrl, name, callback)
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromBloggerHtml(
        html: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val configPattern = """var\s+VIDEO_CONFIG\s*=\s*(\{.*?\})\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val configMatch = configPattern.find(html)
        
        if (configMatch != null) {
            return try {
                val config = JSONObject(configMatch.groupValues[1])
                val streams = config.getJSONArray("streams")
                
                var found = false
                for (i in 0 until streams.length()) {
                    val stream = streams.getJSONObject(i)
                    val videoUrl = stream.getString("play_url")
                    val itag = stream.getInt("format_id")
                    val quality = itagQualityMap[itag] ?: 360
                    
                    val qualityLabel = getQualityLabel(quality)
                    
                    val extractorLink = newExtractorLink(
                        source = "AnimeFire",
                        name = "$name ($qualityLabel)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Origin" to "https://www.blogger.com"
                        )
                    }
                    
                    callback(extractorLink)
                    found = true
                }
                
                found
            } catch (e: Exception) {
                false
            }
        }
        
        val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
        val matches = videoPattern.findAll(html).toList()
        
        if (matches.isNotEmpty()) {
            var found = false
            for (match in matches) {
                val videoUrl = match.value
                val itagPattern = """[?&]itag=(\d+)""".toRegex()
                val itagMatch = itagPattern.find(videoUrl)
                val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                val quality = itagQualityMap[itag] ?: 360
                val qualityLabel = getQualityLabel(quality)
                
                val extractorLink = newExtractorLink(
                    source = "AnimeFire",
                    name = "$name ($qualityLabel)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Origin" to "https://www.blogger.com"
                    )
                }
                
                callback(extractorLink)
                found = true
            }
            
            return found
        }
        
        return false
    }
    
    private suspend fun extractLightspeedVideo(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pathParts = url.removePrefix("https://animefire.io/animes/").split("/")
            if (pathParts.size < 2) return false
            
            val slug = pathParts[0]
            val ep = pathParts[1].toIntOrNull() ?: 1
            
            val xhrUrl = "https://animefire.io/video/$slug/$ep?tempsubs=0&${System.currentTimeMillis()/1000}"
            
            val response = app.get(xhrUrl, headers = mapOf(
                "Referer" to url,
                "X-RequestedWith" to "XMLHttpRequest",
                "UserAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            val responseText = response.text
            if (responseText.isEmpty()) return false
            
            val json = JSONObject(responseText)
            val data = json.getJSONArray("data")
            
            var found = false
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val videoUrl = item.getString("src")
                val label = item.optString("label", "")
                
                val quality = when {
                    label.contains("1080") -> 1080
                    label.contains("720") -> 720
                    label.contains("480") -> 480
                    label.contains("360") -> 360
                    label.contains("240") -> 240
                    videoUrl.contains("lightspeedst") -> 720
                    else -> 480
                }
                
                val qualityLabel = getQualityLabel(quality)
                
                val extractorLink = newExtractorLink(
                    source = "AnimeFire",
                    name = "$name ($qualityLabel)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to url,
                        "UserAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
                
                callback(extractorLink)
                found = true
            }
            
            found
            
        } catch (e: Exception) {
            false
        }
    }
    
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            quality >= 360 -> "SD"
            else -> "SD"
        }
    }
}
