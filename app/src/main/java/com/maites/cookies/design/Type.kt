package com.maites.cookies.design

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.maites.cookies.R

@OptIn(ExperimentalTextApi::class)
private fun rubik(w: Int) = Font(
    R.font.rubik,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

val Rubik = FontFamily(rubik(400), rubik(500), rubik(600), rubik(700), rubik(800))

object TextStyles {
    val display = TextStyle(fontFamily = Rubik, fontWeight = FontWeight(800), fontSize = 32.sp, lineHeight = 36.sp)
    val title = TextStyle(fontFamily = Rubik, fontWeight = FontWeight(700), fontSize = 22.sp, lineHeight = 28.sp)
    val heading = TextStyle(fontFamily = Rubik, fontWeight = FontWeight(600), fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Rubik, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 21.sp)
    val label = TextStyle(fontFamily = Rubik, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 17.sp)
    val caption = TextStyle(fontFamily = Rubik, fontWeight = FontWeight(500), fontSize = 11.sp, lineHeight = 14.sp)
}
