package io.embodit.feelnumber.ui.feel

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import io.embodit.feelnumber.platform.FeelLog
import io.embodit.feelnumber.platform.audio.SoundPlayer
import io.embodit.feelnumber.ui.feel.components.FeelStage
import io.embodit.feelnumber.ui.feel.components.LocalFeelResources
import io.embodit.feelnumber.ui.feel.components.localizedResources
import io.embodit.feelnumber.ui.feel.debug.FeelProbe
import io.embodit.feelnumber.ui.feel.scene.FeelFrameSystem
import io.embodit.feelnumber.ui.feel.scene.FeelScene

/**
 * The one screen of the app, living in a Mixed Stage. The ViewModel holds the state, [FeelScene] turns
 * it into entities and reports taps, drags, the head and the planes, and [FeelStage] declares the panels.
 */
@Composable
fun FeelScreen() {
    val context = LocalContext.current
    val viewModel: FeelViewModel = viewModel(factory = FeelViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()

    remember(context) {
        FeelProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { FeelScene(viewModel::onEvent) }
    val sounds = remember(context) { SoundPlayer(context.applicationContext) }

    DisposableEffect(scene) {
        registerSystem<FeelFrameSystem>()
        FeelProbe.host =
            object : FeelProbe.Host {
                override fun dispatch(event: FeelEvent) = viewModel.onEvent(event)

                override fun uiState(): FeelUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): FeelProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = FeelProbe.click(name)

                override fun setFrozen(on: Boolean) {
                    viewModel.frozen = on
                }
            }
        FeelLog.i("STAGE enter")
        onDispose {
            FeelProbe.host = null
            unregisterSystem<FeelFrameSystem>()
            scene.detach()
            sounds.release()
            FeelLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onEvent(FeelEvent.WentBackground)
                        Lifecycle.Event.ON_START -> viewModel.onEvent(FeelEvent.CameForeground)
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            runCatching {
                when (effect) {
                    FeelEffect.Pop -> sounds.pop()
                    FeelEffect.Chime -> sounds.chime()
                    FeelEffect.Tick -> sounds.tick()
                }
            }.onFailure { FeelLog.w("SOUND failed: $it") }
        }
    }

    val resources = remember(state.lang, context) { localizedResources(context, state.lang) }
    CompositionLocalProvider(LocalFeelResources provides resources) {
        FeelStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
