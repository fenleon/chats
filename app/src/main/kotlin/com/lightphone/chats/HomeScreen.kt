package com.lightphone.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : LightViewModel<Unit>() {

    /** "connected" once the binder round-trip to the companion succeeds. */
    val status = MutableStateFlow(INITIAL_STATUS)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        ping()
    }

    fun ping() {
        viewModelScope.launch {
            status.value = CONNECTING_STATUS
            var ok = ChatClient.ping()
            // A cold start can bind before the companion's service is ready;
            // retry briefly so the status doesn't flash an offline message.
            repeat(PING_RETRIES) {
                if (ok) return@repeat
                delay(PING_RETRY_DELAY_MS)
                ok = ChatClient.ping()
            }
            status.value = if (ok) CONNECTED_STATUS else OFFLINE_STATUS
        }
    }

    private companion object {
        const val INITIAL_STATUS = "scaffold"
        const val CONNECTING_STATUS = "connecting…"
        const val CONNECTED_STATUS = "connected"
        const val OFFLINE_STATUS = "can't reach server"
        const val PING_RETRIES = 5
        const val PING_RETRY_DELAY_MS = 1_000L
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel()

    @Composable
    override fun Content() {
        val status by viewModel.status.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LightText(text = "Chats", variant = LightTextVariant.Title)
                        LightText(
                            text = status,
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
