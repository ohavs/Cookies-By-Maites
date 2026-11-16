package com.maites.cookies

import android.app.Application
import com.maites.cookies.data.AppDb
import com.maites.cookies.data.Prefs

class CookiesApp : Application() {
    val prefs by lazy { Prefs(this) }
    val db by lazy { AppDb.create(this) }
}
