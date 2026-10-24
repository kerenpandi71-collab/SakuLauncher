package com.sakuhome.launcher

import android.content.Context

/**
 * Persists the selected New Dimension UI style independently from launcher layout preferences.
 */
object ThemeManager {
    private const val PREF_NAME = "saku_prefs"
    private const val KEY_UI_STYLE = "ui_style_dimension"

    const val STYLE_SAKU = 0
    const val STYLE_AOSP_MATERIAL3 = 1

    fun setUIStyle(context: Context, styleId: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_UI_STYLE, styleId).apply()
    }

    fun getUIStyle(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_UI_STYLE, STYLE_AOSP_MATERIAL3)
}
