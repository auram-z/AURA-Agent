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
 * حافظه بلندمدت AURA
 * تمام ذخیره‌های مهم باید از ConfirmationGate عبور کنند.
 */
class MemoryStore(private val context: Context) {

    companion object {
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_NOTES = stringPreferencesKey("user_notes")
        private val KEY_PREFERENCES = stringPreferencesKey("user_preferences")
        private val KEY_GOALS = stringPreferencesKey("user_goals")
    }

    val userName: Flow<String?> = context.dataStore.data.map { it[KEY_USER_NAME] }
    val notes: Flow<String?> = context.dataStore.data.map { it[KEY_NOTES] }
    val preferences: Flow<String?> = context.dataStore.data.map { it[KEY_PREFERENCES] }
    val goals: Flow<String?> = context.dataStore.data.map { it[KEY_GOALS] }

    suspend fun saveUserName(name: String) {
        context.dataStore.edit { it[KEY_USER_NAME] = name }
    }

    suspend fun saveNotes(notes: String) {
        context.dataStore.edit { it[KEY_NOTES] = notes }
    }

    /**
     * ذخیره ترجیح: هم کلید جدا و هم خلاصه در KEY_PREFERENCES
     * تا buildLearnedContext بتواند آن را ببیند.
     */
    suspend fun savePreference(key: String, value: String) {
        val prefKey = stringPreferencesKey("pref_$key")
        context.dataStore.edit { prefs ->
            prefs[prefKey] = value
            val existing = prefs[KEY_PREFERENCES].orEmpty()
            val line = value.take(150)
            if (!existing.contains(line.take(40))) {
                prefs[KEY_PREFERENCES] = (existing + "\n• $line").trim().take(2000)
            }
        }
    }

    suspend fun saveGoals(goals: String) {
        context.dataStore.edit { it[KEY_GOALS] = goals }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
