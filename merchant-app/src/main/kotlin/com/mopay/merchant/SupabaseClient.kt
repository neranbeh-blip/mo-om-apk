package com.mopay.merchant

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object SupabaseClient {
    private const val BASE_URL = "https://pctpwfupzbsgbsszikmr.supabase.co"
    private const val PUBLISHABLE_KEY = "sb_publishable_RSfj1KiGdMA9aCG032RfZA_iK_7RptK"

    fun rpc(functionName: String, body: JSONObject = JSONObject()): JSONObject {
        val url = URL("$BASE_URL/rest/v1/rpc/$functionName")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Supabase HTTP $code: $response")
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
