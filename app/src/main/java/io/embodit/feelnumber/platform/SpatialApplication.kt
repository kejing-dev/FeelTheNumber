package io.embodit.feelnumber.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.feelnumber.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FeelGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
