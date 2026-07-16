package com.maksimowiczm.foodyou.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.maksimowiczm.foodyou.app.infrastructure.FoodYouLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only way anything triggers a widget update.
 *
 * It exists to swallow: `updateAll` can throw (a dead GlanceId, oversized RemoteViews, an IPC
 * failure), and it is called from inside [SyncWorker.doWork]. An uncaught throw there makes
 * WorkManager record the run as `Result.failure()` — for the one-shot "sync now" job that means a
 * successful sync is reported as failed. The sync itself is unharmed (its data and status are
 * written before this fires, and periodic work reschedules regardless), but the widget is
 * downstream of sync and must not write false status onto it.
 */
internal object FoodYouWidgetUpdater {

    suspend fun updateAll(context: Context) {
        try {
            FoodYouGlanceWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FoodYouLogger.e(TAG, e) { "Widget update failed" }
        }
    }

    private const val TAG = "FoodYouWidgetUpdater"
}
