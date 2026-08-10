package com.lightphone.chats.server

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Minimal status screen for the companion. Its real job is the SDK service;
 * this activity exists so the companion can be launched (e.g. from adb) and
 * its state observed during development.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StatusScreen() }
    }
}

@Composable
private fun StatusScreen() {
    val themeColors by LightThemeController.colors.collectAsState()

    LightTheme(colors = themeColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)
                .padding(32.dp),
        ) {
            LightText(text = "Chats Server", variant = LightTextVariant.Heading)
            LightText(
                text = "Companion for the Chats tool",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            LightText(
                text = "Phase 2 will add the Matrix sync loop here.",
                variant = LightTextVariant.Copy,
            )
        }
    }
}
