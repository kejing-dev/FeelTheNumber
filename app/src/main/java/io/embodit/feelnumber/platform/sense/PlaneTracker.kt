package io.embodit.feelnumber.platform.sense

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.feelnumber.domain.model.PlaneSurface
import io.embodit.feelnumber.domain.model.SurfaceKind
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.platform.FeelLog

/**
 * Plane detection (from the Spatial Ruler / Fire Plan). Surfaces arrive progressively (on the emulator
 * the first after about 20 s) and keep being updated; nothing waits for them. The room box is fitted
 * from them by `RoomFit` (FLOOR height and outline, WALL directions, CEILING height).
 *
 * Vertices are converted to Stage metres here; normals come from the vertices. Main thread only.
 */
class PlaneTracker {
    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, PlaneSurface>()
    private var subscription: Cancellable? = null

    /** Bumped whenever a plane is added, changed or removed. */
    var revision: Int = 0
        private set

    var startedAtMs: Long = 0L
        private set

    /** Debug `noscan on`: detected planes are ignored (the nothing-detected path, taste D7). */
    var ignoreDetected: Boolean = false
        set(value) {
            if (field != value) revision++
            field = value
        }

    val detected: Collection<PlaneSurface>
        get() = if (ignoreDetected) emptyList() else surfaces.values

    val count: Int
        get() = surfaces.size

    fun start() {
        if (subscription != null) return
        startedAtMs = SystemClock.elapsedRealtime()
        // subscribe first, then start: no event is lost
        subscription =
            PlaneTrackingManager.subscribeAnchorUpdate { update ->
                if (Looper.myLooper() == Looper.getMainLooper()) handle(update) else main.post { handle(update) }
            }
        runCatching { PlaneTrackingManager.start() }
            .onFailure { FeelLog.e("PLANES start failed: $it", it) }
        FeelLog.i("PLANES tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        surfaces.clear()
        revision++
    }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val surface = runCatching { anchor.toSurface(id) }.getOrNull() ?: return
                if (surface.vertices.size < 3 || surface.indices.size < 3) return
                val previous = surfaces[id]
                if (previous != null && sameGeometry(previous, surface)) return
                surfaces[id] = surface
                revision++
            }
            AnchorUpdate.Event.REMOVED -> if (surfaces.remove(id) != null) revision++
            else -> Unit
        }
    }

    private fun PlaneAnchor.toSurface(id: String): PlaneSurface {
        val q = transform.quaternion
        val p = transform.position
        val stage =
            vertices.map { v ->
                val w = q.rotateVector(v) + p
                Vec3(w.x, w.y, w.z)
            }
        val name = semantics.name
        return PlaneSurface(id, SurfaceKind.fromSemanticName(name), name, stage, indices.toList())
    }

    /** The emulator re-reports unchanged planes all the time; those updates are dropped. */
    private fun sameGeometry(a: PlaneSurface, b: PlaneSurface): Boolean {
        if (a.vertices.size != b.vertices.size || a.semanticName != b.semanticName) return false
        for (i in a.vertices.indices) {
            if (a.vertices[i].distanceTo(b.vertices[i]) > GEOMETRY_EPS_M) return false
        }
        return true
    }

    private companion object {
        const val GEOMETRY_EPS_M = 0.005f
    }
}
