package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Chat search (feedback 2026-08-21): replaces the room list's VIEW UNREAD
 * toggle. Type a query (the LP3 keyboard, no emoji/mic/return rows — the
 * search action lives in the keyboard's bottom zone, DESIGN.md §16), tap the
 * SEARCH icon, and the results view lists matching rooms alphabetically. A
 * 1-character query matches; an empty search lists every chat. Results are
 * DIRECT chats by default — the bottom-middle "VIEW GROUP CHATS" toggle
 * reveals group chats (feedback 2026-08-21). The chat list's active network
 * filter (all / WhatsApp / Instagram) carries into the search. Selecting a
 * row opens that room's thread.
 *
 * Two views like the radio SearchScreen (radio/.../SearchScreen.kt): a
 * typing view and a results view. [SearchViewModel.showResults] lives in the
 * view model so the results view survives the thread round-trip (the
 * composition is disposed on navigate).
 */
class SearchViewModel(
    /** The chat list's active network filter (null = all networks). */
    private val networkFilter: String?,
) : LightViewModel<Unit>() {

    val query = MutableStateFlow("")
    /** All rooms the companion reports; filtered + sorted by [matchingRooms]. */
    val rooms = MutableStateFlow<List<LightServiceMethod.GetRooms.Room>>(emptyList())
    /** Exclusive mode: false (default) = direct chats only; true = group chats only. */
    val groupOnly = MutableStateFlow(false)
    /** True once a search was run — the results view stays up across navigation. */
    val showResults = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // No thread is on screen here; let the companion notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        // Refresh the room set (also on return from a thread — a new chat may
        // have arrived); the results list keeps its current rows meanwhile.
        viewModelScope.launch { rooms.value = ChatClient.getRooms() }
    }

    fun updateQuery(newQuery: String) {
        query.value = newQuery
    }

    /**
     * Matching rooms, alphabetically: a blank query matches everything.
     * Exclusive mode — direct chats by default, or group chats only when the
     * toggle is pressed (feedback 2026-08-21). The chat list's active network
     * filter (all / WhatsApp / Instagram) applies.
     */
    fun matchingRooms(): List<LightServiceMethod.GetRooms.Room> {
        val q = query.value.trim()
        return rooms.value
            .filter { room ->
                (networkFilter == null || room.network == networkFilter) &&
                    (if (groupOnly.value) !room.isDirect else room.isDirect) &&
                    (q.isEmpty() || room.name.contains(q, ignoreCase = true))
            }
            .sortedBy { it.name.lowercase() }
    }
}

class SearchScreen(
    sealedActivity: SealedLightActivity,
    private val networkFilter: String? = null,
) : LightScreen<Unit, SearchViewModel>(sealedActivity) {

    override val viewModelClass: Class<SearchViewModel>
        get() = SearchViewModel::class.java

    override fun createViewModel(): SearchViewModel = SearchViewModel(networkFilter)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val showResults by viewModel.showResults.collectAsState()
        val groupOnly by viewModel.groupOnly.collectAsState()

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    if (showResults) {
                        ResultsView(
                            groupOnly = groupOnly,
                            onToggleGroupOnly = {
                                viewModel.groupOnly.value = !viewModel.groupOnly.value
                            },
                            onBackToQuery = { viewModel.showResults.value = false },
                            onOpenRoom = ::openThread,
                            results = viewModel.matchingRooms(),
                        )
                    } else {
                        QueryView(
                            onSearch = { viewModel.showResults.value = true },
                            onBack = { goBack() },
                            onQueryChange = viewModel::updateQuery,
                        )
                    }
                }
            }
        }
    }

    private fun openThread(room: LightServiceMethod.GetRooms.Room) {
        // Returning from a thread opened via search closes the search: the
        // thread's back pops with a Unit result, so this callback pops the
        // search screen too — back from the thread lands on the MAIN list, not
        // the search results (feedback 2026-08-22).
        navigateTo(screenFactory = { ThreadScreen(it, room) }) { goBack() }
    }
}

/** The typing view: "Search Chats" title, keyboard without emoji/return/mic
 *  keys, SEARCH icon in the keyboard's bottom-middle action zone (the
 *  passes/podcast pattern, DESIGN.md §16). */
@Composable
private fun QueryView(
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    val textState = rememberTextFieldState("")
    // Single source of truth for the query lives in the ViewModel (the text
    // state itself is recreated per composition — the query must survive
    // navigation back from a thread).
    LaunchedEffect(textState.text) {
        onQueryChange(textState.text.toString())
    }
    val keyboardOptionsFlow = remember {
        MutableStateFlow(
            defaultKeyboardOptions().copy(
                emojis = emptyList(),
                displayReturn = false,
                displayVoice = false,
            ),
        )
    }
    LightTextInputEditor(
        title = "Search Chats",
        state = textState,
        keyboardOptionsFlow = keyboardOptionsFlow,
        onSubmit = { onSearch() },
        onBack = onBack,
        modifier = Modifier.background(LightThemeTokens.colors.background),
        submitLabel = "Search",
        submitIcon = LightIcons.SEARCH,
        singleLine = true,
        // The input centers vertically between the top bar and the keyboard —
        // the same treatment as the login field editors (design standard,
        // feedback 2026-08-22: the field sat flush under the top bar).
        centered = true,
    )
}

/** The results view: matching rooms alphabetically ("no chats found" when
 *  nothing matches), a bottom-middle exclusive DIRECT/GROUP CHATS mode
 *  switch, and a back arrow returning to the query. Direct chats by default. */
@Composable
private fun ColumnScope.ResultsView(
    groupOnly: Boolean,
    onToggleGroupOnly: () -> Unit,
    onBackToQuery: () -> Unit,
    onOpenRoom: (LightServiceMethod.GetRooms.Room) -> Unit,
    results: List<LightServiceMethod.GetRooms.Room>,
) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = onBackToQuery,
            contentDescription = "Back to search",
        ),
        center = LightTopBarCenter.Text("Search Results"),
    )
    Box(modifier = Modifier.weight(1f)) {
        if (results.isEmpty()) {
            LightText(
                text = "no chats found",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 24.dp),
            )
        } else {
            LightLazyScrollView(uniformItemHeightGridUnits = 2.6f) {
                items(results, key = { it.id }) { room ->
                    SearchResultRow(room = room, onOpen = { onOpenRoom(room) })
                }
            }
        }
    }
    LightBottomBar(
        modifier = Modifier.navigationBarsPadding(),
        items = listOf(
            // Bottom-middle (single-item bar): exclusive mode switch — direct
            // chats by default; "VIEW GROUP CHATS" swaps to group chats only
            // and flips the label (feedback 2026-08-21).
            LightBarButton.Text(
                text = if (groupOnly) "VIEW DIRECT CHATS" else "VIEW GROUP CHATS",
                onClick = onToggleGroupOnly,
            ),
        ),
    )
}

@Composable
private fun SearchResultRow(
    room: LightServiceMethod.GetRooms.Room,
    onOpen: () -> Unit,
) {
    LightText(
        text = room.name,
        variant = LightTextVariant.Heading,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            // Matches the room list's leading inset (1.75 gu row padding + the
            // 1 gu star column) so result names line up with list names.
            .padding(start = 2.75f.gridUnitsAsDp(), end = 0.5f.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
    )
}
