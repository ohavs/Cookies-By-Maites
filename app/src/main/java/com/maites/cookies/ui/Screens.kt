package com.maites.cookies.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.EmptyState
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles

@Composable
fun ScreenTitle(title: String, sub: String? = null) {
    Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Space.m)) {
        Text(title, style = TextStyles.display, color = C.colors.ink)
        if (sub != null) Text(sub, style = TextStyles.body, color = C.colors.inkDim)
    }
}

@Composable
fun RecipesScreen(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("מתכונים")
        Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
            EmptyState("🍪", "עוד אין מתכונים", "המתכון הראשון שלך יופיע כאן, עם תמחיר לכל עוגייה.", C.colors.tPeach) {
                StickerButton("מתכון חדש", onAdd)
            }
        }
    }
}

@Composable
fun ShoppingScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("קניות")
        Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
            EmptyState("🛒", "הרשימה ריקה", "בוחרים מתכונים, והרשימה והסופר הזול לסל נבנים לבד.", C.colors.tMint)
        }
    }
}

