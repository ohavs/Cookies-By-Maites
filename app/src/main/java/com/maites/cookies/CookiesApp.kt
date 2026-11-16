package com.maites.cookies

import android.app.Application
import com.maites.cookies.data.AppDb
import com.maites.cookies.data.Prefs
import com.maites.cookies.data.PriceSync
import kotlinx.coroutines.launch

class CookiesApp : Application() {
    val prefs by lazy { Prefs(this) }
    val db by lazy { AppDb.create(this) }
    val priceSync by lazy { PriceSync(db, prefs, com.maites.cookies.data.LocalChains(this)) }
    val catalog by lazy { com.maites.cookies.data.CatalogSync(db, prefs) }
    val auth by lazy { com.maites.cookies.data.CloudAuth(prefs) }
    val cloud by lazy { com.maites.cookies.data.CloudSync(this, db, prefs, auth, applicationScope) }
    /** Outlives screens — for fire-and-forget network calls. */
    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        cloud.start()
        applicationScope.launch { com.maites.cookies.data.ProductPhotos.localizeAll(this@CookiesApp, db.products()) }
    }
}
