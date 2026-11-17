package com.maites.cookies

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.data.UiPrefs
import com.maites.cookies.design.CookiesTheme
import com.maites.cookies.design.LocalColors
import com.maites.cookies.ui.AppShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CookiesApp
        setContent {
            val prefs by app.prefs.ui.collectAsStateWithLifecycle(UiPrefs())
            CookiesTheme(prefs.mode, prefs.accent) {
                val dark = LocalColors.current.isDark
                LaunchedEffect(dark) {
                    val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(bars, bars)
                }
                AppShell(app, prefs)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as CookiesApp).cloud.apply { foreground = true; syncSoon() }
    }

    override fun onStop() {
        super.onStop()
        (application as CookiesApp).cloud.foreground = false
    }
}
