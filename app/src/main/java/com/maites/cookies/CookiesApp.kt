package com.maites.cookies

import android.app.Application
import com.maites.cookies.data.AppDb
import com.maites.cookies.data.Prefs
import com.maites.cookies.data.PriceSync

class CookiesApp : Application() {
    val prefs by lazy { Prefs(this) }
    val db by lazy { AppDb.create(this) }
    val priceSync by lazy { PriceSync(db, prefs) }
    /** Outlives screens — for fire-and-forget network calls. */
    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
}
