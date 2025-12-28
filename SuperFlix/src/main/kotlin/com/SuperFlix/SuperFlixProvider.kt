package com.SuperFlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SuperFlixProvider : Plugin() {
    override fun load(context: Context) {
        
        registerMainAPI(SuperFlix())

        registerExtractorAPI(YouTubeTrailerExtractor())
    }
}
