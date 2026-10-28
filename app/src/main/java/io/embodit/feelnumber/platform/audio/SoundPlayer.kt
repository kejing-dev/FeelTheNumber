package io.embodit.feelnumber.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.embodit.feelnumber.R
import io.embodit.feelnumber.platform.FeelLog

/**
 * The two short sounds of the design (`audio`): the reveal "pop" and the three-star chime, plus a soft
 * tick when a dragged guess snaps to a wall. Synthesised offline (tools/make_sounds.py, CC0 by
 * construction), played through SoundPool at half volume (taste D15: quiet by default; muting is
 * decided by the ViewModel, which never sends a sound while muted).
 */
class SoundPlayer(context: Context) {
    private val pool =
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
    private val pop = pool.load(context, R.raw.pop, 1)
    private val chime = pool.load(context, R.raw.chime, 1)
    private val tick = pool.load(context, R.raw.tick, 1)

    fun pop() = play(pop, "pop")

    fun chime() = play(chime, "chime")

    fun tick() = play(tick, "tick", VOLUME * 0.6f)

    private fun play(id: Int, name: String, volume: Float = VOLUME) {
        val stream = pool.play(id, volume, volume, 1, 0, 1f)
        FeelLog.i("SOUND $name stream=$stream")
    }

    fun release() = pool.release()

    private companion object {
        const val VOLUME = 0.5f
    }
}
