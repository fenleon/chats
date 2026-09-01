package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.contactIdentifier
import com.lightphone.chats.roomMatchesQuery
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Contacts (2026-08-29): the main list's bottom-middle CONTACTS icon opens
 * this — every chat, alphabetically, under Direct/Group tabs styled like the
 * Radio tool's Stations panel (Favourites/Recent). Direct is the default tab:
 * it dedupes by contact id (a person with several threads appears once, as
 * their newest room); Group lists every non-direct room. Archived rooms
 * appear in whichever tab their direct/group flag lands them in. The panel
 * is seeded from the chat list's active network filter (2026-08-30): opened
 * from All it shows every contact (title "All Contacts", network name as each
 * row's subtext — trial feature); opened from WhatsApp only that network's
 * rooms (title "WhatsApp Contacts", no subtext). SEARCH top-right filters the ACTIVE
 * tab's list (an in-panel query, not the room search): the editor title is
 * "Search All Contacts"/"Search {Network} Contacts" with the SEARCH icon, and the panel
 * title shows the searched term in quotes while a query is active
 * (feedback 2026-09-01). Tapping a row
 * opens that thread; back from the thread pops the contacts list too,
 * landing on the main list (the SearchScreen pattern, feedback 2026-08-22).
 */
class ContactsViewModel(
    /** The chat list's active network filter (null = all networks). */
    private val network: String? = null,
    /** The census the panel was opened from — seeds the first frame so the
     *  panel renders instantly instead of flashing the empty state while the
     *  first GetRooms round-trips (feedback 2026-09-01). */
    seedRooms: List<LightServiceMethod.GetRooms.Room> = emptyList(),
) : LightViewModel<Unit>() {

    enum class Tab { DIRECT, GROUP }

    /** One list entry: the row key (the dedup id for contacts, the room id
     *  for groups) and the room it opens. */
    data class Contact(val key: String, val room: LightServiceMethod.GetRooms.Room)

    /** Active tab — DIRECT by default (each visit starts fresh). */
    val tab = MutableStateFlow(Tab.DIRECT)
    /** In-panel search query; filters whichever tab is active. Blank = all. */
    val query = MutableStateFlow("")
    /** All rooms the companion reports; filtered + grouped by [entries]. */
    val rooms = MutableStateFlow(seedRooms)

    private var pollJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // No thread is on screen here; let the companion notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        refreshRooms()
        startPolling()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        stopPolling()
    }

    /** Re-fetches the census while the panel stays open (a cold process can
     *  answer the first call with an empty list while the companion's
     *  resolver seeds — the poll fills the panel in, same as the main list). */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                refreshRooms()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun refreshRooms() {
        viewModelScope.launch { rooms.value = ChatClient.getAllRooms() }
    }

    /** The active tab's rows, alphabetically: DIRECT dedupes by contact id
     *  (the first room of each group is the newest for that person); GROUP
     *  lists every non-direct room. A non-blank query keeps only matching
     *  names. The network filter (null = all) applies first, so contacts
     *  opened from WhatsApp show only WhatsApp rooms (2026-08-30). [rooms],
     *  [tab] and [query] are passed in from the screen's collected state —
     *  reading the flows' .value directly never recomposes when they change. */
    fun entries(
        rooms: List<LightServiceMethod.GetRooms.Room>,
        tab: Tab,
        query: String,
    ): List<Contact> {
        val q = query.trim()
        val direct = tab == Tab.DIRECT
        return rooms
            .filter { (network == null || it.network == network) && (if (direct) it.isDirect else !it.isDirect) }
            .let { list ->
                if (direct) {
                    list.groupBy { it.contactId ?: it.id }
                        .values
                        .map { it.first() }
                } else list
            }
            .filter { q.isEmpty() || roomMatchesQuery(it, q) }
            .sortedBy { it.name.lowercase() }
            .map { room ->
                Contact(
                    key = if (direct) (room.contactId ?: room.id) else room.id,
                    room = room,
                )
            }
    }

    private companion object {
        /** Panel refresh cadence — matches the main list's poll. */
        const val POLL_INTERVAL_MS = 5_000L
    }
}

class ContactsScreen(
    sealedActivity: SealedLightActivity,
    /** The chat list's active network filter (null = all networks) — the
     *  starting point matters: opened from All the panel lists every contact,
     *  from WhatsApp only WhatsApp rooms (2026-08-30). */
    private val network: String? = null,
    /** The census the panel was opened from — seeds the first frame (see
     *  [ContactsViewModel]). */
    private val seedRooms: List<LightServiceMethod.GetRooms.Room> = emptyList(),
) : LightScreen<Unit, ContactsViewModel>(sealedActivity) {

    override val viewModelClass: Class<ContactsViewModel>
        get() = ContactsViewModel::class.java

    override fun createViewModel(): ContactsViewModel = ContactsViewModel(network, seedRooms)

    @Composable
    override fun Content() {
        val tab by viewModel.tab.collectAsState()
        val query by viewModel.query.collectAsState()
        val rooms by viewModel.rooms.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val entries = remember(rooms, tab, query) { viewModel.entries(rooms, tab, query) }

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
                    // Title grammar (2026-08-30): the active search shows the
                    // searched term in quotes (feedback 2026-09-01); otherwise
                    // the network the panel was opened from ("WhatsApp
                    // Contacts"), or "All Contacts" from All (feedback
                    // 2026-09-01).
                    center = LightTopBarCenter.Text(
                        when {
                            query.isNotBlank() -> "'$query'"
                            network != null -> "$network Contacts"
                            else -> "All Contacts"
                        },
                    ),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = { openSearch() },
                        contentDescription = "Search contacts",
                    ),
                )
                // Direct/Group tabs — the Radio Stations panel grammar
                // (Favourites/Recent): centered labels, the active one
                // underlined.
                TabRow(tab = tab, onSelect = { viewModel.tab.value = it })
                Box(modifier = Modifier.weight(1f)) {
                    if (entries.isEmpty()) {
                        LightText(
                            text = when {
                                query.isNotBlank() -> if (tab == ContactsViewModel.Tab.DIRECT) {
                                    "No contacts match"
                                } else {
                                    "No groups match"
                                }
                                tab == ContactsViewModel.Tab.DIRECT -> "No contacts yet"
                                else -> "No groups yet"
                            },
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(
                                horizontal = 2f.gridUnitsAsDp(),
                                vertical = 24.dp,
                            ),
                        )
                    } else {
                        LightLazyScrollView(
                            // Two-line rows (id + network subtext) need the
                            // taller uniform estimate; single-line otherwise.
                            uniformItemHeightGridUnits = 3.8f,
                        ) {
                            items(entries, key = { it.key }) { contact ->
                                // The community's own room (announcement room)
                                // is named after the community — showing the
                                // community subtag there repeats the name
                                // (LP3 2026-09-01: "BERLIN SCENE LAB" read
                                // "WhatsApp · BERLIN SCENE LAB").
                                val community = contact.room.community?.takeUnless {
                                    it.equals(contact.room.name, ignoreCase = true)
                                }
                                val subtext = (
                                    if (network == null) {
                                        listOfNotNull(
                                            contact.room.network,
                                            identifierOf(contact) ?: community,
                                        ).joinToString(" · ")
                                    } else {
                                        identifierOf(contact) ?: community
                                    }
                                    ).takeIf { !it.isNullOrBlank() }
                                ContactRow(
                                    contact = contact,
                                    // Subtext grammar (2026-09-01): from All the
                                    // row reads "{Network} · {id}" (network alone
                                    // when the id hasn't resolved yet); inside a
                                    // network's own panel just the id — the
                                    // network would repeat. Groups with no id
                                    // show their community name in the id slot
                                    // (WhatsApp community groups, feedback
                                    // 2026-09-01).
                                    subtext = subtext,
                                    onOpen = { openThread(contact) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openThread(contact: ContactsViewModel.Contact) {
        // Back from the thread pops the contacts list too — lands on the main
        // list, same as search (feedback 2026-08-22).
        navigateTo(screenFactory = { ThreadScreen(it, contact.room) }) { goBack() }
    }

    private fun openSearch() {
        // The query editor reuses the account screen's field editor: LP3
        // keyboard, no emoji/return/voice, SEARCH submits. Empty clears the
        // filter; backing out without submitting keeps the previous query.
        // Title grammar (2026-08-30): "Search All Contacts" from All, "Search
        // {Network} Contacts" inside a network (feedback 2026-09-01); the
        // SEARCH button is now the search icon.
        navigateTo(
            screenFactory = {
                FieldEditorScreen(
                    it,
                    if (network == null) "Search All Contacts" else "Search $network Contacts",
                    viewModel.query.value,
                    "SEARCH",
                    submitIcon = LightIcons.SEARCH,
                )
            },
        ) { result -> viewModel.query.value = result }
    }
}

/** Direct/Group tabs — the Radio Stations panel grammar (Favourites/Recent):
 *  centered labels split evenly, the active one underlined by a bar. */
@Composable
private fun TabRow(
    tab: ContactsViewModel.Tab,
    onSelect: (ContactsViewModel.Tab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TabLabel(modifier = Modifier.weight(1f), text = "Direct", active = tab == ContactsViewModel.Tab.DIRECT) {
            onSelect(ContactsViewModel.Tab.DIRECT)
        }
        TabLabel(modifier = Modifier.weight(1f), text = "Group", active = tab == ContactsViewModel.Tab.GROUP) {
            onSelect(ContactsViewModel.Tab.GROUP)
        }
    }
}

/** One tab: the centered label with the active tab's underline bar beneath
 *  (the Radio Stations grammar, 2026-08-29). */
@Composable
private fun TabLabel(
    modifier: Modifier = Modifier,
    text: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.lightClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(text = text, variant = LightTextVariant.Subheading)
            if (active) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(LightThemeTokens.colors.content),
                )
            }
        }
    }
}

/** The row's display id — number/username from the companion's bridge
 *  resolution, formatted ([contactIdentifier] adds the '@' to non-phones);
 *  null for groups and unresolved contacts. */
private fun identifierOf(contact: ContactsViewModel.Contact): String? =
    contactIdentifier(contact.room.contactId, contact.room.name, contact.room.contactPhone)

/** A contact row: the name, Heading, with the id — and the network when the
 *  panel was opened from All — as a light subtext line (2026-08-30 trial,
 *  extended 2026-09-01: the id joined the network in the subtext). The
 *  leading inset (1.75 gu) sits the name where the room list's names land —
 *  the contacts rows have no unread-star column, so the 2.75-gu padding the
 *  search rows need to align with the starred list would float the names
 *  clear of the edge (feedback 2026-09-01: too much buffer). */
@Composable
private fun ContactRow(
    contact: ContactsViewModel.Contact,
    subtext: String?,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            .padding(
                start = 1.75f.gridUnitsAsDp(),
                end = 0.5f.gridUnitsAsDp(),
                top = 0.5f.gridUnitsAsDp(),
                bottom = 0.5f.gridUnitsAsDp(),
            ),
    ) {
        LightText(
            text = contact.room.name,
            variant = LightTextVariant.Heading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtext != null) {
            // The id/network line, Superfine — smaller than the room name
            // (feedback 2026-08-30: subtext was too big; 2026-09-01: Detail
            // still too big).
            LightText(
                text = subtext,
                variant = LightTextVariant.Superfine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
