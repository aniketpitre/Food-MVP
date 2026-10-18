package app.worthabite

import app.worthabite.data.AppJson
import app.worthabite.data.DishCard
import app.worthabite.data.DishStats
import app.worthabite.data.Engagement
import app.worthabite.data.EngagementState
import app.worthabite.data.FeedPage
import app.worthabite.data.MyActions
import app.worthabite.data.normaliseServerUrl
import app.worthabite.data.toggled
import app.worthabite.ui.Format
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

    @Test
    fun timeAgoReadsLikeInstagram() {
        val now = java.time.Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
        assertEquals("now", Format.timeAgo("2026-10-16T11:59:30Z", now))
        assertEquals("5m", Format.timeAgo("2026-10-16T11:55:00Z", now))
        assertEquals("3h", Format.timeAgo("2026-10-16T09:00:00Z", now))
        assertEquals("2d", Format.timeAgo("2026-10-14T12:00:00Z", now))
        assertEquals("3w", Format.timeAgo("2026-09-25T12:00:00Z", now))
        assertEquals("", Format.timeAgo("not a date", now))
    }

    @Test
    fun parsesCommentsWithReplies() {
        val json = """{"count":2,"comments":[{"id":"a","parentId":null,"body":"Hi","createdAt":"2026-10-16T06:00:00.000Z",
            "author":{"handle":"x","displayName":null},"likes":1,"likedByMe":true,"canDelete":false,
            "replies":[{"id":"b","parentId":"a","body":"@x yo","createdAt":"2026-10-16T06:01:00.000Z",
            "author":{"handle":"y","displayName":"Y"},"likes":0,"likedByMe":false,"canDelete":true,"replies":[]}]}]}"""
        val list = AppJson.decodeFromString(app.worthabite.data.CommentList.serializer(), json)
        assertEquals(2, list.count)
        assertEquals("b", list.comments[0].replies[0].id)
        assertTrue(list.comments[0].likedByMe)
    }

    @Test
    fun comparesVersionsForUpdates() {
        val u = app.worthabite.data.Updates
        assertTrue(u.isNewer("v0.5.0", "0.4.0"))
        assertTrue(u.isNewer("v0.10.0", "0.9.3"))
        assertTrue(u.isNewer("1.0.0", "0.99.99"))
        assertFalse(u.isNewer("v0.4.0", "0.4.0"))
        assertFalse(u.isNewer("v0.3.9", "0.4.0"))
        assertFalse(u.isNewer("v0.4.0", "0.4.0-debug"))
    }

    @Test
    fun readsWhatsNewFromReleaseNotes() {
        val body = "### What's new\n- Comments on posts\n- Location everywhere in India\n\n**WorthAbite pilot build 0.5.0**\n1. Download"
        assertEquals(listOf("Comments on posts", "Location everywhere in India"), app.worthabite.data.Updates.whatsNew(body))
        assertEquals(emptyList<String>(), app.worthabite.data.Updates.whatsNew("**WorthAbite pilot build**"))
    }

    @Test
    fun notForMeExcludesALoveAndMarksTried() {
        val loved = base.toggled(Engagement.Recommend)
        assertEquals(1, loved.rating.loves)
        val passed = loved.toggled(Engagement.Pass)
        assertFalse(passed.me.recommended)
        assertTrue(passed.me.passed)
        assertTrue(passed.me.tried)
        // Someone who tried it and said "not for me" is not one of the people who recommend it.
        assertEquals(base.stats.recommenders, passed.stats.recommenders)
        assertEquals(0, passed.rating.loves)
        assertEquals(1, passed.rating.passes)
        assertEquals(0, passed.rating.percent)
        val lovedAgain = passed.toggled(Engagement.Recommend)
        assertTrue(lovedAgain.me.recommended)
        assertFalse(lovedAgain.me.passed)
        assertEquals(100, lovedAgain.rating.percent)
    }
}
