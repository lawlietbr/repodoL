package com.AniTube

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AniTubePlugin : Plugin() {
    override fun load(context: Context) {
        
        registerMainAPI(AniTube())
    }
}
