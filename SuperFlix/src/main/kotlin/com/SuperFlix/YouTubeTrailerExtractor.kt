package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.M3u8Helper2
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.regex.Pattern

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.5"
        )
    }

    private fun debugLog(message: String) {
        println("🔍 YouTubeExt: $message")
    }

    private fun extractYtCfg(html: String): JSONObject? {
return try {
            val pattern = Pattern.compile("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\)\\s*;")
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                JSONObject(matcher.group(1))
} else {
                null
}
} catch (e: Exception) {
            debugLog("ytcfg error: ${e.message}")
            null
}
}

    private suspend fun getPageConfig(videoId: String): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                debugLog("Fetching page: $url")
                
                val response = app.get(url, headers = HEADERS)
                if (response.isSuccessful) {
                    val html = response.text
                    val ytCfg = extractYtCfg(html)
                    
                    ytCfg?.let {
                        val apiKey = it.optString("INNERTUBE_API_KEY")
                        val clientVersion = it.optString("INNERTUBE_CLIENT_VERSION")
                        val visitorData = it.optString("VISITOR_DATA")
                        
                        if (apiKey.isNotBlank() && clientVersion.isNotBlank()) {
                            mapOf(
                                "apiKey" to apiKey,
                                "clientVersion" to clientVersion,
                                "visitorData" to visitorData
                            )
                        } else {
                            debugLog("API key or client version not found")
                            null
                        }
                    }
                } else {
                    debugLog("Failed to fetch page: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                debugLog("Error in getPageConfig: ${e.message}")
                null
            }
}
}

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
callback: (ExtractorLink) -> Unit
    ) {
        try {
            debugLog("Processing URL: $url")
            
            // Extrair videoId
            val videoId = extractVideoId(url)
            if (videoId.isEmpty()) {
                debugLog("Could not extract videoId")
                return
            }

            debugLog("Video ID: $videoId")

            // Obter configuração da página
            val config = getPageConfig(videoId)

            if (config != null) {
                val apiKey = config["apiKey"]
                val clientVersion = config["clientVersion"]
                val visitorData = config["visitorData"]

                if (apiKey != null && clientVersion != null) {
                    // Fazer requisição para a API do YouTube
                    val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
                    
                    val jsonBody = """
                    {
                        "context": {
                            "client": {
                                "hl": "en",
                                "gl": "US",
                                "clientName": "WEB",
                                "clientVersion": "$clientVersion",
                                "visitorData": "$visitorData",
                                "platform": "DESKTOP",
                                "userAgent": "$USER_AGENT"
                            }
                        },
                        "videoId": "$videoId",
                        "playbackContext": {
                            "contentPlaybackContext": {
                                "html5Preference": "HTML5_PREF_WANTS"
                            }
                        }
                    }
                    """.trimIndent()
                    
                    val requestBody = jsonBody.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                    
                    debugLog("Making API request to: $apiUrl")

                    val response = app.post(
                        apiUrl,
                        headers = HEADERS,
                        requestBody = requestBody
                    )

                    if (response.isSuccessful) {
                        val jsonResponse = JSONObject(response.text)
                        val streamingData = jsonResponse.optJSONObject("streamingData")
                        
                        if (streamingData != null) {
                            val hlsUrl = streamingData.optString("hlsManifestUrl")
                            
                            if (hlsUrl.isNotBlank()) {
                                debugLog("HLS URL found: $hlsUrl")
                                
                                // Processar stream M3U8 - método simplificado
                                try {
                                    val m3u8Links = M3u8Helper2.generateM3u8(
                                        "Youtube",
                                        hlsUrl,
                                        mainUrl
                                    )
                                    
                                    m3u8Links?.forEach { link ->
                                        callback(link)
                                    }
                                    
                                    if (m3u8Links != null && m3u8Links.isNotEmpty()) {
                                        return
                                    }
                                } catch (e: Exception) {
                                    debugLog("M3U8 processing failed: ${e.message}")
                                }
                            }
                        }
                    }
}
}
            
            // Fallback para URL direta
            sendFallbackUrl(videoId, callback)
            
} catch (e: Exception) {
            debugLog("Error: ${e.message}")
            e.printStackTrace()
            
            // Fallback em caso de erro
            try {
                val videoId = extractVideoId(url)
                if (videoId.isNotEmpty()) {
                    sendFallbackUrl(videoId, callback)
                }
            } catch (e2: Exception) {
                debugLog("Fallback also failed: ${e2.message}")
            }
}
}

    private suspend fun sendFallbackUrl(videoId: String, callback: (ExtractorLink) -> Unit) {
        debugLog("Using fallback URL")
        
        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
        
        val link = newExtractorLink(
            source = name,
            name = "YouTube Player",
            url = youtubeUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = mainUrl
            quality = 1080
        }

        callback(link)
    }
    
    private fun extractVideoId(url: String): String {
        return try {
            // Padrão 1: v=ID
            val pattern1 = Pattern.compile("v=([a-zA-Z0-9_-]{11})")
            val matcher1 = pattern1.matcher(url)
            if (matcher1.find()) {
                return matcher1.group(1) ?: ""
            }
            
            // Padrão 2: youtu.be/ID
            val pattern2 = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})")
            val matcher2 = pattern2.matcher(url)
            if (matcher2.find()) {
                return matcher2.group(1) ?: ""
            }
            
            // Se já for apenas um ID
            if (url.length == 11 && url.matches(Regex("[a-zA-Z0-9_-]+"))) {
                return url
}
            
            // Último segmento da URL
            url.substringAfterLast("/")
        } catch (e: Exception) {
            ""
}
}
}
