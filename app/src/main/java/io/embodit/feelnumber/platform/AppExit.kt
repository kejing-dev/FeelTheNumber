package io.embodit.feelnumber.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.WeakHashMap
import kotlin.system.exitProcess

/**
 * [Quit the app] (taste D26): every activity of the process is finished with `finishAndRemoveTask()`, the sensing
 * services are stopped (tracking, planes, controller - the audio pool is released by the screen on dispose), and the
 * process ends shortly after, so the headset frees the tracking and the anchors at once. The activities are known
 * through [Application.ActivityLifecycleCallbacks] (the Stage's activity is created by the SDK, not by the app).
 */
object AppExit : Application.ActivityLifecycleCallbacks {
    private val activities: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())
    private val main = Handler(Looper.getMainLooper())

    @Volatile var exiting = false
        private set

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    /** Finishes the task and ends the process after [graceMs] (time for the Stage to detach and the services to stop). */
    fun quit(graceMs: Long = GRACE_MS) {
        if (exiting) return
        exiting = true
        FeelLog.i("EXIT finishAndRemoveTask activities=${activities.size}")
        for (a in activities.toList()) runCatching { a.finishAndRemoveTask() }.onFailure { FeelLog.w("EXIT finish failed: $it") }
        main.postDelayed({ end() }, graceMs)
    }

    private fun end() {
        runCatching { FeelGraph.planes.stop() }
        runCatching { FeelGraph.head.stop() }
        runCatching { FeelGraph.controller.stop() }
        FeelLog.i("EXIT process end")
        exitProcess(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activities += activity
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities -= activity
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private const val GRACE_MS = 600L
}
