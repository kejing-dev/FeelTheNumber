package io.embodit.feelnumber.domain

import io.embodit.feelnumber.domain.model.Body
import io.embodit.feelnumber.domain.model.Clip
import io.embodit.feelnumber.domain.model.Geometry
import io.embodit.feelnumber.domain.model.Placement
import io.embodit.feelnumber.domain.model.QuestionBank
import io.embodit.feelnumber.domain.model.RoomBox
import io.embodit.feelnumber.domain.model.Scoring
import io.embodit.feelnumber.domain.model.Shape
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.domain.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The design's `qaHooks.unitTests` list. */
class FeelDomainTest {
    private val head = Vec3(0f, 1.6f, 0f)

    @Test
    fun bankSizesFollowFromVolumes() {
        assertEquals(20, QuestionBank.all.size)
        for (set in QuestionBank.sets) assertEquals(5, QuestionBank.inSet(set.id).size)
        // design values (1 mm), except co2-phone which the build re-checked against the current EPA factor
        val expected =
            mapOf(
                "co2-1kg" to 1.007f, "co2-petrol" to 1.331f, "co2-flight" to 4.513f, "co2-1t" to 10.071f,
                "kg-gold" to 0.0373f, "kg-dryice" to 0.086f, "kg-water" to 0.1f, "kg-air" to 1.16f, "kg-hydrogen" to 2.82f,
                "body-breath" to 0.098f, "body-blood" to 0.212f, "body-water-year" to 0.9f, "body-water-ton" to 1.0f, "body-air-day" to 2.759f,
                "big-lifetime-water" to 3.847f, "big-whale" to 6.592f, "big-pool" to 13.572f, "big-air-year" to 19.719f, "big-co2-capita" to 20.87f,
                "co2-phone" to 0.233f,
            )
        for ((id, size) in expected) assertEquals(id, size, QuestionBank.get(id).sizeM, 0.0015f)
        // every set runs from small to large
        for (set in QuestionBank.sets) {
            val v = QuestionBank.inSet(set.id).map { it.volumeM3 }
            assertEquals(set.id, v.sorted(), v)
        }
    }

    @Test
    fun starsBoundariesGoToTheBetterBand() {
        assertEquals(1, Scoring.stars(0.25))
        assertEquals(2, Scoring.stars(0.5))
        assertEquals(3, Scoring.stars(0.8))
        assertEquals(3, Scoring.stars(1.0))
        assertEquals(3, Scoring.stars(1.25))
        assertEquals(2, Scoring.stars(2.0))
        assertEquals(1, Scoring.stars(4.0))
        assertEquals(0, Scoring.stars(0.2499))
        assertEquals(0, Scoring.stars(4.01))
    }

    @Test
    fun verdictCopyAndRounding() {
        assertEquals(Verdict.TimesBigger("522"), Scoring.verdict(521.6))
        assertEquals(Verdict.FractionOf("3.7"), Scoring.verdict(1 / 3.72))
        assertEquals(Verdict.TimesBigger("2.0"), Scoring.verdict(2.0))
        assertEquals(Verdict.PercentBigger(50), Scoring.verdict(1.5))
        assertEquals(Verdict.PercentSmaller(30), Scoring.verdict(0.7))
        assertEquals(Verdict.FractionOf("2.0"), Scoring.verdict(0.5))
        assertTrue(Scoring.showsLesson(2.0))
        assertTrue(Scoring.showsLesson(0.5))
        assertFalse(Scoring.showsLesson(1.9))
    }

    @Test
    fun qaExpectations() {
        val kg = QuestionBank.get("co2-1kg")
        val r1 = Scoring.ratio(Geometry.volumeOf(Shape.SPHERE, 0.65), kg.volumeM3)
        assertEquals(0.269, r1, 0.001)
        assertEquals(1, Scoring.stars(r1))
        assertEquals(Verdict.FractionOf("3.7"), Scoring.verdict(r1))
        val gold = QuestionBank.get("kg-gold")
        val r2 = Scoring.ratio(Geometry.volumeOf(Shape.CUBE, 0.3), gold.volumeM3)
        assertEquals(Verdict.TimesBigger("522"), Scoring.verdict(r2))
    }

    @Test
    fun clearancePushScalesWithRadius() {
        val user = Vec3(0f, 0f, -1.8f)
        for (r in listOf(0.1f, 1f, 5f)) {
            val spot = Placement.actualSpot(user, head, Shape.SPHERE, 2 * r, null)
            assertEquals(maxOf(1.8f, r + 0.8f), -spot.z, 1e-4f)
            assertEquals(0f, spot.y, 1e-6f)
        }
    }

    @Test
    fun clearancePushStopsAtTheWallAndShrinksBack() {
        // the viewer 1 m from the back wall of a 5 m room: the front wall is 4 m ahead
        val room = RoomBox.around(head, Vec3.AHEAD, floorY = 0f)
        val user = Vec3(0f, 0f, -1.8f)
        val big = Placement.actualSpot(user, head, Shape.SPHERE, 10f, room)
        assertEquals(4f, -big.z, 1e-3f)
        val small = Placement.actualSpot(user, head, Shape.SPHERE, 0.5f, room)
        assertEquals(1.8f, -small.z, 1e-4f)
        // the head moving does not move the user's spot: the rule only reads it when called
        assertEquals(user, Vec3(0f, 0f, -1.8f))
    }

    @Test
    fun revealPushKeepsNearSurface08m() {
        val spot = Placement.revealSpot(Vec3(0f, 0f, -1.8f), head, Shape.SPHERE, 4.513f)
        assertEquals(4.513f / 2f + 0.8f, -spot.z, 1e-3f)
        val keep = Placement.revealSpot(Vec3(0f, 0f, -3f), head, Shape.SPHERE, 1f)
        assertEquals(3f, -keep.z, 1e-4f)
    }

    @Test
    fun wrapAndBack() {
        val before = Vec3(0f, 0f, -6f)
        val wrapped = Placement.wrapSpot(head, 0f)
        val b = Body(Shape.SPHERE, Placement.center(wrapped, 10f), 10f)
        assertTrue(Clip.headInside(b, head, wasInside = false))
        val back = Body(Shape.SPHERE, Placement.center(before, 10f), 10f)
        assertEquals(-6f, back.center.z, 1e-6f)
    }

    @Test
    fun virtualRoomFollowsHeadYaw() {
        val facing = Vec3(1f, 0f, 0f) // looking along +x
        val r = RoomBox.around(head, facing)
        assertEquals(90f, r.yawDeg, 1e-3f)
        assertEquals(head.y - 1.6f, r.floorY, 1e-6f)
        // the viewer stands 1.0 m from the back wall, centred across
        val l = r.toLocal(Vec3(head.x, r.floorY, head.z))
        assertEquals(-(r.length / 2f - 1f), l.z, 1e-4f)
        assertEquals(0f, l.x, 1e-4f)
    }

    @Test
    fun visibleShareOfACubeMatchesTheAnalyticValue() {
        val room = RoomBox(Vec3.ZERO, 0f, width = 4f, length = 4f, height = 2f, floorY = 0f)
        // a 2 m cube centred on the right wall: half of it outside
        val b = Body(Shape.CUBE, Vec3(2f, 1f, 0f), 2f)
        assertEquals(0.5f, Clip.visibleFraction(room, b), 0.02f)
        // a 3 m cube on the floor under a 2 m ceiling: 2/3 inside
        val c = Body(Shape.CUBE, Vec3(0f, 1.5f, 0f), 3f)
        assertEquals(2f / 3f, Clip.visibleFraction(room, c), 0.02f)
        assertTrue(Clip.isCut(room, c))
        assertFalse(Clip.isCut(room, Body(Shape.CUBE, Vec3(0f, 0.5f, 0f), 1f)))
        // QA round 1: a ball snapped against the front wall only touches it (float error included) -> not cut
        assertFalse(Clip.isCut(room, Body(Shape.SPHERE, Vec3(0f, 0.5f, -1.5f - 0.0004f), 1f)))
        assertEquals(1f, Clip.visibleFraction(room, Body(Shape.SPHERE, Vec3(0f, 0.5f, -1.5f), 1f)), 1e-6f)
    }

    @Test
    fun insideChecksWithHysteresis() {
        val room = RoomBox(Vec3.ZERO, 0f, width = 4f, length = 4f, height = 2.5f, floorY = 0f)
        // ball centre beyond the wall, on the ceiling, head inside
        assertTrue(Clip.isCut(room, Body(Shape.SPHERE, Vec3(2.5f, 1f, 0f), 2f)))
        assertTrue(Clip.isCut(room, Body(Shape.SPHERE, Vec3(0f, 2.5f, 0f), 2f)))
        val b = Body(Shape.SPHERE, Vec3(0f, 1f, 0f), 2f)
        assertTrue(Clip.headInside(b, Vec3(0f, 1.8f, 0f), wasInside = false))
        // within the 0.2 m band: entering needs 0.1 m inside, leaving 0.1 m outside
        assertFalse(Clip.headInside(b, Vec3(0f, 1.95f, 0f), wasInside = false))
        assertTrue(Clip.headInside(b, Vec3(0f, 2.05f, 0f), wasInside = true))
        assertFalse(Clip.headInside(b, Vec3(0f, 2.15f, 0f), wasInside = true))
    }

    @Test
    fun clampedMeshStaysInTheRoomAndRingsSitOnTheWall() {
        val room = RoomBox(Vec3.ZERO, 0f, width = 4f, length = 4f, height = 2.5f, floorY = 0f)
        val b = Body(Shape.SPHERE, Vec3(1.5f, 1f, 0f), 2f)
        val mesh = Clip.sphere(b, room)
        assertTrue(mesh.positions.all { room.contains(it, 1e-4f) })
        val rings = Clip.rings(room, b)
        assertTrue(rings.isNotEmpty())
        // the right wall at x = 2, rings lifted 2 mm into the room
        assertTrue(rings.all { kotlin.math.abs(it.a.x - 1.998f) < 1e-3f })
        assertEquals(64 * 32 * 2, mesh.triangles)
    }

    @Test
    fun sliderAndHandleScales() {
        assertEquals(0f, Placement.sliderOf(0.02f), 1e-5f)
        assertEquals(1f, Placement.sliderOf(30f), 1e-5f)
        assertEquals(0.8f, Placement.sizeOfSlider(Placement.sliderOf(0.8f)), 1e-4f)
        assertEquals(0.25f * 1.08f, Placement.handleScale(0.25f, 0.01f), 1e-5f)
        // 0.3 -> 10 m in about 45 cm
        assertEquals(10f, Placement.handleScale(0.3f, 0.4556f), 0.05f)
        assertEquals(1, Placement.stickAxis(0.8f, 0.2f))
        assertEquals(2, Placement.stickAxis(0.2f, -0.8f))
        assertEquals(0, Placement.stickAxis(0.6f, 0.5f))
        assertEquals(0, Placement.stickAxis(0.1f, 0.1f))
    }

    @Test
    fun wallSnapAndRelease() {
        val room = RoomBox(Vec3.ZERO, 0f, width = 4f, length = 4f, height = 2.5f, floorY = 0f)
        val snap = Placement.snap(room, Vec3(1.4f, 0f, 0f), Shape.CUBE, 1f, 20f, Placement.SNAP_IN_M)
        assertEquals(1.5f, snap!!.first.x, 1e-4f)
        assertEquals(0f, snap.second, 1e-4f)
        assertNull(Placement.snap(room, Vec3(1.2f, 0f, 0f), Shape.SPHERE, 1f, 0f, Placement.SNAP_IN_M))
        // once snapped it holds to 0.25 m
        assertTrue(Placement.snap(room, Vec3(1.3f, 0f, 0f), Shape.SPHERE, 1f, 0f, Placement.SNAP_OUT_M) != null)
    }

    /** v0.1.1 (taste D1 / D20): the default anchor stands 2.5 m out, 55 degrees right of the card, out of its view cone. */
    @Test
    fun defaultAnchorKeepsClearOfTheCard() {
        // card 30 degrees left, 20 down, 1.0 m (the designed pose)
        val card = head + Vec3(-0.4698f, -0.342f, -0.8138f)
        val a = Placement.defaultAnchor(head, card, 0f)
        assertEquals(0f, a.y, 1e-6f)
        assertEquals(2.5f, Vec3(a.x - head.x, 0f, a.z - head.z).length, 1e-3f)
        val toCard = Vec3(card.x - head.x, 0f, card.z - head.z).normalized()
        val toAnchor = Vec3(a.x - head.x, 0f, a.z - head.z).normalized()
        assertEquals(55.0, Math.toDegrees(kotlin.math.acos(toCard.dot(toAnchor).toDouble())), 0.1)
        assertTrue(toAnchor.x > 0f) // right of straight ahead
        // a 1 m ball and a 0.25 m ball on the tray above it do not block the card
        val halfDiag = 0.315f
        assertFalse(Placement.blocksPanel(head, card, halfDiag, Placement.center(a, 1f), 0.5f))
        assertFalse(Placement.blocksPanel(head, card, halfDiag, Placement.center(Placement.trayAt(a), 0.25f), 0.125f))
    }

    @Test
    fun viewConeToThePanel() {
        val card = head + Vec3(0f, -0.342f, -0.94f)
        val halfDiag = 0.315f
        // halfway to the card on the line of sight: blocks
        assertTrue(Placement.blocksPanel(head, card, halfDiag, head + Vec3(0f, -0.17f, -0.5f), 0.1f))
        // the same direction but 2 m beyond the card: the card is drawn in front of it
        assertFalse(Placement.blocksPanel(head, card, halfDiag, head + Vec3(0f, -1f, -2.75f), 0.2f))
        // 40 degrees off to the side at the card's distance: clear
        assertFalse(Placement.blocksPanel(head, card, halfDiag, head + Vec3(0.64f, -0.34f, -0.77f), 0.1f))
        // head inside the body
        assertTrue(Placement.blocksPanel(head, card, halfDiag, head, 1f))
    }

    @Test
    fun trayStandsOnTheAnchor() {
        val spot = Vec3(1f, 0.4f, -2f)
        assertEquals(Vec3(1f, 1.4f, -2f), Placement.trayAt(spot))
    }
}
