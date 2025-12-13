package eu.monniot.feed

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

// Data classes for Feedly API responses
// These are simplified for the purpose of the facade
data class FeedlyStreamResponse(
    val id: String,
    val title: String,
    val items: List<FeedlyEntry>
)

data class FeedlyEntry(
    val id: String,
    val title: String,
    val summary: FeedlyContent?,
    val published: Long,
    val origin: FeedlyOrigin,
    val alternate: List<FeedlyLink>?
)

data class FeedlyLink(
    val href: String
)

data class FeedlyContent(
    val content: String
)

data class FeedlyOrigin(
    val title: String
)

// Retrofit interface
interface FeedlyApi {
    @GET("v3/streams/contents")
    suspend fun getStreamContents(
        @Query("streamId") streamId: String,
        @Query("count") count: Int = 20,
        @Header("Authorization") authToken: String
    ): FeedlyStreamResponse
}

class FeedlyFacade(private val authToken: String) {

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://cloud.feedly.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(FeedlyApi::class.java)

    /**
     * Fetches the content of a specific stream (category, feed, or tag).
     * 
     * @param streamId The ID of the stream to fetch. 
     *                 Example: "user/1234/category/global.all"
     * @return A list of RssItem objects adapted for the UI.
     */
    suspend fun getStreamItems(streamId: String): List<RssItem> {
        return try {
            val response = api.getStreamContents(
                streamId = streamId,
                authToken = "OAuth $authToken"
            )
            
            response.items.map { entry ->
                RssItem(
                    id = entry.id,
                    title = entry.title,
                    description = entry.summary?.content ?: "",
                    // Helper to format date if needed, or keeping raw for now.
                    // The UI currently expects a string that it tries to parse.
                    // Ideally we should handle Date objects directly, but adhering to current RssItem string contract:
                    pubDate = java.text.SimpleDateFormat("EEE, d MMM yyyy", java.util.Locale.ENGLISH)
                        .format(java.util.Date(entry.published)),
                    source = entry.origin.title,
                    url = entry.alternate?.firstOrNull()?.href ?: ""
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
