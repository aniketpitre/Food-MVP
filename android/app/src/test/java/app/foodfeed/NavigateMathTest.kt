package app.foodfeed

import app.foodfeed.data.Coords
import app.foodfeed.data.Directions
import app.foodfeed.data.NavStep
import app.foodfeed.ui.map.NavTarget
import app.foodfeed.ui.map.RouteProgress
import app.foodfeed.ui.map.bearing
import app.foodfeed.ui.map.geoMetres
import app.foodfeed.ui.map.progressOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The maths behind in-app Take me there: where you are on the road and what comes next. */
class NavigateMathTest {
    // An L-shaped drive: 1.1 km east, then 1.1 km north.
    private val line = listOf(listOf(73.78, 20.0), listOf(73.7905, 20.0), listOf(73.7905, 20.01))
    private val target = NavTarget("p1", 20.01, 73.7905, "Sadhana")
    private val route = Directions(
        coordinates = line, distanceM = 2200, durationS = 440, source = "osrm",
        steps = listOf(
            NavStep("Head out onto Gangapur Road", 1100, 73.78, 20.0),
            NavStep("Turn left onto College Road", 1100, 73.7905, 20.0),
            NavStep("You have arrived", 0, 73.7905, 20.01),
        ),
    )

    @Test
    fun distancesAndBearings() {
        assertEquals(1112.0, geoMetres(20.0, 73.78, 20.01, 73.78), 5.0)
        assertEquals(0.0, bearing(20.0, 73.78, 20.01, 73.78), 0.5)
        assertEquals(90.0, bearing(20.0, 73.78, 20.0, 73.79), 0.5)
    }

    @Test
    fun locatesYouAlongAndOffTheRoad() {
        val p = RouteProgress(line)
        assertEquals(2210.0, p.length, 30.0)
        val (along, off) = p.locate(20.0003, 73.785) // ~33 m north of the first leg, halfway along it
        assertEquals(523.0, along, 20.0)
        assertEquals(33.0, off, 5.0)
    }

    @Test
    fun nextTurnTimeLeftAndArrival() {
        val start = progressOf(route, Coords(20.0, 73.781), target)
        assertEquals("Turn left onto College Road", start.nextInstruction)
        assertEquals(990.0, start.toNextM!!, 25.0)
        assertTrue(start.remainingS!! in 380.0..440.0)
        // Round the corner: the next thing is the place itself.
        val after = progressOf(route, Coords(20.002, 73.7905), target)
        assertEquals("You have arrived", after.nextInstruction)
        assertTrue(after.remainingM in 850.0..950.0)
        // Off the road by ~550 m: time to route again.
        assertTrue(progressOf(route, Coords(20.005, 73.785), target).offRouteM > 70)
        // Within 40 m of the place.
        val there = progressOf(route, Coords(20.0098, 73.7905), target)
        assertTrue(there.arrived)
        assertNull(there.toNextM)
    }

    @Test
    fun withoutRoadDirectionsItPointsStraightThere() {
        val p = progressOf(Directions(coordinates = line.take(2), source = "straight"), Coords(20.0, 73.78), target)
        assertEquals("Head towards Sadhana", p.nextInstruction)
        assertEquals(geoMetres(20.0, 73.78, 20.01, 73.7905), p.remainingM, 0.1)
    }
}
