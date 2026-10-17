package app.foodfeed.data

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** The WorthAbite self-hosted API (see backend/src/routes). */
interface WorthAbiteApi {
    @GET("health") suspend fun health(): OkResult

    @GET("cities") suspend fun cities(@Query("q") q: String? = null, @Query("limit") limit: Int? = null): List<City>

    @GET("cities/nearest")
    suspend fun nearestCity(@Query("lat") lat: Double, @Query("lng") lng: Double): NearestCity

    @GET("categories") suspend fun categories(): List<Category>

    @GET("feed")
    suspend fun feed(
        @Query("city") city: Int,
        @Query("category") category: Int?,
        @Query("tab") tab: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("seed") seed: String,
        @Query("lat") lat: Double?,
        @Query("lng") lng: Double?,
    ): FeedPage

    @GET("dishes/{id}")
    suspend fun dish(@Path("id") id: String, @Query("lat") lat: Double?, @Query("lng") lng: Double?): DishDetail

    @GET("restaurants/{id}")
    suspend fun restaurant(@Path("id") id: String, @Query("lat") lat: Double?, @Query("lng") lng: Double?): RestaurantDetail

    @GET("search")
    suspend fun search(
        @Query("q") q: String,
        @Query("city") city: Int,
        @Query("lat") lat: Double?,
        @Query("lng") lng: Double?,
    ): SearchResult

    @GET("suggest/dishes")
    suspend fun suggestDishes(@Query("q") q: String, @Query("city") city: Int?): List<DishSuggestion>

    @GET("restaurants/nearby")
    suspend fun nearbyRestaurants(@Query("lat") lat: Double, @Query("lng") lng: Double, @Query("q") q: String?): List<PlaceOption>

    @GET("restaurants")
    suspend fun restaurants(@Query("city") city: Int, @Query("q") q: String): List<PlaceOption>

    @GET("users/{handle}") suspend fun user(@Path("handle") handle: String): UserProfile

    @PUT("dishes/{id}/engagements/{kind}")
    suspend fun engage(@Path("id") id: String, @Path("kind") kind: String): EngagementResult

    @DELETE("dishes/{id}/engagements/{kind}")
    suspend fun disengage(@Path("id") id: String, @Path("kind") kind: String): EngagementResult

    @POST("dishes/{id}/share") suspend fun share(@Path("id") id: String): ShareResult

    @GET("me/saved") suspend fun saved(@Query("lat") lat: Double?, @Query("lng") lng: Double?): FeedPage

    @POST("reports") suspend fun report(@Body body: ReportBody): OkResult

    @POST("blocks") suspend fun block(@Body body: BlockBody): OkResult

    @DELETE("blocks/{handle}") suspend fun unblock(@Path("handle") handle: String): OkResult

    @POST("views") suspend fun views(@Body body: ViewsBody): OkResult

    @POST("auth/register") suspend fun register(@Body body: RegisterBody): AuthResult

    @POST("auth/login") suspend fun login(@Body body: LoginBody): LoginResult
    @POST("auth/login/verify") suspend fun verifyLogin(@Body body: CodeBody): AuthResult
    @POST("auth/login/resend") suspend fun resendLogin(@Body body: ChallengeBody): CodeChallenge
    @POST("me/2fa/start") suspend fun startTwoFactor(): CodeChallenge
    @POST("me/2fa/confirm") suspend fun confirmTwoFactor(@Body body: CodeBody): User
    @POST("me/2fa/disable") suspend fun disableTwoFactor(@Body body: PasswordBody): User

    @GET("me") suspend fun me(): User

    @DELETE("me") suspend fun deleteMe(): OkResult

    @Multipart
    @POST("posts")
    suspend fun createPost(@Part parts: List<MultipartBody.Part>): CreatedPost

    @GET("posts/{id}/comments") suspend fun comments(@Path("id") postId: String): CommentList

    @POST("posts/{id}/comments")
    suspend fun addComment(@Path("id") postId: String, @Body body: NewComment): Comment

    @DELETE("comments/{id}") suspend fun deleteComment(@Path("id") id: String): OkResult

    @PUT("comments/{id}/like") suspend fun likeComment(@Path("id") id: String): CommentLike

    @DELETE("comments/{id}/like") suspend fun unlikeComment(@Path("id") id: String): CommentLike

    // ---- Social ----
    @GET("users/{handle}/loved") suspend fun userLoved(@Path("handle") handle: String): FeedPage

    @PATCH("me/profile") suspend fun updateProfile(@Body body: ProfileUpdate): User

    @Multipart
    @POST("me/avatar")
    suspend fun uploadAvatar(@Part image: MultipartBody.Part): User

    @DELETE("me/avatar") suspend fun deleteAvatar(): User

    @POST("me/password") suspend fun changePassword(@Body body: PasswordChange): OkResult

    @GET("me/blocked") suspend fun blocked(): List<Person>

    @PUT("users/{handle}/follow") suspend fun follow(@Path("handle") handle: String): FollowState

    @DELETE("users/{handle}/follow") suspend fun unfollow(@Path("handle") handle: String): FollowState

    @GET("users/{handle}/followers") suspend fun followers(@Path("handle") handle: String): List<Person>

    @GET("users/{handle}/following") suspend fun following(@Path("handle") handle: String): List<Person>

    @GET("people") suspend fun people(@Query("q") q: String): List<Person>

    @GET("notifications") suspend fun notifications(@Query("before") before: Long? = null): ActivityPage

    @GET("notifications/unread") suspend fun unread(): UnreadCount

    @POST("notifications/read") suspend fun markRead(): OkResult

    // ---- Discover ----
    @GET("craving")
    suspend fun craving(@Query("q") q: String, @Query("city") city: Int, @Query("lat") lat: Double?, @Query("lng") lng: Double?): CravingResult

    @GET("home")
    suspend fun home(@Query("city") city: Int, @Query("lat") lat: Double?, @Query("lng") lng: Double?): HomeData

    @GET("map") suspend fun map(
        @Query("city") city: Int,
        @Query("all") all: Boolean = false,
        @Query("filter") filter: String = "all",
    ): MapPlaces

    // ---------- Taste ----------
    @GET("me/taste") suspend fun taste(): TasteProfile
    @PUT("me/taste") suspend fun setTaste(@Body body: TasteBody): OkResult
    @GET("people/taste") suspend fun tasteMates(@Query("limit") limit: Int = 12): List<TasteMate>
    @GET("route/stops")
    suspend fun routeStops(
        @Query("fromLat") fromLat: Double,
        @Query("fromLng") fromLng: Double,
        @Query("toLat") toLat: Double,
        @Query("toLng") toLng: Double,
        @Query("corridorKm") corridorKm: Double = 3.0,
    ): RouteStops

    /** Directions for the in-app Take me there: the road line and its turns. */
    @GET("route")
    suspend fun directions(
        @Query("fromLat") fromLat: Double,
        @Query("fromLng") fromLng: Double,
        @Query("toLat") toLat: Double,
        @Query("toLng") toLng: Double,
    ): Directions

    // ---------- Explore by places ----------
    @GET("places")
    suspend fun places(
        @Query("city") city: Int,
        @Query("lat") lat: Double?,
        @Query("lng") lng: Double?,
        @Query("sort") sort: String,
        @Query("area") area: String?,
        @Query("cuisine") cuisine: String?,
        @Query("category") category: Int?,
        @Query("veg") veg: Boolean,
        @Query("maxKm") maxKm: Double?,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 20,
    ): PlacePage

    @GET("places/facets") suspend fun placeFacets(@Query("city") city: Int): PlaceFacets

    @GET("restaurants/{id}/google-photos") suspend fun googlePhotos(@Path("id") id: String): GooglePhotos

    @GET("restaurants/{id}/maps") suspend fun mapsLink(@Path("id") id: String): MapsLink

    // ---- Messages ----
    @GET("inbox") suspend fun inbox(@Query("folder") folder: String = "inbox"): Inbox

    @GET("inbox/unread") suspend fun inboxUnread(): InboxUnread

    @POST("conversations") suspend fun openChat(@Body body: OpenChat): ConversationId

    @GET("conversations/{id}")
    suspend fun conversation(
        @Path("id") id: String,
        @Query("after") after: String? = null,
        @Query("before") before: String? = null,
        @Query("state") state: String? = null,
    ): Conversation

    @POST("conversations/{id}/messages") suspend fun sendMessage(@Path("id") id: String, @Body body: NewMessage): Message

    @POST("messages") suspend fun shareMessage(@Body body: ShareMessage): ShareResult2

    @Multipart
    @POST("conversations/{id}/photo")
    suspend fun sendPhoto(@Path("id") id: String, @Part parts: List<MultipartBody.Part>): Message

    @PUT("messages/{id}/reaction") suspend fun react(@Path("id") id: String, @Body body: ReactionBody): OkResult

    @DELETE("messages/{id}/reaction") suspend fun unreact(@Path("id") id: String): OkResult

    @GET("me/friends") suspend fun friends(@Query("q") q: String? = null): List<Person>

    @GET("updates") suspend fun updates(
        @Query("since") since: String?,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
        @Query("city") city: Int? = null,
    ): UpdatesPage

    @GET("push/config") suspend fun pushConfig(): PushConfig
    @PUT("me/push-token") suspend fun savePushToken(@Body body: PushTokenBody): OkResult
    @HTTP(method = "DELETE", path = "me/push-token", hasBody = true) suspend fun deletePushToken(@Body body: PushTokenBody): OkResult

    @POST("conversations/{id}/read") suspend fun readChat(@Path("id") id: String): OkResult

    @POST("conversations/{id}/accept") suspend fun acceptChat(@Path("id") id: String): OkResult

    @DELETE("conversations/{id}") suspend fun deleteChat(@Path("id") id: String): retrofit2.Response<Unit>

    @DELETE("messages/{id}") suspend fun unsend(@Path("id") id: String): retrofit2.Response<Unit>

    // ---- Stories ----
    @GET("stories") suspend fun stories(@Query("city") city: Int?): StoryTray

    @GET("users/{handle}/stories") suspend fun userStories(@Path("handle") handle: String): StoryReel

    @Multipart
    @POST("stories")
    suspend fun createStory(@Part parts: List<MultipartBody.Part>): StoryCreated

    @POST("stories/{id}/view") suspend fun viewStory(@Path("id") id: String): OkResult

    @PUT("stories/{id}/like") suspend fun likeStory(@Path("id") id: String, @Body body: LikeBody): LikeState

    @POST("stories/{id}/reply") suspend fun replyStory(@Path("id") id: String, @Body body: ReplyBody): ReplySent

    @GET("stories/{id}/viewers") suspend fun storyViewers(@Path("id") id: String): StoryViewers

    @DELETE("stories/{id}") suspend fun deleteStory(@Path("id") id: String): retrofit2.Response<Unit>

    @GET("admin/queue") suspend fun adminQueue(): AdminQueue

    @POST("admin/restaurants/{id}/{action}")
    suspend fun adminPlace(@Path("id") id: String, @Path("action") action: String): OkResult

    @POST("admin/{type}/{id}/{action}")
    suspend fun adminContent(@Path("type") type: String, @Path("id") id: String, @Path("action") action: String): OkResult

    @POST("admin/talk/{type}/{id}/{action}")
    suspend fun adminTalk(@Path("type") type: String, @Path("id") id: String, @Path("action") action: String): OkResult

    @POST("admin/users/{id}/dismiss")
    suspend fun adminDismissUser(@Path("id") id: String): OkResult
}
