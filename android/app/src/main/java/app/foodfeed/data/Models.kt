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
    val dishes: List<DishCard> = emptyList(),
)

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
) {
    val postCount: Int get() = posts.count { it.status == "live" }
}

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
    val peoplesPicks: List<DishCard> = emptyList(),
    val nearby: List<DishCard> = emptyList(),
    val following: List<FollowedPick> = emptyList(),
    val trending: List<DishCard> = emptyList(),
    val fresh: List<DishCard> = emptyList(),
    val tasters: List<Taster> = emptyList(),
)

@Serializable
data class MapTopDish(val dishId: String, val name: String, val loves: Int = 0, val percent: Int? = null, val thumbUrl: String? = null)

@Serializable
data class MapPlace(
    val id: String,
    val name: String,
    val locality: String? = null,
    val cuisine: String? = null,
    val lat: Double,
    val lng: Double,
    val dishCount: Int = 0,
    val topDish: MapTopDish? = null,
)

@Serializable
data class MapPlaces(val places: List<MapPlace> = emptyList())
