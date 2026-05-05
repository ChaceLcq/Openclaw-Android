package com.agenew.translate.tts

import android.content.Context

object TtsBackendPreferences {
    private const val PREFS_NAME = "tts_backend_prefs"
    private const val KEY_PREFERRED_BACKEND = "preferred_backend"

    fun getPreferredBackend(context: Context): TtsBackendType {
        val storedValue = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_BACKEND, null)

        return runCatching { storedValue?.let(TtsBackendType::valueOf) }
            .getOrNull()
            ?: TtsBackendType.BERT_VITS2
    }

    fun setPreferredBackend(context: Context, backendType: TtsBackendType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_BACKEND, backendType.name)
            .apply()
    }
}
