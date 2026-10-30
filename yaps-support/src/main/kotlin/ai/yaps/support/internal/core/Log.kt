package ai.yaps.support.internal.core

import ai.yaps.support.LogLevel

/**
 * The SDK's only logger. Never pass it device tokens, identity JWTs, message text or push payloads:
 * log codes and IDs instead.
 */
internal object Log {
    @Volatile var level: LogLevel = LogLevel.WARNING
    private const val TAG = "YapsSupport"

    fun e(message: String, t: Throwable? = null) = log(LogLevel.ERROR, message, t)
    fun w(message: String, t: Throwable? = null) = log(LogLevel.WARNING, message, t)
    fun i(message: String) = log(LogLevel.INFO, message, null)
    fun d(message: String) = log(LogLevel.DEBUG, message, null)

    private fun log(at: LogLevel, message: String, t: Throwable?) {
        if (level == LogLevel.NONE || at.ordinal > level.ordinal) return
        // Throwables can carry URLs with tokens (WebSocket query string), so log only their type and message class.
        val suffix = t?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        try {
            when (at) {
                LogLevel.ERROR -> android.util.Log.e(TAG, message + suffix)
                LogLevel.WARNING -> android.util.Log.w(TAG, message + suffix)
                LogLevel.INFO -> android.util.Log.i(TAG, message + suffix)
                LogLevel.DEBUG -> android.util.Log.d(TAG, message + suffix)
                LogLevel.NONE -> Unit
            }
        } catch (_: RuntimeException) {
            // android.util.Log is a stub in JVM unit tests.
        }
    }
}
