package ro.blazemessenger.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ro.blazemessenger.app.call.TelecomRegistry
import ro.blazemessenger.app.nav.DeepLinks
import ro.blazemessenger.app.notify.NotificationCenter
import ro.blazemessenger.app.ui.root.BlazeRoot

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var deepLinks: DeepLinks

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TelecomRegistry.register(this)
        handleIntent(intent)
        setContent { BlazeRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val extra = intent.getStringExtra(NotificationCenter.EXTRA_CHAT_ID)
        val dataId = intent.data?.takeIf { it.scheme == "blazemessenger" && it.host == "chat" }?.lastPathSegment
        val chatId = extra ?: dataId
        if (!chatId.isNullOrBlank()) deepLinks.openChat(chatId)
    }
}
