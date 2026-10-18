package app.worthabite.data

import kotlinx.serialization.Serializable

@Serializable
data class City(
    val id: Int,
    val slug: String,
    val name: String,
    val state: String? = null,
    val isActive: Boolean = true,
    val radiusKm: Int = 25,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val dishCount: Int = 0,
)

@Serializable
data class NearestCityCity(
    val id: Int,
    val slug: String,
    val name: String,
    val state: String? = null,
    val distanceKm: Int = 0,
    val radiusKm: Int = 25,
)

@Serializable
data class NearestCity(val city: NearestCityCity? = null, val inRange: Boolean = false)

@Serializable
data class Category(val id: Int, val slug: String, val name: String, val emoji: String? = null)

@Serializable
data class RestaurantRef(
    val id: String,
    val name: String,
    val locality: String? = null,
    val status: String = "active",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** One of the invented demo places, not a real business. */
    val isDemo: Boolean = false,
)

@Serializable
data class Cover(
    val postId: String,
    val imageUrl: String,
    val thumbUrl: String,
    val placeholderColor: String? = null,
    val isSponsored: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val commentCount: Int = 0,
    /** The photographer's phone was at the place when they posted it. */
    val verifiedVisit: Boolean = false,
)

@Serializable
data class DishStats(val recommenders: Int = 0, val saves: Int = 0, val tried: Int = 0, val endorsers: Int = 0)

@Serializable
data class MyActions(
    val recommended: Boolean = false,
    val saved: Boolean = false,
    val tried: Boolean = false,
    /** "Tried it, not for me." */
    val passed: Boolean = false,
)

/** People's Pick: loves vs "not for me" (see docs/PEOPLES_PICK.md). [score] ranks; people see [percent]. */
@Serializable
data class Rating(val loves: Int = 0, val passes: Int = 0, val percent: Int? = null, val score: Double = 0.0) {
    val votes: Int get() = loves + passes

    fun adjusted(loves: Int, passes: Int): Rating {
        val l = (this.loves + loves).coerceAtLeast(0)
        val p = (this.passes + passes).coerceAtLeast(0)
        return copy(loves = l, passes = p, percent = if (l + p > 0) Math.round(100f * l / (l + p)) else null)
    }
}

/** The most trusted person who loved a dish. */
@Serializable
data class Champion(
    val handle: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val isExpert: Boolean = false,
    val backers: Int = 0,
)

@Serializable
data class UserRef(val handle: String, val displayName: String? = null, val avatarUrl: String? = null)

@Serializable
data class DishCard(
    val id: String,
    val name: String,
    val priceInr: Int? = null,
    val isVeg: Boolean? = null,
    val categoryId: Int? = null,
    val createdAt: String = "",
    val restaurant: RestaurantRef,
    val cover: Cover? = null,
    val stats: DishStats = DishStats(),
    val me: MyActions = MyActions(),
    val recentRecommenders: List<UserRef> = emptyList(),
    val distanceM: Int? = null,
    val lane: String? = null,
    val rating: Rating = Rating(),
    val champion: Champion? = null,
    /** Why it's in For You: who loved it (and how close their taste is to yours), or a dish type. */
    val because: Because? = null,
    /** How many different people posted photos of this dish. */
    val photographers: Int = 0,
)

@Serializable
data class Because(
    val handle: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    /** Taste Match with that person, in percent. */
    val match: Int? = null,
    val followed: Boolean = false,
    val people: Int = 0,
    val category: String? = null,
)

@Serializable
data class FeedPage(val items: List<DishCard> = emptyList(), val nextOffset: Int? = null)

@Serializable
data class Post(
    val id: String,
    val caption: String? = null,
    val imageUrl: String,
    val thumbUrl: String,
    val placeholderColor: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val source: String = "user",
    val photoCredit: String? = null,
    val isSponsored: Boolean = false,
    val sponsorNote: String? = null,
    val instagramUrl: String? = null,
    val createdAt: String = "",
    val authorHandle: String? = null,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
    val commentCount: Int = 0,
    val verifiedVisit: Boolean = false,
)

@Serializable
data class DishDetail(
    val id: String,
    val name: String,
    val priceInr: Int? = null,
    val isVeg: Boolean? = null,
    val categoryId: Int? = null,
    val createdAt: String = "",
    val restaurant: RestaurantRef,
    val cover: Cover? = null,
    val stats: DishStats = DishStats(),
    val me: MyActions = MyActions(),
    val recentRecommenders: List<UserRef> = emptyList(),
    val distanceM: Int? = null,
    val description: String? = null,
    val posts: List<Post> = emptyList(),
    val recommenders: List<UserRef> = emptyList(),
    val moreAtPlace: List<DishCard> = emptyList(),
    val rating: Rating = Rating(),
    val champion: Champion? = null,
    val photographers: Int = 0,
) {
    fun toCard() = DishCard(
        id = id, name = name, priceInr = priceInr, isVeg = isVeg, categoryId = categoryId,
        createdAt = createdAt, restaurant = restaurant, cover = cover, stats = stats, me = me,
        recentRecommenders = recentRecommenders, distanceM = distanceM, rating = rating, champion = champion,
        photographers = photographers,
    )
}

@Serializable
data class RestaurantDetail(
    val id: String,
    val name: String,
    val locality: String? = null,
    val address: String? = null,
    val mapsUrl: String? = null,
    val instagramHandle: String? = null,
    val priceBand: Int? = null,
    val status: String = "active",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val distanceM: Int? = null,
    val cuisine: String? = null,
    val openingHours: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val photos: List<PlacePhoto> = emptyList(),
    /** Whether the server can show Google Maps photos of this place (fetched separately). */
    val googlePhotos: Boolean = false,
    /** One of the invented demo places, not a real business. */
    val isDemo: Boolean = false,
    val dishes: List<DishCard> = emptyList(),
)

/** A photo of a place from an openly licensed source, with the credit we must show. */
@Serializable
data class PlacePhoto(
    val imageUrl: String,
    val thumbUrl: String,
    val placeholderColor: String? = null,
    val attribution: String,
    val sourceUrl: String? = null,
    val source: String = "wikimedia",
)

@Serializable
data class GoogleAuthor(val name: String, val uri: String? = null)

@Serializable
data class GooglePhoto(val url: String, val authors: List<GoogleAuthor> = emptyList())

@Serializable
data class MapsLink(val googlePlaceId: String? = null)

@Serializable
data class GooglePhotos(val photos: List<GooglePhoto> = emptyList())

@Serializable
data class PlaceOption(val id: String, val name: String, val locality: String? = null, val distanceM: Int? = null)

@Serializable
data class SearchResult(val dishes: List<DishCard> = emptyList(), val restaurants: List<PlaceOption> = emptyList())

@Serializable
data class DishSuggestion(val name: String, val categoryId: Int? = null)

@Serializable
data class ProfilePost(
    val id: String,
    val dishId: String,
    val thumbUrl: String,
    val placeholderColor: String? = null,
    val status: String = "live",
    val dishName: String,
    val restaurantName: String,
    val restaurantStatus: String = "active",
)

@Serializable
data class UserProfile(
    val handle: String,
    val displayName: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val role: String = "user",
    val createdAt: String = "",
    val posts: List<ProfilePost> = emptyList(),
    val recommendations: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    val isFollowing: Boolean = false,
    val followsYou: Boolean = false,
    val isMe: Boolean = false,
    val blockedByMe: Boolean = false,
    val commentPolicy: String = "everyone",
    val tasteBadges: List<TasteBadge> = emptyList(),
    /** How alike your taste and theirs are; null for yourself, guests, or with nothing to go on yet. */
    val tasteMatch: TasteMatch? = null,
) {
    val postCount: Int get() = posts.count { it.status == "live" }
}

@Serializable
data class SharedType(val name: String, val emoji: String? = null)

@Serializable
data class TasteMatch(
    val percent: Int,
    val coVotes: Int = 0,
    val agreed: Int = 0,
    val shared: List<SharedType> = emptyList(),
)

/** What someone told us about their taste, and what their votes taught us. */
@Serializable
data class TasteProfile(
    val set: Boolean = false,
    val categories: List<Int> = emptyList(),
    val diet: String = "nonveg",
    val spice: Int = 3,
    val learned: List<LearnedTaste> = emptyList(),
)

@Serializable
data class LearnedTaste(val categoryId: Int, val name: String, val emoji: String? = null, val score: Double = 0.0)

@Serializable
data class TasteBody(val categories: List<Int>, val diet: String, val spice: Int)

/** Someone whose taste matches yours, suggested to follow. */
@Serializable
data class TasteMate(
    val handle: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val match: Int = 0,
    val coVotes: Int = 0,
    val isFollowing: Boolean = false,
    val favourite: String? = null,
)

@Serializable
data class RouteLine(
    /** [lng, lat] pairs. */
    val coordinates: List<List<Double>> = emptyList(),
    val distanceM: Int? = null,
    val durationS: Int? = null,
    val source: String = "straight",
)

/** One turn of the in-app directions: what to do there, and how far it is to the next one. */
@Serializable
data class NavStep(val instruction: String, val distanceM: Int = 0, val lng: Double, val lat: Double)

@Serializable
data class Directions(
    /** [lng, lat] pairs along the road. */
    val coordinates: List<List<Double>> = emptyList(),
    val distanceM: Int? = null,
    val durationS: Int? = null,
    val source: String = "straight",
    val steps: List<NavStep> = emptyList(),
)

@Serializable
data class RouteStop(val card: DishCard, val along: Double = 0.0, val offRouteM: Int = 0)

@Serializable
data class RouteStops(val route: RouteLine = RouteLine(), val stops: List<RouteStop> = emptyList())

@Serializable
data class User(
    val id: String,
    val email: String,
    val handle: String,
    val displayName: String? = null,
    val role: String = "user",
    val homeCityId: Int? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val commentPolicy: String = "everyone",
    val dmPolicy: String = "everyone",
    /** Two-step sign-in: a code by email at every sign-in. */
    val twoFactor: Boolean = false,
    val emailVerified: Boolean = false,
    val createdAt: String = "",
)

@Serializable
data class AuthResult(
    val token: String,
    val user: User,
    /** After signing up: the code we emailed to confirm the address. */
    val verify: CodeChallenge? = null,
)

@Serializable
data class EmailChangeStart(val newEmail: String, val password: String)

@Serializable
data class EmailChangeCodes(val oldChallengeId: String, val oldHint: String = "", val newChallengeId: String, val newHint: String = "")

@Serializable
data class EmailChangeConfirm(val oldChallengeId: String, val oldCode: String, val newChallengeId: String, val newCode: String)

@Serializable
data class EmailChangeResult(val user: User, val token: String? = null)

@Serializable
data class PasswordChangeResult(val ok: Boolean = true, val token: String? = null)

/** A device you're signed in on. */
@Serializable
data class SessionItem(
    val id: String,
    val device: String? = null,
    val createdAt: String = "",
    val lastSeenAt: String = "",
    val current: Boolean = false,
)

@Serializable
data class SessionList(val items: List<SessionItem> = emptyList())

@Serializable
data class ForgotPasswordBody(val login: String)

@Serializable
data class ForgotResult(val challengeId: String, val message: String = "")

@Serializable
data class ResetPasswordBody(val challengeId: String, val code: String, val password: String)

@Serializable
data class EmailBody(val email: String)

@Serializable
data class HandleBody(val handle: String)

@Serializable
data class EmailHintResult(val emailHint: String)

@Serializable
data class HandleAvailable(val available: Boolean, val reason: String? = null)

@Serializable
data class MessageResult(val ok: Boolean = true, val message: String = "")

/** Signing in: a session straight away, or (two-step sign-in on) a code to enter first. */
@Serializable
data class LoginResult(
    val token: String? = null,
    val user: User? = null,
    val twoFactor: Boolean = false,
    val challengeId: String? = null,
    val emailHint: String? = null,
)

@Serializable
data class CodeChallenge(val challengeId: String, val emailHint: String = "")

@Serializable
data class CodeBody(val challengeId: String, val code: String)

@Serializable
data class ChallengeBody(val challengeId: String)

@Serializable
data class PasswordBody(val password: String)

@Serializable
data class RegisterBody(
    val email: String,
    val password: String,
    val handle: String,
    val displayName: String? = null,
    val acceptTerms: Boolean,
)

@Serializable
/**
 * [login] is an email or a username. [email] carries the same text for servers older than
 * 0.16, which only read "email".
 */
data class LoginBody(val login: String, val password: String, val email: String)

@Serializable
data class EngagementResult(val stats: DishStats, val me: MyActions, val rating: Rating? = null)

@Serializable
data class ShareResult(val url: String, val counted: Boolean = false)

@Serializable
data class OkResult(val ok: Boolean = true, val hidden: Boolean = false, val status: String? = null)

@Serializable
data class ReportBody(
    val targetType: String,
    val targetId: String? = null,
    val targetHandle: String? = null,
    val reason: String,
    val note: String? = null,
)

@Serializable
data class BlockBody(val handle: String)

@Serializable
data class ViewsBody(
    val dishIds: List<String>,
    val deviceId: String? = null,
    /** Which feed the cards were in. */
    val tab: String? = null,
    /** These are cards someone tapped open (not just saw). */
    val opened: Boolean = false,
)

@Serializable
data class CreatedPost(
    val id: String,
    val dishId: String,
    val dishName: String,
    val imageUrl: String,
    val thumbUrl: String,
    val status: String,
    val isSponsored: Boolean = false,
    val restaurantId: String,
    val restaurantName: String,
    val restaurantStatus: String,
    /** "verified" (phone at the place, photo taken there and then), "nearby", or null. */
    val visit: String? = null,
    /** It looks like a copy of someone else's photo and waits for a person to check it. */
    val held: Boolean = false,
)

/** A photo in the Following feed: who posted it, and the dish it's of. */
@Serializable
data class FollowingPost(
    val id: String,
    val caption: String? = null,
    val imageUrl: String,
    val thumbUrl: String,
    val placeholderColor: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: String = "",
    val verifiedVisit: Boolean = false,
    val author: UserRef,
    val dish: DishCard,
)

@Serializable
data class FollowingCursor(val before: String, val beforeId: String)

@Serializable
data class FollowingPage(val items: List<FollowingPost> = emptyList(), val next: FollowingCursor? = null)

@Serializable
data class SimilarDish(val id: String, val name: String, val categoryId: Int? = null, val photos: Int = 0)

@Serializable
data class SimilarPlace(val id: String, val name: String, val locality: String? = null, val status: String = "active", val distanceM: Int = 0)

@Serializable
data class RemovedFollower(val ok: Boolean = true, val followers: Int = 0)

@Serializable
data class HeldPhoto(
    val id: String,
    val thumbUrl: String,
    val dishId: String,
    val dishName: String,
    val authorHandle: String? = null,
    val originalThumbUrl: String? = null,
    val originalAuthorHandle: String? = null,
)

@Serializable
data class SimilarDishPair(
    val aId: String, val aName: String, val bId: String, val bName: String, val placeName: String,
    val aPosts: Int = 0, val bPosts: Int = 0,
)

@Serializable
data class MergeBody(val into: String)

@Serializable
data class HealthTotals(
    val liveDishes: Int = 0,
    val dishesWith3Votes: Int = 0,
    val ownPlaceVotesIgnored: Int = 0,
    val lockstepAccounts: Int = 0,
    val copiesHeld: Int = 0,
    val copiesRemoved: Int = 0,
    val userPhotos: Int = 0,
    val verifiedPhotos: Int = 0,
    val dishesMerged: Int = 0,
)

@Serializable
data class DayVotes(val day: String, val loves: Int = 0, val passes: Int = 0, val voters: Int = 0)

@Serializable
data class TopChange(val city: String, val day: String, val comparedWith: String, val size: Int = 0, val newInTop: Int = 0)

@Serializable
data class TabTaps(val tab: String, val seen: Int = 0, val opened: Int = 0)

@Serializable
data class PredictionCheck(
    val days: Int = 30,
    val votes: Int = 0,
    val accuracy: Double? = null,
    val alwaysLoveAccuracy: Double? = null,
    val skill: Double? = null,
)

@Serializable
data class AlgorithmHealth(
    val totals: HealthTotals = HealthTotals(),
    val votesPerDay: List<DayVotes> = emptyList(),
    val topChange: List<TopChange> = emptyList(),
    val taps: List<TabTaps> = emptyList(),
    val prediction: PredictionCheck = PredictionCheck(),
)

@Serializable
data class PendingPlace(
    val id: String,
    val name: String,
    val locality: String? = null,
    val createdAt: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val createdBy: String? = null,
    val dishes: Int = 0,
)

@Serializable
data class ReportedItem(
    val targetType: String,
    val targetId: String,
    val reports: Int,
    val reasons: List<String> = emptyList(),
    val lastReportAt: String = "",
    val status: String? = null,
    val label: String? = null,
    val thumbUrl: String? = null,
    val dishId: String? = null,
)

@Serializable
data class AdminQueue(
    val pendingPlaces: List<PendingPlace> = emptyList(),
    val reported: List<ReportedItem> = emptyList(),
    val heldPhotos: List<HeldPhoto> = emptyList(),
)

@Serializable
data class ApiError(val error: String = "error", val message: String = "Something went wrong.")

@Serializable
data class Comment(
    val id: String,
    val parentId: String? = null,
    val body: String,
    val createdAt: String = "",
    val author: UserRef,
    val likes: Int = 0,
    val likedByMe: Boolean = false,
    val canDelete: Boolean = false,
    val replies: List<Comment> = emptyList(),
)

@Serializable
data class CommentList(val count: Int = 0, val comments: List<Comment> = emptyList())

@Serializable
data class NewComment(val body: String, val parentId: String? = null)

@Serializable
data class CommentLike(val likes: Int = 0, val likedByMe: Boolean = false)

@Serializable
data class TasteBadge(
    val category: String,
    val emoji: String? = null,
    val picks: Int = 0,
    val backers: Int = 0,
    val isExpert: Boolean = false,
)

/** A person in a list (followers, search, blocked). */
@Serializable
data class Person(
    val handle: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val isFollowing: Boolean = false,
    val isMe: Boolean = false,
    val followers: Int = 0,
    val followsYou: Boolean = false,
)

@Serializable
data class FollowState(val isFollowing: Boolean = false, val followers: Int = 0)

@Serializable
data class ProfileUpdate(
    val displayName: String? = null,
    val username: String? = null,
    val bio: String? = null,
    val commentPolicy: String? = null,
    val dmPolicy: String? = null,
)

@Serializable
data class PasswordChange(val current: String, val next: String)

@Serializable
data class NotificationDish(val id: String, val name: String, val thumbUrl: String? = null)

@Serializable
data class ActivityItem(
    val id: Long,
    val kind: String,
    val createdAt: String = "",
    val read: Boolean = false,
    val actor: UserRef,
    val dish: NotificationDish? = null,
    val postId: String? = null,
    val commentBody: String? = null,
    val followingActor: Boolean = false,
)

@Serializable
data class ActivityPage(val items: List<ActivityItem> = emptyList())

@Serializable
data class UnreadCount(val count: Int = 0)

/** Someone whose picks for a craving the community backed. */
@Serializable
data class Taster(
    val handle: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val picks: Int = 0,
    val backers: Int = 0,
    val topPick: String? = null,
    val isExpert: Boolean = false,
)

@Serializable
data class CravingResult(
    val query: String = "",
    val label: String = "",
    val results: List<DishCard> = emptyList(),
    val tasters: List<Taster> = emptyList(),
)

@Serializable
data class CravingChip(val label: String, val emoji: String? = null, val dishes: Int = 0)

@Serializable
data class FollowedPick(val card: DishCard, val lovedBy: String)

@Serializable
data class HomeData(
    val cravings: List<CravingChip> = emptyList(),
    val forYou: List<DishCard> = emptyList(),
    val peoplesPicks: List<DishCard> = emptyList(),
    val nearby: List<DishCard> = emptyList(),
    val following: List<FollowedPick> = emptyList(),
    val trending: List<DishCard> = emptyList(),
    val fresh: List<DishCard> = emptyList(),
    val tasters: List<Taster> = emptyList(),
)

@Serializable
data class MapTopDish(
    val dishId: String,
    val name: String,
    val loves: Int = 0,
    val percent: Int? = null,
    val thumbUrl: String? = null,
    val priceInr: Int? = null,
    val isVeg: Boolean? = null,
    val emoji: String? = null,
)

@Serializable
data class MapPlace(
    val id: String,
    val name: String,
    val locality: String? = null,
    val cuisine: String? = null,
    val address: String? = null,
    val openingHours: String? = null,
    val lat: Double,
    val lng: Double,
    val dishCount: Int = 0,
    val lovers: Int = 0,
    val photoUrl: String? = null,
    /** One of the invented demo places (not a real business). */
    val demo: Boolean = false,
    /** The food logo drawn on the pin: the best dish's category, else a guess from the cuisine. */
    val emoji: String = "🍽️",
    val topDish: MapTopDish? = null,
    /** With a people filter on: who loved [topDish]. */
    val lovedBy: LovedBy? = null,
)

@Serializable
data class LovedBy(val handle: String, val displayName: String? = null, val avatarUrl: String? = null, val match: Int? = null)

/** A place in Explore › Places: a map place plus how far it is and its best few dishes. */
@Serializable
data class PlaceItem(
    val id: String,
    val name: String,
    val locality: String? = null,
    val cuisine: String? = null,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val dishCount: Int = 0,
    val lovers: Int = 0,
    val distanceM: Int? = null,
    val pureVeg: Boolean = false,
    val photoUrl: String? = null,
    val demo: Boolean = false,
    val emoji: String = "🍽️",
    val topDish: MapTopDish? = null,
    val dishNames: List<String> = emptyList(),
)

@Serializable
data class PlacePage(val items: List<PlaceItem> = emptyList(), val nextOffset: Int? = null)

@Serializable
data class Facet(val name: String, val places: Int = 0, val withDishes: Int = 0)

@Serializable
data class CategoryFacet(val id: Int, val name: String, val emoji: String? = null, val dishes: Int = 0, val places: Int = 0)

@Serializable
data class PlaceFacets(
    val areas: List<Facet> = emptyList(),
    val cuisines: List<Facet> = emptyList(),
    val categories: List<CategoryFacet> = emptyList(),
)

@Serializable
data class MapPlaces(val places: List<MapPlace> = emptyList())

// ---------- Direct messages ----------

@Serializable
data class LastMessage(val kind: String = "text", val body: String? = null, val mine: Boolean = false, val createdAt: String = "")

@Serializable
data class ChatSummary(
    val id: String,
    val folder: String = "inbox",
    val with: UserRef,
    val lastMessage: LastMessage = LastMessage(),
    val unread: Boolean = false,
)

@Serializable
data class Inbox(val items: List<ChatSummary> = emptyList(), val requests: Int = 0)

@Serializable
data class InboxUnread(val chats: Int = 0, val requests: Int = 0)

@Serializable
data class MessageDish(val id: String, val name: String, val restaurantName: String? = null, val priceInr: Int? = null, val thumbUrl: String? = null)

@Serializable
data class MessageStory(val id: String? = null, val thumbUrl: String? = null, val expired: Boolean = true)

@Serializable
data class MessagePlace(
    val id: String,
    val name: String,
    val locality: String? = null,
    val cuisine: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val thumbUrl: String? = null,
)

@Serializable
data class MessagePost(
    val id: String,
    val dishId: String? = null,
    val thumbUrl: String? = null,
    val caption: String? = null,
    val dishName: String? = null,
    val author: String? = null,
)

@Serializable
data class MessagePhoto(val imageUrl: String, val thumbUrl: String? = null)

@Serializable
data class MessageReplyRef(val id: String, val kind: String = "text", val body: String? = null, val mine: Boolean = false)

@Serializable
data class Reaction(val emoji: String, val mine: Boolean = false)

@Serializable
data class Message(
    val id: String,
    val kind: String = "text",
    val body: String? = null,
    val createdAt: String,
    val mine: Boolean = false,
    val dish: MessageDish? = null,
    val place: MessagePlace? = null,
    val post: MessagePost? = null,
    val photo: MessagePhoto? = null,
    val story: MessageStory? = null,
    val replyTo: MessageReplyRef? = null,
    val reactions: List<Reaction> = emptyList(),
)

@Serializable
data class Conversation(
    val id: String,
    val folder: String = "inbox",
    val with: UserRef = UserRef(""),
    val canMessage: Boolean = true,
    val theirLastReadAt: String? = null,
    val messages: List<Message> = emptyList(),
    /** Fingerprint of the chat; polling with it returns just [unchanged] when nothing moved. */
    val state: String? = null,
    val unchanged: Boolean = false,
)

@Serializable
data class ConversationId(val id: String)

@Serializable
data class NewMessage(
    val body: String? = null,
    val dishId: String? = null,
    val restaurantId: String? = null,
    val postId: String? = null,
    val replyTo: String? = null,
)

@Serializable
data class ShareMessage(
    val handles: List<String>,
    val body: String? = null,
    val dishId: String? = null,
    val restaurantId: String? = null,
    val postId: String? = null,
)

@Serializable
data class ReactionBody(val emoji: String)

// ---------- Phone notifications (GET /updates) ----------

@Serializable
data class UpdateMessage(
    val id: String,
    val conversationId: String,
    val kind: String = "text",
    val body: String? = null,
    val createdAt: String = "",
    val folder: String = "inbox",
    val from: UserRef,
)

@Serializable
data class UpdateReaction(
    val messageId: String,
    val conversationId: String,
    val emoji: String,
    val createdAt: String = "",
    val body: String? = null,
    val kind: String = "text",
    val from: UserRef,
)

@Serializable
data class UpdateActivity(
    val id: Long,
    val kind: String,
    val createdAt: String = "",
    val actor: UserRef,
    val dishName: String? = null,
    val dishId: String? = null,
    val commentBody: String? = null,
    val storyId: String? = null,
    val placeName: String? = null,
)

/** New food near you: a dish just posted nearby, or a place new on WorthAbite. */
@Serializable
data class UpdateNearby(
    val kind: String = "dish",
    val placeId: String,
    val placeName: String,
    val locality: String? = null,
    val dishId: String,
    val dishName: String,
    val emoji: String? = null,
    val isVeg: Boolean? = null,
    val distanceM: Int? = null,
    val thumbUrl: String? = null,
)

@Serializable
data class UpdatesPage(
    val now: String,
    val messages: List<UpdateMessage> = emptyList(),
    val reactions: List<UpdateReaction> = emptyList(),
    val activity: List<UpdateActivity> = emptyList(),
    val nearby: List<UpdateNearby> = emptyList(),
    /** Once a day while two-step sign-in is off: a reminder to turn it on. */
    val reminder: UpdateReminder? = null,
    /** People whose photo, name or username changed since the last check. */
    val people: List<PersonChange> = emptyList(),
)

/** Someone's new look, and the usernames they had before (to update screens still showing those). */
@Serializable
data class PersonChange(
    val handle: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val formerHandles: List<String> = emptyList(),
)

@Serializable
data class UpdateReminder(val kind: String)

@Serializable
data class PushConfig(
    val enabled: Boolean = false,
    val apiKey: String? = null,
    val appId: String? = null,
    val projectId: String? = null,
    val senderId: String? = null,
)

@Serializable
data class PushTokenBody(val token: String, val cityId: Int? = null)

@Serializable
data class ShareResult2(val sent: List<String> = emptyList(), val failed: List<String> = emptyList())

@Serializable
data class OpenChat(val handle: String)

// ---------- Stories ----------

@Serializable
data class StoryDish(val id: String, val name: String, val restaurantName: String? = null)

@Serializable
data class StoryPlace(val id: String, val name: String)

@Serializable
data class Story(
    val id: String,
    val imageUrl: String,
    val thumbUrl: String,
    val placeholderColor: String? = null,
    val caption: String? = null,
    val createdAt: String = "",
    val expiresAt: String = "",
    val seen: Boolean = false,
    val liked: Boolean = false,
    val viewCount: Int? = null,
    val dish: StoryDish? = null,
    val place: StoryPlace? = null,
)

@Serializable
data class StoryReel(val user: UserRef, val isMe: Boolean = false, val allSeen: Boolean = false, val stories: List<Story> = emptyList())

@Serializable
data class StoryTray(val items: List<StoryReel> = emptyList())

@Serializable
data class StoryViewer(val handle: String, val displayName: String? = null, val avatarUrl: String? = null, val liked: Boolean = false)

@Serializable
data class StoryViewers(val items: List<StoryViewer> = emptyList())

@Serializable
data class LikeBody(val liked: Boolean)

@Serializable
data class LikeState(val liked: Boolean)

@Serializable
data class ReplyBody(val body: String)

@Serializable
data class StoryCreated(val id: String)

@Serializable
data class ReplySent(val conversationId: String)
