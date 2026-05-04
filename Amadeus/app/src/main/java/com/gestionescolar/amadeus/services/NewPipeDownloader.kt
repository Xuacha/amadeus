package com.gestionescolar.amadeus.services

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    
    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        val headers = request.headers()
        val method = request.httpMethod()
        val dataToSend = request.dataToSend()

        val body = if (dataToSend != null && (method == "POST" || method == "PUT")) {
            dataToSend.toRequestBody()
        } else null

        val okHttpRequest = okhttp3.Request.Builder()
            .url(url)
            .method(method, body)
            .apply {
                headers.forEach { (key, values) ->
                    values.forEach { addHeader(key, it) }
                }
            }
            .build()

        val response = client.newCall(okHttpRequest).execute()
        val responseBody = response.body?.string()
        
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            url
        )
    }
}
