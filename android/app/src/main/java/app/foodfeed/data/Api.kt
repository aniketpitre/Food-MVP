package app.foodfeed.data

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** The FoodFeed self-hosted API (see backend/src/routes). */
interface FoodFeedApi {
    @GET("health") suspend fun health(): OkResult

    @GET("cities") suspend fun cities(): List<City>

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

    @POST("auth/login") suspend fun login(@Body body: LoginBody): AuthResult

    @GET("me") suspend fun me(): User

    @DELETE("me") suspend fun deleteMe(): OkResult

    @Multipart
    @POST("posts")
    suspend fun createPost(@Part parts: List<MultipartBody.Part>): CreatedPost

    @GET("admin/queue") suspend fun adminQueue(): AdminQueue

    @POST("admin/restaurants/{id}/{action}")
    suspend fun adminPlace(@Path("id") id: String, @Path("action") action: String): OkResult

    @POST("admin/{type}/{id}/{action}")
    suspend fun adminContent(@Path("type") type: String, @Path("id") id: String, @Path("action") action: String): OkResult

    @POST("admin/users/{id}/dismiss")
    suspend fun adminDismissUser(@Path("id") id: String): OkResult
}
