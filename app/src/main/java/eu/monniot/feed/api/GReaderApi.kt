package eu.monniot.feed.api

import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Header
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Query

interface GReaderApi {
    // TODO Used leading slash everywhere, not sure if it's necessary or not.
    // https://freshrss.example.net/api/greader.php/accounts/ClientLogin



    // curl 'https://freshrss.example.net/api/greader.php/accounts/ClientLogin?Email=alice&Passwd=Abcdef123456'
    //SID=alice/8e6845e089457af25303abc6f53356eb60bdb5f8
    //Auth=alice/8e6845e089457af25303abc6f53356eb60bdb5f8
    @POST("/accounts/ClientLogin")
    suspend fun login(@Query("Email") email: String,
                      @Query("Passwd") password: String): String

    // Retrieve a token for requests making modifications
    // curl -H "Authorization:GoogleLogin auth=alice/8e6845e089457af25303abc6f53356eb60bdb5f8" \
    //   'https://freshrss.example.net/api/greader.php/reader/api/0/token'
    //    8e6845e089457af25303abc6f53356eb60bdb5f8ZZZZZZZZZZZZZZZZZ
    @GET("/reader/api/0/token")
    suspend fun getToken(@Header("Authorization") auth: String)



    // curl -s -H "Authorization:GoogleLogin auth=alice/8e6845e089457af25303abc6f53356eb60bdb5f8" \
    //   'https://freshrss.example.net/api/greader.php/reader/api/0/subscription/list?output=json'
    @GET("/reader/api/0/subscription/list?output=json")
    suspend fun listSubscriptions(@Header("Authorization") auth: String): String

    // Unsubscribe from a feed
    // curl -H "Authorization:GoogleLogin auth=alice/8e6845e089457af25303abc6f53356eb60bdb5f8" \
    //   -d 'ac=unsubscribe&s=feed/52' 'https://freshrss.example.net/api/greader.php/reader/api/0/subscription/edit'
    @GET("/reader/api/0/subscription/edit?output=json")
    suspend fun unsubscribeFeed(@Header("Authorization") auth: String,
                                @Query("s") feedId: String,
                                @Query("ac") action: String)

    // curl -s -H "Authorization:GoogleLogin auth=alice/8e6845e089457af25303abc6f53356eb60bdb5f8" \
    //   'https://freshrss.example.net/api/greader.php/reader/api/0/unread-count?output=json'
    @GET("/reader/api/0/unread-count?output=json")
    suspend fun unreadCount(@Header("Authorization") auth: String)

    // curl -s -H "Authorization:GoogleLogin auth=alice/8e6845e089457af25303abc6f53356eb60bdb5f8" \
    //   'https://freshrss.example.net/api/greader.php/reader/api/0/tag/list?output=json'
    @GET("/reader/api/0/tag/list?output=json")
    suspend fun listTags(@Header("Authorization") auth: String)

    // # Get articles
    // curl -s -H "Authorization:GoogleLogin auth=alice/8e6845e089457af25303abc6f53356eb60bdb5f8" \
    //   'https://freshrss.example.net/api/greader.php/reader/api/0/stream/contents/reading-list' | jq .
    @GET("/reader/api/0/stream/contents/reading-list?output=json")
    suspend fun readingList(@Header("Authorization") auth: String)


    /*
    @Resource("reader")
        @Resource("api")
            @Resource("0")

                @Resource("subscription")
                class Subscription(
                    val parent: Zero = Zero(),
                ) {
                    @Resource("list")
                    class List(
                        val parent: Subscription = Subscription(),
                        val output: String = "json",
                    )

                    @Resource("edit")
                    class Edit(
                        val parent: Subscription = Subscription(),
                    )

                    @Resource("quickadd")
                    class QuickAdd(
                        val parent: Subscription = Subscription(),
                        val output: String = "json",
                    )
                }

                @Resource("stream")
                class StreamRes(
                    val parent: Zero = Zero(),
                ) {

                    @Resource("contents")
                    class Contents(
                        val parent: StreamRes = StreamRes(),
                    ) {

                        @Resource("user/-/state/com.google/reading-list")
                        class ReadingList(
                            val parent: Contents = Contents(),
                            // Exclude target
                            val xt: List<String>? = null,
                            // Maximum number of items to return.
                            val n: Int,
                            // Epoch timestamp. Items older than this timestamp are filtered out.
                            val ot: Long? = null,
                            // continuation
                            val c: String? = null,
                            val output: String = "json",
                        )

                        @Resource("user/-/state/com.google/starred")
                        class Starred(
                            val parent: Contents = Contents(),
                            // Maximum number of items to return.
                            val n: Int,
                            // Epoch timestamp. Items older than this timestamp are filtered out.
                            val ot: Long? = null,
                            // continuation
                            val c: String? = null,
                            val output: String = "json",
                        )
                    }

                    @Resource("items")
                    class Items(
                        val parent: StreamRes = StreamRes(),
                    ) {
                        @Resource("ids")
                        class IDs(
                            val parent: Items = Items(),
                            // stream ID
                            val s: String,
                            /** Epoch timestamp. Items older than this timestamp are filtered out. */
                            val ot: Long? = null,
                            // continuation
                            val c: String? = null,
                            // count
                            val n: Int = 15_000,
                            // A stream ID to exclude.
                            val xt: String? = null,
                            val output: String = "json",
                        )

                        @Resource("contents")
                        class Contents(
                            val parent: Items = Items(),
                            val output: String = "json",
                        )
                    }
                }

                @Resource("token")
                class Token(val parent: Zero = Zero())

                @Resource("edit-tag")
                class EditTag(
                    val parent: Zero = Zero(),
                    val output: String = "json",
                )

                @Resource("rename-tag")
                class RenameTag(
                    val parent: Zero = Zero(),
                )

                @Resource("disable-tag")
                class DisableTag(
                    val parent: Zero = Zero(),
                )
     */
}