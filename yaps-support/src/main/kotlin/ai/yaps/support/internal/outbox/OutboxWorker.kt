package ai.yaps.support.internal.outbox

import ai.yaps.support.YapsSupport
import ai.yaps.support.internal.core.Log
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Finishes sends that were in flight when the app went to the background. Enqueued only when the
 * outbox has pending entries at `ON_STOP`, with a network constraint, so a user who taps Send and
 * immediately leaves still gets their message delivered. The engine is rebuilt from the persisted
 * App ID if the process was cold-started for this work.
 */
internal class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = YapsSupport.engineForBackgroundWork(applicationContext) ?: return Result.success()
        return try {
            if (engine.flushForWorker()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w("Background outbox flush failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "ai.yaps.support.outbox"

        fun enqueue(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Log.w("WorkManager unavailable; pending messages send on next open", it) }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}
