package com.gestionescolar.amadeus.repository

import com.gestionescolar.amadeus.models.YouTubeResult
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface YouTubeApiService {
    @GET("youtube/v3/search")
    suspend fun searchVideos(
        @Query("q") query: String,
        @Query("part") part: String = "snippet",
        @Query("type") type: String = "video",
        @Query("videoCategoryId") categoryId: String = "10",
        @Query("maxResults") maxResults: Int = 10,
        @Query("key") apiKey: String
    ): YouTubeSearchResponse
}

data class YouTubeSearchResponse(
    @SerializedName("items") val items: List<YouTubeItem>
)

data class YouTubeItem(
    @SerializedName("id") val id: YouTubeId,
    @SerializedName("snippet") val snippet: YouTubeSnippet
)

data class YouTubeId(
    @SerializedName("videoId") val videoId: String
)

data class YouTubeSnippet(
    @SerializedName("title") val title: String,
    @SerializedName("channelTitle") val channelTitle: String,
    @SerializedName("thumbnails") val thumbnails: YouTubeThumbnails
)

data class YouTubeThumbnails(
    @SerializedName("default") val default: YouTubeThumbnail
)

data class YouTubeThumbnail(
    @SerializedName("url") val url: String
)

class YouTubeSearchRepository {
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://www.googleapis.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(YouTubeApiService::class.java)

    suspend fun search(query: String): Result<List<YouTubeResult>> {
        return try {
            val videoId = extractVideoId(query)
            if (videoId != null) {
                // Si es una URL directa, devolvemos un único resultado sintético o podríamos buscarlo por ID
                return Result.success(listOf(YouTubeResult(
                    videoId = videoId,
                    title = "Video Directo",
                    thumbnail = "https://img.youtube.com/vi/$videoId/default.jpg",
                    channelTitle = "YouTube"
                )))
            }

            val response = service.searchVideos(query = query, apiKey = ApiKeys.YOUTUBE_API_KEY)
            Result.success(response.items.map {
                YouTubeResult(
                    videoId = it.id.videoId,
                    title = it.snippet.title,
                    thumbnail = it.snippet.thumbnails.default.url,
                    channelTitle = it.snippet.channelTitle
                )
            })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractVideoId(query: String): String? {
        val pattern = "(?<=watch\\?v=|/videos/|embed/|youtu.be/|/v/|/e/|watch\\?v%3D|watch\\?feature=player_embedded&v=|%2Fvideos%2F|embed%2F|youtu.be%2F|%2Fv%2F)[^#&?\\n]*"
        val compiledPattern = java.util.regex.Pattern.compile(pattern)
        val matcher = compiledPattern.matcher(query)
        return if (matcher.find()) {
            matcher.group()
        } else {
            null
        }
    }
}
