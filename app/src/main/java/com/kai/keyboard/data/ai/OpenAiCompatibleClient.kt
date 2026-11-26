package com.kai.keyboard.data.ai

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Единственное место, откуда KAI выходит в сеть.
 *
 * Правила (их проверяет ревью и тесты, не меняйте без причины):
 *  - Запрос идёт только на `baseUrl + /chat/completions` (и `baseUrl + /models` — каталог моделей),
 *    где baseUrl взят из настроек пользователя.
 *    Других адресов в приложении нет: ни аналитики, ни проверки обновлений, ни crash-репортов.
 *  - Запрос создаётся только в ответ на действие пользователя (кнопка ИИ → пункт меню,
 *    «Проверить подключение» или «Каталог моделей» в настройках). Фоновых запросов и автоповторов нет:
 *    ошибка просто показывается.
 *  - Текст пользователя и API-ключ нигде не логируются: ни в Logcat, ни в сообщениях об ошибках.
 *    Логирующих перехватчиков OkHttp здесь нет и добавлять их нельзя.
 *  - Редиректы не выполняются (ключ не должен уйти на другой адрес), открытый http запрещён
 *    системой (cleartext выключен) — сервер должен быть доступен по https.
 *  - Формат — OpenAI Chat Completions; подходит любой совместимый сервер.
 */
class OpenAiCompatibleClient(private val http: OkHttpClient = defaultHttp()) {

    class Config(val baseUrl: String, val apiKey: String, val model: String, val temperature: Double)

    enum class Kind { BAD_URL, NO_NETWORK, TIMEOUT, TLS, CLEARTEXT, UNAUTHORIZED, RATE_LIMIT, SERVER, CLIENT, BAD_RESPONSE, CANCELLED, OTHER }

    /** Ошибка без текста пользователя и ключа. [detail] — короткое сообщение сервера (например «model not found»). */
    class Failure(val kind: Kind, val code: Int = 0, val detail: String? = null)

    sealed interface Result {
        class Ok(val text: String) : Result
        class Fail(val failure: Failure) : Result
    }

    fun interface Handle { fun cancel() }

    /**
     * Отправляет запрос в фоне. [onResult] вызывается один раз, из потока OkHttp (не из главного):
     * перенос в главный поток — забота вызывающего. Отменённый запрос результата не даёт.
     */
    fun chat(cfg: Config, prompt: String, maxTokens: Int? = null, onResult: (Result) -> Unit): Handle {
        val req = try { buildRequest(cfg, prompt, maxTokens) } catch (e: BadUrl) {
            onResult(Result.Fail(Failure(Kind.BAD_URL))); return Handle { }
        }
        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onResult(Result.Fail(mapIo(e)))
            }
            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) { response.close(); return }
                onResult(response.use { parse(it) })
            }
        })
        return Handle { call.cancel() }
    }

    fun chatBlocking(cfg: Config, prompt: String, maxTokens: Int? = null): Result {
        val req = try { buildRequest(cfg, prompt, maxTokens) } catch (e: BadUrl) {
            return Result.Fail(Failure(Kind.BAD_URL))
        }
        return try {
            http.newCall(req).execute().use { parse(it) }
        } catch (e: IOException) {
            Result.Fail(mapIo(e))
        }
    }

    private class BadUrl : Exception()

    private fun buildRequest(cfg: Config, prompt: String, maxTokens: Int?): Request {
        val url = endpoint(cfg.baseUrl)?.toHttpUrlOrNull() ?: throw BadUrl()
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("temperature", cfg.temperature)
            if (maxTokens != null) put("max_tokens", maxTokens)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }
        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer " + cfg.apiKey.trim()) }
            .build()
    }

    private fun parse(r: Response): Result {
        val raw = try {
            val src = r.body?.source() ?: return Result.Fail(Failure(Kind.BAD_RESPONSE, r.code))
            src.request(MAX_BODY + 1)
            if (src.buffer.size > MAX_BODY) return Result.Fail(Failure(Kind.BAD_RESPONSE, r.code))
            src.readUtf8()
        } catch (e: IOException) {
            return Result.Fail(mapIo(e))
        }
        if (r.isSuccessful) {
            val text = try {
                val msg = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                msg.optString("content", "")
            } catch (e: Exception) { return Result.Fail(Failure(Kind.BAD_RESPONSE, r.code)) }
            return if (text.isBlank()) Result.Fail(Failure(Kind.BAD_RESPONSE, r.code)) else Result.Ok(text.trim())
        }
        return Result.Fail(httpFailure(r.code, raw))
    }

    private fun httpFailure(code: Int, raw: String): Failure {
        val kind = when (code) {
            401, 403 -> Kind.UNAUTHORIZED
            429 -> Kind.RATE_LIMIT
            in 500..599 -> Kind.SERVER
            else -> Kind.CLIENT
        }
        return Failure(kind, code, if (kind == Kind.CLIENT) serverMessage(raw) else null)
    }

    sealed interface ModelsResult {
        class Ok(val ids: List<String>) : ModelsResult
        class Fail(val failure: Failure) : ModelsResult
    }

    /**
     * Каталог моделей: `GET baseUrl/models` (ключ тот же). Только по нажатию «Каталог моделей» в настройках.
     * [onResult] — один раз, из потока OkHttp; отменённый запрос результата не даёт.
     */
    fun models(baseUrl: String, apiKey: String, onResult: (ModelsResult) -> Unit): Handle {
        val req = try { buildModelsRequest(baseUrl, apiKey) } catch (e: BadUrl) {
            onResult(ModelsResult.Fail(Failure(Kind.BAD_URL))); return Handle { }
        }
        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onResult(ModelsResult.Fail(mapIo(e)))
            }
            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) { response.close(); return }
                onResult(response.use { parseModels(it) })
            }
        })
        return Handle { call.cancel() }
    }

    fun modelsBlocking(baseUrl: String, apiKey: String): ModelsResult {
        val req = try { buildModelsRequest(baseUrl, apiKey) } catch (e: BadUrl) {
            return ModelsResult.Fail(Failure(Kind.BAD_URL))
        }
        return try {
            http.newCall(req).execute().use { parseModels(it) }
        } catch (e: IOException) {
            ModelsResult.Fail(mapIo(e))
        }
    }

    private fun buildModelsRequest(baseUrl: String, apiKey: String): Request {
        val url = modelsEndpoint(baseUrl)?.toHttpUrlOrNull() ?: throw BadUrl()
        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer " + apiKey.trim()) }
            .build()
    }

    private fun parseModels(r: Response): ModelsResult {
        val raw = try {
            val src = r.body?.source() ?: return ModelsResult.Fail(Failure(Kind.BAD_RESPONSE, r.code))
            src.request(MAX_BODY + 1)
            if (src.buffer.size > MAX_BODY) return ModelsResult.Fail(Failure(Kind.BAD_RESPONSE, r.code))
            src.readUtf8()
        } catch (e: IOException) {
            return ModelsResult.Fail(mapIo(e))
        }
        if (!r.isSuccessful) return ModelsResult.Fail(httpFailure(r.code, raw))
        val ids = modelIds(raw) ?: return ModelsResult.Fail(Failure(Kind.BAD_RESPONSE, r.code))
        return ModelsResult.Ok(ids)
    }

    private fun serverMessage(raw: String): String? = try {
        val e = JSONObject(raw).opt("error")
        val m = if (e is JSONObject) e.optString("message") else e?.toString()
        m?.takeIf { it.isNotBlank() }?.take(160)
    } catch (_: Exception) { null }

    private fun mapIo(e: IOException): Failure = when (e) {
        is SocketTimeoutException -> Failure(Kind.TIMEOUT)
        is UnknownHostException -> Failure(Kind.NO_NETWORK)
        is UnknownServiceException -> Failure(if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true) Kind.CLEARTEXT else Kind.OTHER)
        is SSLException -> Failure(Kind.TLS)
        else -> when {
            e.message == "timeout" || e.message?.contains("timeout", ignoreCase = true) == true -> Failure(Kind.TIMEOUT)
            e.message == "Canceled" -> Failure(Kind.CANCELLED)
            else -> Failure(Kind.NO_NETWORK)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_BODY = 1L shl 20 // ответ больше мегабайта — не модель, а что-то не то

        fun endpoint(baseUrl: String): String? {
            var b = baseUrl.trim().trimEnd('/')
            if (b.isEmpty()) return null
            if (!b.startsWith("https://", true) && !b.startsWith("http://", true)) return null
            if (!b.endsWith("/chat/completions")) b += "/chat/completions"
            return b
        }

        fun modelsEndpoint(baseUrl: String): String? {
            var b = baseUrl.trim().trimEnd('/')
            if (b.isEmpty()) return null
            if (!b.startsWith("https://", true) && !b.startsWith("http://", true)) return null
            if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions").trimEnd('/')
            if (!b.endsWith("/models")) b += "/models"
            return b
        }

        fun modelIds(raw: String): List<String>? {
            val arr: JSONArray = try {
                val t = raw.trim()
                if (t.startsWith("[")) JSONArray(t) else {
                    val o = JSONObject(t)
                    o.optJSONArray("data") ?: o.optJSONArray("models") ?: return null
                }
            } catch (e: Exception) { return null }
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                val id = when (v) {
                    is JSONObject -> v.optString("id", "").ifBlank { v.optString("name", "") }
                    is String -> v
                    else -> ""
                }.trim()
                if (id.isNotEmpty()) out.add(id)
            }
            return out.sortedBy { it.lowercase() }
        }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
