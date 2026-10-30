package ai.yaps.support

import ai.yaps.support.internal.core.AppearanceResolver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier

/**
 * Full-screen support chat for View-based apps, and the target of support notifications.
 * Open it with [YapsSupport.openChat]. Edge-to-edge, with the system's predictive back animation.
 */
public class SupportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!YapsSupport.isConfigured) YapsSupport.engineForBackgroundWork(applicationContext)
        setContent {
            val remote = YapsSupport.engine?.remoteAppearance?.takeIf { YapsSupport.usesRemoteAppearance }
            val scheme = AppearanceResolver.resolve(remote, YapsSupport.appearance, "en").colorScheme
            val dark = when (scheme) {
                SupportColorScheme.DARK -> true
                SupportColorScheme.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // The system handles Back here (predictive back-to-app animation), so the chat doesn't intercept it.
            SupportChatHost(modifier = Modifier, onClose = { finish() }, interceptBack = false)
        }
    }

    public companion object {
        internal const val EXTRA_FROM_PUSH: String = "ai.yaps.support.extra.FROM_PUSH"
        internal const val EXTRA_CONVERSATION_ID: String = "ai.yaps.support.extra.CONVERSATION_ID"

        /** An intent for this activity, if you prefer to start it yourself. */
        @JvmStatic
        public fun intent(context: Context): Intent = Intent(context, SupportActivity::class.java)
    }
}
