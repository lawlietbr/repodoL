ackage com.NovelasFlix

import android.content.Context // <-- OBRIGATÓRIO!
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // <-- CLASSE PAI CORRETA (Plugin, não BasePlugin)

@CloudstreamPlugin
class NovelasFlixProviderPlugin: Plugin() {
    
    // A assinatura CORRETA para a função de carregamento
    override fun load(context: Context) {
        // Registra a API principal (NovelasFlix)
        registerMainAPI(NovelasFlix())
    }
}
