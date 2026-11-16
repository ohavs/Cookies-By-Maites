package com.maites.cookies.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maites.cookies.design.Accent
import com.maites.cookies.design.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("prefs")

data class UiPrefs(val mode: ThemeMode = ThemeMode.System, val accent: Accent = Accent.Caramel)

class Prefs(private val ctx: Context) {
    private val kMode = stringPreferencesKey("theme_mode")
    private val kAccent = stringPreferencesKey("accent")

    val ui: Flow<UiPrefs> = ctx.store.data.map { p ->
        UiPrefs(
            mode = p[kMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            accent = p[kAccent]?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: Accent.Caramel,
        )
    }

    private val kCity = stringPreferencesKey("city")
    private val kSync = longPreferencesKey("price_sync")

    /** Chosen city for chain prices; "" = not chosen yet. */
    val city: Flow<String> = ctx.store.data.map { it[kCity].orEmpty() }
    suspend fun setCity(c: String) = ctx.store.edit { it[kCity] = c }
    suspend fun lastPriceSync(): Long = ctx.store.data.first()[kSync] ?: 0L
    suspend fun setLastPriceSync(t: Long) = ctx.store.edit { it[kSync] = t }

    suspend fun setMode(m: ThemeMode) = ctx.store.edit { it[kMode] = m.name }
    suspend fun setAccent(a: Accent) = ctx.store.edit { it[kAccent] = a.name }
}
