package com.maites.cookies.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maites.cookies.design.Accent
import com.maites.cookies.design.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("prefs")

data class UiPrefs(val mode: ThemeMode = ThemeMode.System, val accent: Accent = Accent.Caramel)

/** Signed-in Google account (Firebase Auth). */
data class Account(val uid: String, val email: String, val name: String, val photo: String, val refreshToken: String)

/** Last phone-side fetch of Victory/Carrefour. [status] = "chain:ok:stores:prices;chain:err:msg". */
data class LocalState(val at: Long, val city: String, val status: String)

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
    private val kCatalog = longPreferencesKey("catalog_sync")
    suspend fun lastCatalog(): Long = ctx.store.data.first()[kCatalog] ?: 0L
    suspend fun setLastCatalog(t: Long) = ctx.store.edit { it[kCatalog] = t }

    private val kLocalAt = longPreferencesKey("local_sync")
    private val kLocalCity = stringPreferencesKey("local_city")
    private val kLocalStatus = stringPreferencesKey("local_status")

    val localState: Flow<LocalState> = ctx.store.data.map { LocalState(it[kLocalAt] ?: 0L, it[kLocalCity].orEmpty(), it[kLocalStatus].orEmpty()) }
    suspend fun localState(): LocalState = localState.first()
    suspend fun setLocalState(s: LocalState) = ctx.store.edit { it[kLocalAt] = s.at; it[kLocalCity] = s.city; it[kLocalStatus] = s.status }

    suspend fun setMode(m: ThemeMode) = ctx.store.edit { it[kMode] = m.name }
    suspend fun setAccent(a: Accent) = ctx.store.edit { it[kAccent] = a.name }

    private val kUid = stringPreferencesKey("acc_uid")
    private val kEmail = stringPreferencesKey("acc_email")
    private val kName = stringPreferencesKey("acc_name")
    private val kPhoto = stringPreferencesKey("acc_photo")
    private val kRefresh = stringPreferencesKey("acc_refresh")
    private val kCloudAt = longPreferencesKey("cloud_sync")
    private val kUploaded = stringSetPreferencesKey("cloud_photos")

    val account: Flow<Account?> = ctx.store.data.map { p ->
        p[kUid]?.takeIf { it.isNotBlank() }?.let { Account(it, p[kEmail].orEmpty(), p[kName].orEmpty(), p[kPhoto].orEmpty(), p[kRefresh].orEmpty()) }
    }
    suspend fun setAccount(a: Account?) = ctx.store.edit { p ->
        if (a == null) listOf(kUid, kEmail, kName, kPhoto, kRefresh).forEach { p -= it }
        else { p[kUid] = a.uid; p[kEmail] = a.email; p[kName] = a.name; p[kPhoto] = a.photo; p[kRefresh] = a.refreshToken }
    }
    suspend fun setRefreshToken(t: String) = ctx.store.edit { it[kRefresh] = t }

    /** Highest server sync time seen per cloud collection (RFC 3339). */
    suspend fun watermark(kind: String): String = ctx.store.data.first()[stringPreferencesKey("wm_$kind")] ?: "1970-01-01T00:00:00Z"
    suspend fun setWatermark(kind: String, t: String) = ctx.store.edit { it[stringPreferencesKey("wm_$kind")] = t }

    val lastCloudSync: Flow<Long> = ctx.store.data.map { it[kCloudAt] ?: 0L }
    suspend fun setLastCloudSync(t: Long) = ctx.store.edit { it[kCloudAt] = t }

    /** Recipe photo file names already in Storage. */
    suspend fun uploaded(): Set<String> = ctx.store.data.first()[kUploaded] ?: emptySet()
    suspend fun addUploaded(name: String) = ctx.store.edit { it[kUploaded] = (it[kUploaded] ?: emptySet()) + name }
    suspend fun removeUploaded(name: String) = ctx.store.edit { it[kUploaded] = (it[kUploaded] ?: emptySet()) - name }

    /** Forget all cloud progress (new sign-in): everything is merged again from scratch. */
    suspend fun resetCloud() = ctx.store.edit { p ->
        p.asMap().keys.filter { it.name.startsWith("wm_") }.forEach { p -= it }
        p.remove(kUploaded); p.remove(kCloudAt)
    }
}
