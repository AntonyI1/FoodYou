package com.maksimowiczm.foodyou.app.infrastructure.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.maksimowiczm.foodyou.app.BuildConfig
import com.maksimowiczm.foodyou.app.di.initKoin
import com.maksimowiczm.foodyou.common.domain.date.DateProvider
import com.maksimowiczm.foodyou.common.domain.event.EventBus
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import com.maksimowiczm.foodyou.settings.domain.event.AppLaunchEvent
import com.maksimowiczm.foodyou.sync.domain.SyncPreferences
import com.maksimowiczm.foodyou.sync.infrastructure.work.SyncScheduling
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named

class FoodYouApplication : Application() {

    private val coroutineScope by lazy {
        CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("FoodYouApplication"))
    }

    override fun onCreate() {
        super.onCreate()

        initKoin(coroutineScope) { androidContext(this@FoodYouApplication) }
        publishLaunchEvent()
        setupSync()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            handleUncaughtException(e)
            defaultHandler?.uncaughtException(t, e)
        }
    }

    @Volatile private var syncEnabled = false
    @Volatile private var startedActivities = 0

    /**
     * Schedules WorkManager periodic sync while enabled and triggers a one-time sync when the app
     * comes to the foreground. Polling only — no push, no GMS/FCM (GrapheneOS-friendly).
     */
    private fun setupSync() {
        val syncPreferences: UserPreferencesRepository<SyncPreferences> by
            inject(named(SyncPreferences::class.qualifiedName!!))

        coroutineScope.launch {
            var firstEmission = true
            syncPreferences.observe().map { it.enabled }.distinctUntilChanged().collect { enabled ->
                syncEnabled = enabled
                if (enabled) {
                    SyncScheduling.schedulePeriodic(this@FoodYouApplication)
                } else {
                    SyncScheduling.cancelPeriodic(this@FoodYouApplication)
                }
                // Cold start: the launcher Activity can reach onStart before this flow first
                // emits, so the on-foreground trigger below sees syncEnabled=false. Catch it once.
                if (firstEmission && enabled && startedActivities > 0) {
                    SyncScheduling.syncNow(this@FoodYouApplication)
                }
                firstEmission = false
            }
        }

        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (startedActivities++ == 0 && syncEnabled) {
                        SyncScheduling.syncNow(this@FoodYouApplication)
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    if (startedActivities > 0) startedActivities--
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                    Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    private fun publishLaunchEvent() {
        val dateProvider: DateProvider by inject()
        val eventBus: EventBus by inject()

        val event = AppLaunchEvent(timestamp = dateProvider.nowInstant())
        eventBus.publish(event)
    }

    private fun handleUncaughtException(e: Throwable) {
        val intent = Intent(this, CrashReportActivity::class.java)

        val report = buildString {
            appendLine("Version: ${BuildConfig.VERSION_NAME}")
            appendLine("Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(e.stackTraceToString())
        }

        intent.putExtra("report", report)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        startActivity(intent)
    }
}
