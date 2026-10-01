package com.aura.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_memory")

/**
 * حافظه بلندمدت ساده AURA
 * در نسخه‌های بعدی می‌توان به Room + Embeddings ارتقا یابد
 */
class MemoryStore(private val context: Context) {

    companion object {
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_NOTES = stringPreferencesKey("user_notes")
        private val KEY_PREFERENCES = stringPreferencesKey("user_preferences")
    }

    val userName: Flow<String?> = context.dataStore.data.map { it[KEY_USER_NAME] }
    val notes: Flow<String?> = context.dataStore.data.map { it[KEY_NOTES] }

    suspend fun saveUserName(name: String) {
        context.dataStore.edit { it[KEY_USER_NAME] = name }
    }

    suspend fun saveNotes(notes: String) {
        context.dataStore.edit { it[KEY_NOTES] = notes }
    }

    suspend fun savePreference(key: String, value: String) {
        val prefKey = stringPreferencesKey("pref_$key")
        context.dataStore.edit { it[prefKey] = value }
    }
}
