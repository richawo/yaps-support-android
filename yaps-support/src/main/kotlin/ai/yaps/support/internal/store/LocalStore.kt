package ai.yaps.support.internal.store

import ai.yaps.support.internal.core.Limits
import ai.yaps.support.internal.protocol.Appearance
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.SdkConfig
import ai.yaps.support.internal.protocol.SdkConversation
import ai.yaps.support.internal.protocol.WireJson
import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
internal data class CachedConversation(
    val conversation: SdkConversation? = null,
    val messages: List<Message> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Local, best-effort persistence. Everything lives under `noBackupFilesDir` (excluded from Auto
 * Backup and device transfer, so support state never follows a user to another device) except a few
 * small preferences.
 */
internal class LocalStore(context: Context) {
    val root: File = File(context.noBackupFilesDir, "yaps-support").apply { mkdirs() }
    val outboxFile: File get() = File(root, "outbox.json")
    val outboxFilesDir: File get() = File(root, "outbox-files").apply { mkdirs() }
    private val historyFile get() = File(root, "history.json")
    private val draftFile get() = File(root, "draft.txt")
    private val appearanceFile get() = File(root, "appearance.json")
    private val prefs: SharedPreferences = context.getSharedPreferences("ai.yaps.support", Context.MODE_PRIVATE)

    // ---- configuration, so WorkManager can rebuild the engine in a cold process ----

    fun saveEndpoint(appId: String, baseUrl: String) {
        prefs.edit().putString(K_APP_ID, appId).putString(K_BASE_URL, baseUrl).apply()
    }

    fun endpoint(): Pair<String, String>? {
        val appId = prefs.getString(K_APP_ID, null) ?: return null
        val baseUrl = prefs.getString(K_BASE_URL, null) ?: return null
        return appId to baseUrl
    }

    var config: SdkConfig?
        get() = prefs.getString(K_CONFIG, null)?.let { runCatching { WireJson.decodeFromString(SdkConfig.serializer(), it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(K_CONFIG) else putString(K_CONFIG, WireJson.encodeToString(SdkConfig.serializer(), value))
            }.apply()
        }

    var customerVerified: Boolean
        get() = prefs.getBoolean(K_VERIFIED, false)
        set(value) = prefs.edit().putBoolean(K_VERIFIED, value).apply()

    var unread: Int
        get() = prefs.getInt(K_UNREAD, 0)
        set(value) = prefs.edit().putInt(K_UNREAD, value).apply()

    /** Push tokens are not secrets (they only address this device) and must survive re-registration. */
    var pushToken: Pair<String, String>?
        get() {
            val token = prefs.getString(K_PUSH_TOKEN, null) ?: return null
            return token to (prefs.getString(K_PUSH_PROVIDER, null) ?: "fcm")
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(K_PUSH_TOKEN).remove(K_PUSH_PROVIDER)
                else putString(K_PUSH_TOKEN, value.first).putString(K_PUSH_PROVIDER, value.second)
            }.apply()
        }

    var appearanceEtag: String?
        get() = prefs.getString(K_ETAG, null)
        set(value) = prefs.edit().apply { if (value == null) remove(K_ETAG) else putString(K_ETAG, value) }.apply()

    // ---- history cache (last 200 messages) ----

    fun loadHistory(): CachedConversation = read(historyFile) { WireJson.decodeFromString(CachedConversation.serializer(), it) } ?: CachedConversation()

    fun saveHistory(value: CachedConversation) {
        val trimmed = if (value.messages.size > Limits.HISTORY_CACHE) {
            value.copy(messages = value.messages.takeLast(Limits.HISTORY_CACHE), hasMore = true)
        } else value
        write(historyFile, WireJson.encodeToString(CachedConversation.serializer(), trimmed))
    }

    // ---- draft ----

    fun loadDraft(): String = read(draftFile) { it }.orEmpty()

    fun saveDraft(text: String) {
        if (text.isEmpty()) draftFile.delete() else write(draftFile, text)
    }

    // ---- appearance ----

    fun loadAppearance(): Appearance? = read(appearanceFile) { WireJson.decodeFromString(Appearance.serializer(), it) }

    fun saveAppearance(appearance: Appearance, etag: String?) {
        write(appearanceFile, WireJson.encodeToString(Appearance.serializer(), appearance))
        appearanceEtag = etag
    }

    /** Logout / delete: everything tied to this support session goes; the endpoint and appearance stay. */
    fun clearSession() {
        historyFile.delete()
        draftFile.delete()
        outboxFile.delete()
        File(root, "outbox-files").deleteRecursively()
        prefs.edit().remove(K_CONFIG).remove(K_UNREAD).remove(K_VERIFIED).apply()
    }

    private fun <T> read(file: File, parse: (String) -> T): T? = try {
        if (file.exists()) parse(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    private fun write(file: File, text: String) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val K_APP_ID = "app_id"
        const val K_BASE_URL = "base_url"
        const val K_CONFIG = "config"
        const val K_VERIFIED = "verified"
        const val K_UNREAD = "unread"
        const val K_PUSH_TOKEN = "push_token"
        const val K_PUSH_PROVIDER = "push_provider"
        const val K_ETAG = "appearance_etag"
    }
}
