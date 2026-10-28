package io.embodit.feelnumber.data

import android.content.Context
import io.embodit.feelnumber.domain.model.Best
import io.embodit.feelnumber.domain.model.RoomBox
import io.embodit.feelnumber.ui.feel.Lang
import org.json.JSONObject

/** What is kept between runs (design `persistence`): best results, language, mute, the virtual room's size. */
data class Saved(
    val lang: Lang?,
    val muted: Boolean,
    val best: Map<String, Best>,
    val roomL: Float,
    val roomW: Float,
    val roomH: Float,
)

interface FeelRepository {
    fun load(): Saved

    fun saveLang(lang: Lang)

    fun saveMuted(muted: Boolean)

    fun saveBest(best: Map<String, Best>)

    fun saveRoomSize(l: Float, w: Float, h: Float)
}

/**
 * SharedPreferences store (the design names DataStore; a handful of keys does not need it and the
 * reference apps all use SharedPreferences). Writes are `apply()`: off the main thread by the platform.
 */
class PrefsFeelRepository(context: Context) : FeelRepository {
    private val prefs = context.getSharedPreferences("feel_number", Context.MODE_PRIVATE)

    override fun load(): Saved {
        val best = HashMap<String, Best>()
        runCatching {
            val o = JSONObject(prefs.getString(KEY_BEST, "{}") ?: "{}")
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                best[k] = Best(e.getInt("s"), e.getDouble("r"))
            }
        }
        return Saved(
            lang = prefs.getString(KEY_LANG, null)?.let { runCatching { Lang.valueOf(it) }.getOrNull() },
            muted = prefs.getBoolean(KEY_MUTED, false),
            best = best,
            roomL = prefs.getFloat(KEY_L, RoomBox.DEFAULT_L),
            roomW = prefs.getFloat(KEY_W, RoomBox.DEFAULT_W),
            roomH = prefs.getFloat(KEY_H, RoomBox.DEFAULT_H),
        )
    }

    override fun saveLang(lang: Lang) = prefs.edit().putString(KEY_LANG, lang.name).apply()

    override fun saveMuted(muted: Boolean) = prefs.edit().putBoolean(KEY_MUTED, muted).apply()

    override fun saveBest(best: Map<String, Best>) {
        val o = JSONObject()
        for ((k, v) in best) o.put(k, JSONObject().put("s", v.stars).put("r", v.ratio))
        prefs.edit().putString(KEY_BEST, o.toString()).apply()
    }

    override fun saveRoomSize(l: Float, w: Float, h: Float) = prefs.edit().putFloat(KEY_L, l).putFloat(KEY_W, w).putFloat(KEY_H, h).apply()

    private companion object {
        const val KEY_BEST = "best"
        const val KEY_LANG = "lang"
        const val KEY_MUTED = "muted"
        const val KEY_L = "room_l"
        const val KEY_W = "room_w"
        const val KEY_H = "room_h"
    }
}
