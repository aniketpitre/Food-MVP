package app.foodfeed.data

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
    val commentCount: Int = 0,
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
) {
    fun toCard() = DishCard(
        id = id, name = name, priceInr = priceInr, isVeg = isVeg, categoryId = categoryId,
        createdAt = createdAt, restaurant = restaurant, cover = cover, stats = stats, me = me,
        recentRecommenders = recentRecommenders, distanceM = distanceM, rating = rating, champion = champion,
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
    val createdAt: String = "",
)

@Serializable
data class AuthResult(val token: String, val user: User)

@Serializable
data class RegisterBody(
    val email: String,
    val password: String,
    val handle: String,
    val displayName: String? = null,
    val acceptTerms: Boolean,
)

@Serializable
data class LoginBody(val email: String, val password: String)

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
data class ViewsBody(val dishIds: List<String>, val deviceId: String? = null)

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
data class AdminQueue(val pendingPlaces: List<PendingPlace> = emptyList(), val reported: List<ReportedItem> = emptyList())

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
)

@Serializable
data class UpdatesPage(
    val now: String,
    val messages: List<UpdateMessage> = emptyList(),
    val reactions: List<UpdateReaction> = emptyList(),
    val activity: List<UpdateActivity> = emptyList(),
)

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
