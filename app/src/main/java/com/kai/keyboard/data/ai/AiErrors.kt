package com.kai.keyboard.data.ai

import android.content.Context
import com.kai.keyboard.R
import com.kai.keyboard.data.ai.OpenAiCompatibleClient.Failure
import com.kai.keyboard.data.ai.OpenAiCompatibleClient.Kind

/** Человекочитаемый текст ошибки запроса (для тоста и экрана настроек). Текст запроса и ключ сюда не попадают. */
object AiErrors {
    fun message(ctx: Context, f: Failure): String = when (f.kind) {
        Kind.BAD_URL -> ctx.getString(R.string.ai_err_bad_url)
        Kind.NO_NETWORK -> ctx.getString(R.string.ai_err_no_network)
        Kind.TIMEOUT -> ctx.getString(R.string.ai_err_timeout)
        Kind.TLS -> ctx.getString(R.string.ai_err_tls)
        Kind.CLEARTEXT -> ctx.getString(R.string.ai_err_cleartext)
        Kind.UNAUTHORIZED -> ctx.getString(R.string.ai_err_401)
        Kind.RATE_LIMIT -> ctx.getString(R.string.ai_err_429)
        Kind.SERVER -> ctx.getString(R.string.ai_err_5xx, f.code)
        Kind.CLIENT ->
            if (f.detail.isNullOrBlank()) ctx.getString(R.string.ai_err_client, f.code)
            else ctx.getString(R.string.ai_err_client_detail, f.code, f.detail)
        Kind.BAD_RESPONSE -> ctx.getString(R.string.ai_err_bad_response)
        Kind.CANCELLED, Kind.OTHER -> ctx.getString(R.string.ai_err_other)
    }
}
