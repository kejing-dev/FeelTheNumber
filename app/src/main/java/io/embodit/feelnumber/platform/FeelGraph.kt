package io.embodit.feelnumber.platform

import android.content.Context
import io.embodit.feelnumber.data.FeelRepository
import io.embodit.feelnumber.data.PrefsFeelRepository
import io.embodit.feelnumber.platform.input.ControllerInput
import io.embodit.feelnumber.platform.sense.PlaneTracker
import io.embodit.feelnumber.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services
 * (every start has a stop: see `FeelScene.attach` / `detach`); the ViewModel only sees interfaces.
 */
object FeelGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()

    @Volatile var appContext: Context? = null

    @Volatile private var repository: FeelRepository? = null

    fun repository(context: Context): FeelRepository =
        repository ?: synchronized(this) { repository ?: PrefsFeelRepository(context).also { repository = it } }
}
