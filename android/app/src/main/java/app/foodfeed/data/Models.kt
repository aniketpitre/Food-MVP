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
data class MyActions(val recommended: Boolean = false, val saved: Boolean = false, val tried: Boolean = false)

@Serializable
data class UserRef(val handle: String, val displayName: String? = null)

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
) {
    fun toCard() = DishCard(
        id = id, name = name, priceInr = priceInr, isVeg = isVeg, categoryId = categoryId,
        createdAt = createdAt, restaurant = restaurant, cover = cover, stats = stats, me = me,
        recentRecommenders = recentRecommenders, distanceM = distanceM,
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
    val role: String = "user",
    val createdAt: String = "",
    val posts: List<ProfilePost> = emptyList(),
    val recommendations: Int = 0,
    val isMe: Boolean = false,
    val blockedByMe: Boolean = false,
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
data class EngagementResult(val stats: DishStats, val me: MyActions)

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
