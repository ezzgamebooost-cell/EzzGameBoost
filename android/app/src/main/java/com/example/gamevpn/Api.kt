package com.example.gamevpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Cfg(val id: Int, val name: String, val type: String, val content: String)
data class Me(val username: String, val active: Boolean, val daysLeft: Int, val configs: List<Cfg>)
data class Plan(val title: String, val price: String, val desc: String)
data class Plans(val telegramUrl: String, val plans: List<Plan>)

class ApiError(msg: String, val code: Int) : Exception(msg)

object Api {
    private fun call(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val c = URL(BuildConfig.SERVER_URL + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "{}"
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        if (code !in 200..299) throw ApiError(json.optString("error", "خطا ($code)"), code)
        return json
    }

    suspend fun login(user: String, pass: String): String = withContext(Dispatchers.IO) {
        call("POST", "/api/login", JSONObject().put("username", user).put("password", pass)).getString("token")
    }

    suspend fun me(token: String): Me = withContext(Dispatchers.IO) {
        val j = call("GET", "/api/me", token = token)
        val arr = j.getJSONArray("configs")
        Me(
            j.getString("username"), j.getBoolean("active"), j.getInt("days_left"),
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Cfg(o.getInt("id"), o.getString("name"), o.getString("type"), o.getString("content"))
            }
        )
    }

    suspend fun plans(): Plans = withContext(Dispatchers.IO) {
        val j = call("GET", "/api/plans")
        val a = j.getJSONArray("plans")
        Plans(j.getString("telegram_url"), (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Plan(o.optString("title"), o.optString("price"), o.optString("desc"))
        })
    }
}
