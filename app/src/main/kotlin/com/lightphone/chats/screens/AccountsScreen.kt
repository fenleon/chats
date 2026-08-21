package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The network picker (feedback pass): "All" plus each bridged account
 * (WhatsApp, Instagram, …). The selection pops back with the label; the chat
 * list's bottom-right menu opens this panel. It was named "Accounts" until the
 * Phase 14 feedback pass — the rows are bridged networks, not accounts.
 *
 * Result is a non-null [AccountChoice] wrapper — the SDK only delivers non-null
 * screen results, so a bare `null` ("All") would be dropped and the list would
 * be stuck on the previous account.
 */
data class AccountChoice(val label: String?)

class AccountsScreen(
    sealedActivity: SealedLightActivity,
    private val accounts: List<String>,
    private val selected: String?,
) : SimpleLightScreen<AccountChoice>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back to chats",
                    ),
                    center = LightTopBarCenter.Text("Networks"),
                )
                LightScrollView {
                    AccountRow(
                        label = "All",
                        active = selected == null,
                        onClick = { goBack(AccountChoice(null)) },
                    )
                    accounts.forEach { account ->
                        AccountRow(
                            label = account,
                            active = selected == account,
                            onClick = { goBack(AccountChoice(account)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    LightText(
        text = label,
        // Networks is an option panel — row label at Heading per DESIGN.md.
        variant = LightTextVariant.Heading,
        // Every row full color; the selected one is underlined — selection is
        // conveyed by underline, not color (design rule, feedback 2026-08-21).
        underline = active,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    )
}
