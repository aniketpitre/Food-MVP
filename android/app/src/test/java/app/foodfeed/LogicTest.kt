package app.foodfeed

import app.foodfeed.data.AppJson
import app.foodfeed.data.DishCard
import app.foodfeed.data.DishStats
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.data.FeedPage
import app.foodfeed.data.MyActions
import app.foodfeed.data.normaliseServerUrl
import app.foodfeed.data.toggled
import app.foodfeed.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val base = EngagementState(DishStats(recommenders = 10, saves = 4, tried = 2), MyActions())

    @Test
    fun recommendAddsOnePersonAndUndoRemovesThem() {
        val on = base.toggled(Engagement.Recommend)
        assertTrue(on.me.recommended)
        assertEquals(11, on.stats.recommenders)
        assertEquals(10, on.toggled(Engagement.Recommend).stats.recommenders)
    }

    @Test
    fun triedAfterRecommendDoesNotCountThePersonTwice() {
        val both = base.toggled(Engagement.Recommend).toggled(Engagement.Tried)
        assertEquals(11, both.stats.recommenders)
        assertEquals(3, both.stats.tried)
        // removing only the recommend keeps them counted, because they still tried it
        assertEquals(11, both.toggled(Engagement.Recommend).stats.recommenders)
    }

    @Test
    fun saveDoesNotChangeTheRecommendedCount() {
        val saved = base.toggled(Engagement.Save)
        assertTrue(saved.me.saved)
        assertEquals(5, saved.stats.saves)
        assertEquals(10, saved.stats.recommenders)
    }

    @Test
    fun countsNeverGoNegative() {
        val weird = EngagementState(DishStats(), MyActions(recommended = true, saved = true))
        val off = weird.toggled(Engagement.Recommend).toggled(Engagement.Save)
        assertEquals(0, off.stats.recommenders)
        assertEquals(0, off.stats.saves)
    }

    @Test
    fun formatsDistancesPricesAndCounts() {
        assertNull(Format.distance(null))
        assertEquals("Nearby", Format.distance(40))
        assertEquals("350 m", Format.distance(347))
        assertEquals("1.2 km", Format.distance(1234))
        assertEquals("14 km", Format.distance(14_400))
        assertEquals("₹80", Format.price(80))
        assertEquals("New · be the first to recommend", Format.recommended(0))
        assertEquals("Recommended by 1 person", Format.recommended(1))
        assertEquals("Recommended by 38 people", Format.recommended(38))
        assertEquals("1.5k", Format.compact(1500))
        assertEquals("2k", Format.compact(2000))
        assertEquals("AP", Format.initials("aniket_p", "Aniket Pitre"))
        assertEquals("DA", Format.initials("demo_aarav1", null))
    }

    @Test
    fun normalisesServerAddresses() {
        assertEquals("http://192.168.1.20:8080", normaliseServerUrl(" 192.168.1.20:8080/ "))
        assertEquals("https://food.example.com", normaliseServerUrl("https://food.example.com//"))
    }

    @Test
    fun parsesTheServerFeedFormat() {
        // Shape copied from GET /feed; unknown fields must be ignored.
        val json = """
            {"items":[{"id":"7b36cb4d-d276-4c5b-9bdc-47365ccda6ad","name":"Kolhapuri Misal","priceInr":90,"isVeg":true,
             "categoryId":1,"createdAt":"2026-10-15T12:00:00.000Z",
             "restaurant":{"id":"a1","name":"Godavari Misal House","locality":"Panchavati","status":"active","lat":20.009,"lng":73.792},
             "cover":{"postId":"p1","imageUrl":"/media/posts/p1/main.webp","thumbUrl":"/media/posts/p1/thumb.webp",
                      "placeholderColor":"#c2410c","isSponsored":false,"width":900,"height":1080},
             "stats":{"recommenders":31,"saves":14,"tried":9,"endorsers":33},
             "me":{"recommended":false,"saved":true,"tried":false},
             "recentRecommenders":[{"handle":"demo_diya1","displayName":"Demo Diya"}],
             "distanceM":null,"lane":"top","somethingNew":42}],
             "nextOffset":20}
        """.trimIndent()
        val page = AppJson.decodeFromString(FeedPage.serializer(), json)
        val card: DishCard = page.items.single()
        assertEquals("Kolhapuri Misal", card.name)
        assertEquals(31, card.stats.recommenders)
        assertTrue(card.me.saved)
        assertFalse(card.me.recommended)
        assertNull(card.distanceM)
        assertEquals(20, page.nextOffset)
    }
}
