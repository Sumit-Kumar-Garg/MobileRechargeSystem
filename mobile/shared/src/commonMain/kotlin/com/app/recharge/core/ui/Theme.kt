package com.app.recharge.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Magenta = Color(0xFFE20074)

private val Light = lightColorScheme(primary = Magenta, onPrimary = Color.White)
private val Dark = darkColorScheme(primary = Color(0xFFFF5CAD), onPrimary = Color.Black)

@Composable
fun RechargeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
