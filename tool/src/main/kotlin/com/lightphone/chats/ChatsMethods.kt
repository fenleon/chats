package com.lightphone.chats

import kotlinx.serialization.Serializable

/**
 * Chats RPC DTOs, ported locally (2026-10): Light's builder extracts only tool/ and
 * builds against pristine upstream light-sdk, whose LightServiceMethod has no chats
 * methods (they were workspace-local SDK patches). In-process (Rung-1 fold) these
 * are plain @Serializable data classes — the binder plumbing (id/serializers) is
 * dropped.
 */
object ChatsMethods {

    object SetAccount {

        @Serializable
        data class Request(
            val homeserver: String,
            val user: String,
            val passwordOrToken: String,
            val tokenLogin: Boolean = false,
        )

        @Serializable
        data class Response(
            val userId: String,
            val deviceId: String,
            val needsVerification: Boolean = false,
        )
    }

    /**
     * Requests a Beeper login code by email. The companion starts a Beeper
     * login request (Beeper's private API — endpoint/token live only in the
     * companion) and emails [Request.email] a 6-digit code, which is then
     * exchanged via [SetBeeperAccount].
     */
    object SetBeeperAccount {

        @Serializable
        data class Request(
            val email: String,
            val code: String,
        )

        @Serializable
        data class Response(
            val userId: String,
            val deviceId: String,
            val needsVerification: Boolean = false,
        )
    }

    object GetAccountState {

        @Serializable
        data class Response(
            val loggedIn: Boolean,
            val userId: String? = null,
            val homeserver: String? = null,
            /** "beeper" (v1, Beeper account) | "homeserver" (dev/test) | null. */
            val loginMode: String? = null,
        )
    }

    object GetRooms {

        @Serializable
        data class Room(
            val id: String,
            val name: String,
            val lastMessage: String,
            val unreadCount: Long,
            val lastTimestampMs: Long,
            /** Id of the newest timeline event, for the thread's initial cursor. */
            val lastEventId: String? = null,
            /** Direct (1:1) chat — the thread can hide per-message sender names. */
            val isDirect: Boolean = false,
            /**
             * The room's other participant for 1:1s — the single non-bot hero
             * (Beeper bridged DMs list the contact; bridge bots like
             * @whatsappbot are excluded). Null for groups. Drives the contact
             * overlay's phone/username line (chats, feedback 2026-08-21).
             */
            val contactId: String? = null,
            /**
             * The contact's phone number for 1:1s, when the room data carries
             * one: a @whatsapp_<number> ghost, or the number the bridge used
             * as the displayname before syncing the profile name (the LID
             * ghost's member event prev_content). Null otherwise — not every
             * contact exposes the number in room data (chats, feedback
             * 2026-08-23).
             */
            val contactPhone: String? = null,
            /**
             * Bridged network label ("WhatsApp", "Instagram", …), derived by the
             * companion from Beeper's per-network spaces. Null = not in any
             * space (Beeper-internal rooms, user-created spaces stay ungrouped).
             */
            val network: String? = null,
            /**
             * For rooms inside a bridged community (Beeper sub-spaces under an
             * account space — WhatsApp community groups): the community's own
             * name ("1 euro film"). Null otherwise. Groups carry the community
             * where 1:1s carry [contactId] (chats, feedback 2026-09-01).
             */
            val community: String? = null,
            /**
             * The user muted this room in the tool (chats): its messages stop
             * notifying; the unread badge and room list stay. Set via
             * [SetRoomMuted].
             */
            val muted: Boolean = false,
            /**
             * The user archived this room on Beeper: hidden from the main room list,
             * silent, reachable only via search VIEW ALL.
             */
            val archived: Boolean = false,
            /**
             * The user pinned this room (m.favourite tag): sorted to the top of the
             * room list; its row shows no latest timestamp.
             */
            val pinned: Boolean = false,
        )

        @Serializable
        data class Response(val rooms: List<Room>)
    }

    /**
     * The full room census — every room the resolver knows, trimmed rows (no
     * preview/unread/last event) so the whole account crosses one binder
     * transaction. The contacts list + search need to find ANY room, not just
     * the newest window [GetRooms] serves (chats 2026-08-30).
     */
    object GetMessages {

        @Serializable
        data class Request(
            val roomId: String,
            val beforeEventId: String? = null,
            val limit: Int = 30,
        )

        @Serializable
        data class Message(
            val id: String,
            val sender: String,
            val senderName: String,
            val body: String,
            val timestampMs: Long,
            val isMine: Boolean,
            /**
             * Beeper send status for an outgoing message ("PENDING" /
             * "FAIL_RETRIABLE" / …), resolved by the companion from the room's
             * `com.beeper.message_send_status` events. Null = no status event
             * (not ours, or delivered without a status report).
             */
            val sendStatus: String? = null,
            /**
             * How the tool renders the row: "text" | "image" (Chats has since
             * added "audio", "notice", and — Phase C, 2026-09-03 —
             * "redacted", the "Message unsent" tombstone). Images carry
             * their bytes over [GetMessageMedia] (keyed by room + event id).
             */
            val contentType: String = "text",
            /**
             * Whether the other party has read this outgoing message (the
             * room's m.read receipts). Only meaningful on the newest page —
             * older pages always report false (receipts describe the newest
             * events, and re-fetching them per page isn't worth it).
             */
            val read: Boolean = false,
            /**
             * Distinct reaction emoji on this message (the room's m.reaction
             * events), newest-page scope — older pages report empty. The tool
             * renders them as a small tag under the message.
             */
            val reactions: List<String> = emptyList(),
            /**
             * Voice-note length in milliseconds (the m.audio event's info
             * duration). Null for non-audio rows; the tool shows it on the row
             * and counts up to it while the note plays.
             */
            val durationMs: Long? = null,
            /**
             * Caption of an image message (the m.image event's body — most
             * clients put the caption there; the file name is a separate
             * field). Null for non-image rows and caption-less photos. The
             * tool renders it under the thumbnail.
             */
            val caption: String? = null,
            /**
             * Whether an m.replace edit updated this message (chats): the
             * companion serves the EDITED body in [body] and this flag makes
             * the tool render an "edited" tag under the row.
             */
            val edited: Boolean = false,
            /**
             * Whether the bridge flagged this message as forwarded (chats): the
             * WhatsApp forward header ("↷ Forwarded") is lifted out of [body] /
             * [caption], and this flag makes the tool render a small header
             * above the row's content.
             */
            val forwarded: Boolean = false,
            /**
             * Whether EDIT is offered on this own row (chats Phase C,
             * 2026-09-03): the bridge's `com.beeper.room_features` edit
             * capability (support level ≥ 1) AND inside its `edit_max_age`
             * window when one is set AND the row is text (the tool's edit is
             * text-only). False hides the action; the default keeps native
             * Matrix rooms and caps-less bridges ungated.
             */
            val canEdit: Boolean = true,
            /**
             * Whether UNSEND is offered on this own row (same gate as
             * [canEdit], the bridge's delete capability / `delete_max_age`).
             */
            val canUnsend: Boolean = true,
            /**
             * HTML subset (chats markdown) for the tool's rich rendering of an
             * incoming formatted message: the server puts the m.text event's
             * `formatted_body` here only when `format` is
             * `org.matrix.custom.html` (and any `mx-reply` block is already
             * stripped). Null for plain messages — render [body].
             */
            val formattedHtml: String? = null,
            /**
             * Reply-to context (chats): event id of the message this one
             * replies to, plus the resolved display name and one-line excerpt
             * of that original message (null when not a reply or when the
             * original couldn't be resolved — render the excerpt header only
             * when [replyToId] is set).
             */
            val replyToId: String? = null,
            val replyToSender: String? = null,
            val replyToExcerpt: String? = null,
        )

        @Serializable
        data class Response(
            val messages: List<Message>,
            /**
             * Whether there are more messages older than this page. The
             * companion computes it from the raw timeline page (not the
             * message-filtered list), so state events / still-encrypted events
             * don't truncate pagination early.
             */
            val hasMore: Boolean = false,
            /**
             * Whether this room is end-to-end encrypted. When the device isn't
             * verified the companion returns this without fetching (the events
             * can't decrypt anyway), so the tool can say so immediately.
             */
            val encrypted: Boolean = false,
            /**
             * The chain position paging stopped at — pass it as the next
             * `beforeEventId` to continue further back. The companion takes it
             * from the timeline CHAIN (the deepest event it actually read), so
             * it stays correct where a page's own row order can't: the serve
             * path sorts a bridged room's page by timestamp, and a cursor taken
             * from a page's first row then sat off the chain edge, re-serving
             * rows already shown — the tool prepended nothing and refetched the
             * same cursor forever (LP3 feedback 2026-09-12).
             */
            val nextBeforeEventId: String? = null,
            /**
             * Event id of the voice note currently playing in the companion
             * (null = nothing playing). The thread poll carries it so the tool
             * can highlight the row that's playing without an extra RPC.
             */
            val audioPlayingEventId: String? = null,
            /**
             * Playback position (ms) of the currently playing voice note
             * ([audioPlayingEventId]); null when nothing is playing. The tool
             * interpolates between polls so the counter runs smoothly.
             */
            val audioPositionMs: Long? = null,
            /**
             * The newest page came back EMPTY off a warm store whose serve
             * read nothing (chats): the store may still be filling — the
             * tool keeps showing "Loading messages…" until a page-revision
             * bump or its one-shot retry lands, instead of "No messages yet."
             */
            val pendingSeed: Boolean = false,
        )
    }

    object SendMessage {

        @Serializable
        data class Request(
            val roomId: String,
            val body: String,
            val replyToEventId: String? = null,
        )

        /**
         * The outbox transaction id; the message may still be pending delivery.
         * [eventId] is the timeline event id once the homeserver acked the send
         * (read back from the outbox after the ack); null if it isn't known yet
         * — the tool uses it for an optimistic thread row that the sync echo
         * replaces.
         */
        @Serializable
        data class Response(
            val transactionId: String,
            val eventId: String? = null,
        )
    }

    object GetConnectionState {

        @Serializable
        data class Response(
            /** "logged_out" | "connecting" | "syncing" | "offline" */
            val state: String,
            val detail: String? = null,
            /** Rooms in the list cache so far — the Settings sync-progress line. */
            val roomsTotal: Int = 0,
            /** Rooms whose name/preview have been resolved in the background so far. */
            val roomsResolved: Int = 0,
            /** Whether the companion's sync loop is running (Settings → Sync toggle). */
            val syncEnabled: Boolean = true,
            /** Background key-backup restore crawl in progress (Account screen's
             *  "Recovering… x of y rooms" line, 2026-08-29). */
            val restoreScanning: Boolean = false,
            val restoreScanned: Int = 0,
            val restoreRoomsTotal: Int = 0,
            /** True when the crawl ran to completion; the Account screen pairs it
             *  with [syncEnabled] to show "All messages restored". */
            val restoreCompleted: Boolean = false,
            /** Chats P2 stat: COUNT(*) of RoomProjection rows (rooms whose
             *  ingest-time projection is materialized) vs the joined-room
             *  count — the honest restore→sync→projecting aggregate the
             *  Account screen renders as "Synced x of n rooms". */
            val roomsProjected: Int = 0,
            val roomsJoined: Int = 0,
            /** Chats fresh-login backfill progress: rooms whose ThreadRow
             *  backfill has finished this login vs the pass's room total —
             *  the Account screen's "Syncing messages… x of y" counter.
             *  Total 0 = no backfill pass running. */
            val backfillRoomsDone: Int = 0,
            val backfillRoomsTotal: Int = 0,
            /** Chats: the room-list resolver's initial crawl has covered the
             *  full room map (see the stable-wrap gate). False during a fresh
             *  login while the list is still filling. */
            val roomListReady: Boolean = false,
        )
    }

    // --- E2EE methods (additive; Trixnity runs the crypto in the companion) ----

    /**
     * E2EE status of the current session: whether this device is cross-signing
     * verified (so messages can decrypt) and whether there are other devices on
     * the account to verify against.
     */
    object GetE2eeState {

        @Serializable
        data class Response(
            val verified: Boolean,
            val canVerify: Boolean,
            val detail: String? = null,
        )
    }

    /** Starts interactive (SAS/emoji) verification with the account's other devices. */
    object StartDeviceVerification {

        @Serializable
        data class Response(
            val started: Boolean,
            val error: String? = null,
        )
    }

    /** The tool polls this while a verification is in progress. */
    object GetVerificationState {

        @Serializable
        data class Response(
            /**
             * "none" | "waiting" (awaiting the other device) | "accept" (their
             * request/SAS — Accept) | "start" (Ready — Start) | "compare" (emoji
             * comparison) | "done" | "cancelled" | "error".
             */
            val state: String,
            /** The SAS emoji set to compare, when [state] == "compare". */
            val emoji: List<String>? = null,
            /** The device that accepted the verification request, when known. */
            val deviceId: String? = null,
            val detail: String? = null,
        )
    }

    /** Drives the interactive verification: "accept" | "start" | "match" | "no_match" | "cancel" | "reset". */
    object WaitForChange {

        @Serializable
        data class Request(
            val watch: String,
            val roomId: String? = null,
            val lastSeen: Long,
            val timeoutMs: Long,
        )

        @Serializable
        data class Response(val revision: Long)
    }

    // --- Passkey methods (local, additive addition for the Passkey companion;
    // upstreamable — production com.lightos should implement these for a real LP3).
    /**
     * Starts a caBLE v2 hybrid sign-in session in the companion from the
     * desktop's scanned QR payload (the FIDO:/… digits string): opens the
     * tunnel, advertises the EID, answers the KNpsk0 handshake, and dispatches
     * CTAP MakeCredential/GetAssertion frames through the authenticator. UV is
     * prompted via a companion UV activity when a frame requires it.
     */
}
