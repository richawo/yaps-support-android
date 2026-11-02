package ai.yaps.support.sample

import ai.yaps.support.SupportChat
import ai.yaps.support.SupportEvent
import ai.yaps.support.YapsSupport
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // `adb shell am start -n ai.yaps.support.sample/.MainActivity --ez embedded true` opens the Compose chat directly.
        val startEmbedded = intent.getBooleanExtra("embedded", false)
        if (intent.getBooleanExtra("open_chat", false) && savedInstanceState == null) YapsSupport.openChat(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var embedded by rememberSaveable { mutableStateOf(startEmbedded) }
                var screen by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    // Link cards for your screens: the SDK has already closed its chat (Activity or Compose).
                    YapsSupport.onScreen = { id -> screen = id }
                }
                if (embedded) {
                    // Compose integration: SupportChat handles insets, IME and predictive back itself.
                    SupportChat(onClose = { embedded = false })
                } else {
                    Home(onEmbedded = { embedded = true })
                }
                screen?.let { id -> ScreenDialog(id, onDismiss = { screen = null }) }
            }
        }
    }
}

@Composable
private fun Home(onEmbedded: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val unread by YapsSupport.unreadCount.collectAsStateWithLifecycle()
    val chatOpen by YapsSupport.isChatOpen.collectAsStateWithLifecycle()
    val log = remember { mutableStateListOf<String>() }
    var paywall by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        YapsSupport.onPaywall = { id -> paywall = id }
        YapsSupport.events.collect { event ->
            log.add(0, event.describe())
            if (log.size > 12) log.removeAt(log.lastIndex)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Support Yaps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "SDK ${YapsSupport.version} · in-process demo backend · chat ${if (chatOpen) "open" else "closed"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { YapsSupport.openChat(context) }, modifier = Modifier.fillMaxWidth()) { Text("Open support (Activity)") }
            }
            Button(onClick = onEmbedded, modifier = Modifier.fillMaxWidth()) { Text("Open support (Compose)") }
            OutlinedButton(onClick = { YapsSupport.identify(userId = "u_42", name = "Ada Lovelace", email = "ada@example.com") }, modifier = Modifier.fillMaxWidth()) {
                Text("Identify as Ada")
            }
            OutlinedButton(onClick = { YapsSupport.track("paywall_declined") }, modifier = Modifier.fillMaxWidth()) { Text("Track paywall_declined") }
            OutlinedButton(onClick = { YapsSupport.requestNotificationPermission(activity) }, modifier = Modifier.fillMaxWidth()) {
                Text("Allow notifications")
            }
            OutlinedButton(
                onClick = {
                    // What a FirebaseMessagingService would forward. Shown unless the chat is on screen.
                    val handled = YapsSupport.handlePush(
                        context,
                        mapOf("support" to "1", "conversationId" to "cnv_demo", "messageId" to "msg_push_${System.currentTimeMillis()}",
                            "title" to "Wakely", "body" to "Frederick: Done 🐓 Max volume is on.", "badge" to "1"),
                    )
                    log.add(0, "handlePush → $handled")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Simulate a support push") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { YapsSupport.logout() }) { Text("Log out") }
                TextButton(onClick = { scope.launch { runCatching { YapsSupport.deleteData() }.onFailure { log.add(0, "deleteData failed: ${it.message}") } } }) {
                    Text("Delete my data")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Events", style = MaterialTheme.typography.titleSmall)
            log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    paywall?.let { id ->
        AlertDialog(
            onDismissRequest = { paywall = null },
            confirmButton = { TextButton(onClick = { paywall = null }) { Text("OK") } },
            title = { Text("Paywall") },
            text = { Text("Your app would present the paywall \"$id\" here (RevenueCat, Superwall, Adapty or Play Billing).") },
        )
    }
}

/** Where a link card's screen would take the user. A real app navigates in [YapsSupport.onScreen]. */
@Composable
private fun ScreenDialog(id: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Screen") },
        text = { Text("Your app would navigate to the screen \"$id\" here.") },
    )
}

private fun SupportEvent.describe(): String = when (this) {
    is SupportEvent.MessageSent -> "messageSent $messageId"
    is SupportEvent.MessageReceived -> "messageReceived $messageId"
    is SupportEvent.ImageSent -> "imageSent ×$count"
    is SupportEvent.ReviewOpened -> "reviewOpened $messageId"
    is SupportEvent.PaywallOpened -> "paywallOpened $paywallId"
    is SupportEvent.AppPromoOpened -> "appPromoOpened $appId"
    is SupportEvent.CodeCopied -> "codeCopied $campaignId"
    is SupportEvent.CodeOpened -> "codeOpened $campaignId"
    is SupportEvent.LinkOpened -> "linkOpened $linkId"
    is SupportEvent.IdentityRejected -> "identityRejected $code"
    SupportEvent.ChatOpened -> "chatOpened"
    SupportEvent.ChatClosed -> "chatClosed"
    SupportEvent.DataDeleted -> "dataDeleted"
}
