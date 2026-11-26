package com.kai.keyboard

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * Тема «Системная»: Material You (динамические цвета, Android 12+) и автопереключение светлая/тёмная
 * вместе с системой. На Android 11 и ниже — обычные светлая или тёмная по системе.
 * Свой акцент выбранного пользователем цвета в ней не действует: берётся системный.
 */
object SystemTheme {
    fun isNight(ctx: Context): Boolean =
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun refresh(ctx: Context) {
        Theme.system = build(ctx.applicationContext, isNight(ctx))
    }

    private fun build(ctx: Context, night: Boolean): Theme {
        val base = if (night) Theme.DARK else Theme.LIGHT
        fun fallback() = Theme(Theme.SYSTEM_ID, "Системная", base.bg, base.key, base.fn, base.pressed,
            base.text, base.textDim, base.bubble, base.shadow, !night)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback()
        return try {
            fun c(id: Int) = ctx.getColor(id) or 0xFF000000.toInt()
            val r = android.R.color::class.java
            fun n(name: String) = c(r.getField(name).getInt(null))
            if (night) Theme(Theme.SYSTEM_ID, "Системная",
                bg = n("system_neutral1_900"), key = n("system_neutral1_700"), fn = n("system_neutral1_800"),
                pressed = n("system_neutral1_600"), text = n("system_neutral1_50"), textDim = n("system_neutral2_300"),
                bubble = n("system_neutral1_700"), shadow = 0, light = false, accent = n("system_accent1_200"))
            else Theme(Theme.SYSTEM_ID, "Системная",
                bg = n("system_neutral1_100"), key = n("system_neutral1_0"), fn = n("system_accent2_100"),
                pressed = n("system_neutral1_200"), text = n("system_neutral1_900"), textDim = n("system_neutral2_600"),
                bubble = n("system_neutral1_0"), shadow = n("system_neutral1_300"), light = true,
                accent = n("system_accent1_600"))
        } catch (e: Exception) {
            fallback()
        }
    }
}

fun Theme.accentFor(user: Int): Int = accent ?: user
