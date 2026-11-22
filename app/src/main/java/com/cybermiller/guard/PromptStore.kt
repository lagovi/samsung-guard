package com.cybermiller.guard

import android.content.Context

/**
 * Owner-editable LLM prompt overrides, persisted in prefs (survive app
 * updates; `pm clear` resets to the built-in defaults).
 *
 * Names: "viber" — ViberAnalysis system prompt, "sms" — SMS triage prompt.
 */
object PromptStore {
    private const val PREF = "guard"
    const val VIBER = "viber"
    const val SMS = "sms"
    val ALL = listOf(VIBER, SMS)

    fun get(ctx: Context, name: String, default: String): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("prompt_$name", null) ?: default

    fun set(ctx: Context, name: String, value: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("prompt_$name", value).apply()
    }

    fun reset(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove("prompt_$name").apply()
    }

    fun isOverridden(ctx: Context, name: String, default: String): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("prompt_$name", null) != null
}
