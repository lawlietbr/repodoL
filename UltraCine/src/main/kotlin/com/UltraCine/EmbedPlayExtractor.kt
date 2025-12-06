package com.UltraCine

import com.lagradost.cloudstream3.extractors.VidStack

// CORRIGIDO: Este deve cobrir o link 'embedplay.upns.ink' que você viu no site.
class EmbedPlayUpnsInk : VidStack() {
    override var name = "EmbedPlay Upns Ink"
    override var mainUrl = "http://embedplay.upns.ink" // Use http ou https
}

// Mantenha este, caso o UltraCine volte a usar o .pro
class EmbedPlayUpnsPro : VidStack() {
    override var name = "EmbedPlay UpnsPro"
    override var mainUrl = "https://embedplay.upns.pro" 
}

// Mantenha este, caso o UltraCine use o .one
class EmbedPlayUpnOne : VidStack() {
    override var name = "EmbedPlay UpnOne"
    override var mainUrl = "http://embedplay.upn.one"
}
