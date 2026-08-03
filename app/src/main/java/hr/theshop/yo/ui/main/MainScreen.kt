package hr.theshop.yo.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import hr.theshop.yo.BuildConfig
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import hr.theshop.yo.data.location.MapIntentFactory
import hr.theshop.yo.domain.location.LocationLink
import hr.theshop.yo.domain.model.Group
import hr.theshop.yo.data.remote.AddFriendOutcome
import hr.theshop.yo.domain.model.PhoneContact
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.ui.theme.YoBody
import hr.theshop.yo.ui.theme.YoLabel
import hr.theshop.yo.ui.theme.YoPalette
import hr.theshop.yo.ui.theme.YoRowText
import hr.theshop.yo.ui.theme.YoRowTextSmall
import kotlinx.coroutines.delay

/** How long a band reads "YO!" after a send. The original documented no confirmation at all. */
private const val SENT_FLASH_MILLIS = 900L

/** "Row Height: 89px" — stated verbatim in Yo's branding guidelines, measured at 89/87/87/86px. */
private val ROW_HEIGHT = 89.dp

/** The one piece of chrome Yo had: a ~48dp red circle, inset ~10-16dp from the bottom-right. */
private val MENU_BUTTON_SIZE = 48.dp
private val MENU_BUTTON_INSET = 12.dp

/** Labels longer than this drop a size so a group name still fits one line at a fixed size. */
private const val LONG_LABEL_CHARS = 9

/**
 * Yo's v1 main screen, reconstructed from Yo's own branding guidelines and pixel measurements of
 * archived store screenshots: a vertical stack of full-bleed 89dp colour bands, one per contact,
 * white uppercase Montserrat Bold centred in both axes, no dividers, and — deliberately — no chrome
 * whatsoever. No app bar, no title, no search, no section headers. The list starts at the first
 * pixel of the content area, and whatever space is left below the last band stays Amethyst purple,
 * exactly as the real app left it.
 *
 * Tap a band to Yo it. Long-press to attach something first (v1.1 used long-press on a name to send
 * a link, so the gesture is period-correct). Everything else — history, group creation — lives
 * behind the red menu button, which is where Yo put its only non-list affordance.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val friendsLoadFailed by viewModel.friendsLoadFailed.collectAsState()
    val pushUnavailable by viewModel.pushUnavailable.collectAsState()
    val pushRetrying by viewModel.pushRetrying.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactQuery by viewModel.contactQuery.collectAsState()
    val contactsFilteredToNothing by viewModel.contactsFilteredToNothing.collectAsState()
    val addFriendOutcome by viewModel.addFriendOutcome.collectAsState()
    val deletingAccount by viewModel.deletingAccount.collectAsState()
    val deleteAccountFailed by viewModel.deleteAccountFailed.collectAsState()
    val sendInFlightTo by viewModel.sendInFlightTo.collectAsState()
    val sendDeliveredTo by viewModel.sendDeliveredTo.collectAsState()
    val sendFailure by viewModel.sendFailure.collectAsState()
    val blocked by viewModel.blocked.collectAsState()
    val blockedLoadFailed by viewModel.blockedLoadFailed.collectAsState()

    var attachTarget by remember { mutableStateOf<SendTarget?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val context = LocalContext.current

    // The flash now waits for the server. It used to fire on tap, which meant a Yo that was rate
    // limited, addressed to a friend whose device never registered, or lost to a dead network was
    // celebrated exactly like one that arrived (gap G25).
    LaunchedEffect(sendDeliveredTo) {
        if (sendDeliveredTo != null) {
            delay(SENT_FLASH_MILLIS)
            viewModel.clearSendDelivered()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YoPalette.Amethyst),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // targetSdk 35+ puts every app edge-to-edge whether it asks or not, so the list now
            // draws underneath the status and navigation bars. Vertical insets only: the bands are
            // full-bleed by design and horizontal padding would inset their colour from the edge.
            contentPadding =
                WindowInsets.systemBars
                    .only(WindowInsetsSides.Vertical)
                    .asPaddingValues(),
        ) {
            val targets = friends.map { SendTarget.Friend(it) } +
                groups.map { SendTarget.YoGroup(it) }

            // PINNED ABOVE THE BANDS, and the position IS the fix.
            //
            // All three of these used to be appended AFTER every 89dp band in the same
            // LazyColumn. Measured on a Galaxy S23 - 1080x2340, density 480, so a band is 267px
            // against 2,120px of usable height: SEVEN bands fit, and at eight these rows are
            // pushed off-screen. The delivered flash is drawn on the band itself, so success was
            // always visible and only FAILURE could scroll away, which quietly re-opened G25 for
            // exactly the accounts that use the app most.
            //
            // Above the bands they cannot be scrolled past. The cost is that a failure shifts the
            // first band down by one row, and that is the right trade: the bands are the thing
            // you can always find again, the failure is the thing you cannot.
            if (friendsLoadFailed) {
                item(key = "friends-error") {
                    Text(
                        text = "COULDN'T LOAD FRIENDS",
                        style = YoLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                    )
                }
            }

            // Says the consequence, not the mechanism: "push registration failed" means nothing to
            // someone wondering why their phone is quiet. Tappable because the cause is usually
            // transient, so asking again is the one thing that actually helps (gap G17).
            if (pushUnavailable) {
                item(key = "push-error") {
                    Text(
                        text = if (pushRetrying) "RECONNECTING..." else "NOT RECEIVING YOS - TAP TO RETRY",
                        style = YoLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !pushRetrying) { viewModel.retryDeviceRegistration() }
                            .padding(vertical = 14.dp),
                    )
                }
            }

            // Same slot and same idiom as the push band above, for the same reason: name the
            // consequence, and offer the one action that helps. Silence here was the whole bug -
            // a Yo that never arrived left no trace anywhere in the UI.
            sendFailure?.let { failure ->
                item(key = "send-error") {
                    Text(
                        text = "COULDN'T YO ${failure.label} - TAP TO RETRY",
                        style = YoLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = sendInFlightTo == null) { viewModel.retrySend() }
                            .padding(vertical = 14.dp),
                    )
                }
            }

            bandRows(
                targets = targets,
                deliveredTo = sendDeliveredTo,
                inFlightTo = sendInFlightTo,
                onSend = { target ->
                    when (target) {
                        is SendTarget.Friend ->
                            viewModel.sendYo(recipient = target.username, label = target.label)
                        is SendTarget.YoGroup ->
                            viewModel.sendYoToGroup(target.group.id, label = target.label)
                    }
                },
                onAttach = { attachTarget = it },
            )

            // Yo's "+" row: the bottom-most band, same height and treatment as any contact, and it
            // takes whatever colour its position lands on rather than a reserved one.
            //
            // It says what it does rather than just "+". A bare plus was fine in the original,
            // where it sat under a list of people you already had; here a new account sees an
            // empty screen whose only affordance silently offers to group the nobody it knows.
            val hasNoContacts = friends.isEmpty()
            if (hasNoContacts) {
                // Only while the list is empty. Once there are people to Yo, the list itself is
                // the point and this would just be a permanent banner in the way.
                item(key = "add-contacts-row") {
                    Band(
                        color = YoPalette.colorForIndex(targets.size),
                        label = "+ ADD CONTACTS",
                        onClick = { sheet = Sheet.AddFriend },
                        onLongClick = { sheet = Sheet.AddFriend },
                        clickLabel = "Add contacts",
                    )
                }
            }
            item(key = "create-group-row") {
                Band(
                    color = YoPalette.colorForIndex(targets.size + if (hasNoContacts) 1 else 0),
                    label = "+ CREATE GROUP",
                    onClick = { sheet = Sheet.CreateGroup },
                    onLongClick = { sheet = Sheet.CreateGroup },
                    clickLabel = "Create a group",
                )
            }

        }

        MenuButton(
            onClick = { sheet = Sheet.Menu },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Without this the button sits under the navigation bar, which swallows the
                // touches: on a 2340px screen its 144px circle ended at y=2304 with the bar
                // covering everything below ~2190, leaving a ~30px strip that actually responded.
                // The menu is the only route to PRIVACY and DELETE ACCOUNT, both of which Play
                // requires to be reachable.
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                .padding(MENU_BUTTON_INSET),
        )
    }

    attachTarget?.let { target ->
        val targets = friends.map { SendTarget.Friend(it) } + groups.map { SendTarget.YoGroup(it) }
        AttachSheet(
            target = target,
            // The send control wears the target's own band colour, so the sheet reads as an
            // extension of the row you long-pressed. Alizarin is not an option here: Yo's
            // guidelines annotate it "red (menu button)" and it appears nowhere else.
            accent = YoPalette.colorForIndex(
                targets.indexOfFirst { it.label == target.label }.coerceAtLeast(0),
            ),
            onDismiss = { attachTarget = null },
            onSend = { link, hashtag, attachLocation ->
                when (target) {
                    is SendTarget.Friend ->
                        viewModel.sendYo(
                            recipient = target.username,
                            link = link,
                            hashtag = hashtag,
                            attachLocation = attachLocation,
                            label = target.label,
                        )
                    // Group sends fan out through the shared pipeline; per-Yo attachments were a
                    // single-recipient affordance, so a group Yo stays a plain Yo.
                    is SendTarget.YoGroup ->
                        viewModel.sendYoToGroup(target.group.id, label = target.label)
                }
                attachTarget = null
            },
            onRemoveFriend = {
                (target as? SendTarget.Friend)?.let { viewModel.removeFriend(it.username) }
                attachTarget = null
            },
            onBlock = {
                (target as? SendTarget.Friend)?.let { viewModel.blockFriend(it.username) }
                attachTarget = null
            },
        )
    }

    when (sheet) {
        Sheet.Menu -> MenuSheet(
            username = viewModel.username,
            onDismiss = { sheet = null },
            onHistory = { sheet = Sheet.History },
            onCreateGroup = { sheet = Sheet.CreateGroup },
            onInvite = { sheet = Sheet.Invite },
            onAddFriend = { sheet = Sheet.AddFriend },
            onBlocked = {
                viewModel.refreshBlocked()
                sheet = Sheet.Blocked
            },
            onLogOut = {
                sheet = null
                viewModel.logOut()
            },
            onPrivacy = { context.openUrl(BuildConfig.YO_PRIVACY_URL) },
            onDeleteAccount = { sheet = Sheet.DeleteAccount },
        )
        Sheet.DeleteAccount -> DeleteAccountSheet(
            username = viewModel.username,
            deleting = deletingAccount,
            failed = deleteAccountFailed,
            onDismiss = {
                viewModel.clearDeleteAccountFailure()
                sheet = null
            },
            onConfirm = viewModel::deleteAccount,
        )
        Sheet.AddFriend -> AddFriendSheet(
            outcome = addFriendOutcome,
            onDismiss = {
                viewModel.clearAddFriendOutcome()
                sheet = null
            },
            onAdd = viewModel::addFriend,
        )
        Sheet.Invite -> InviteSheet(
            contacts = contacts,
            query = contactQuery,
            filteredToNothing = contactsFilteredToNothing,
            onQueryChange = viewModel::onContactQueryChange,
            onDismiss = {
                // Clear the query on close so the next open starts from the whole address book.
                viewModel.onContactQueryChange("")
                sheet = null
            },
            onRefreshContacts = viewModel::refreshContacts,
            inviteMessageFor = viewModel::inviteMessageFor,
        )
        Sheet.Blocked -> BlockedSheet(
            blocked = blocked,
            loadFailed = blockedLoadFailed,
            onDismiss = { sheet = null },
            onUnblock = viewModel::unblock,
        )
        Sheet.History -> HistorySheet(history = history, onDismiss = { sheet = null })
        Sheet.CreateGroup -> CreateGroupSheet(
            friends = friends,
            onDismiss = { sheet = null },
            onCreate = { name, members ->
                viewModel.createGroup(name, members)
                sheet = null
            },
        )
        null -> Unit
    }
}

private enum class Sheet { Menu, History, CreateGroup, Invite, AddFriend, Blocked, DeleteAccount }

/** A send target — a person or a group. Both render as an identical colour band. */
internal sealed interface SendTarget {
    val label: String

    /**
     * Stable AND unique, which the label is not.
     *
     * Two groups may share a name - nothing stops it, and `GroupRepositoryImplTest` has a test
     * asserting that creating a duplicate name yields a distinct group, so it is a deliberate
     * contract rather than an oversight. Keying the LazyColumn on the label therefore produced
     * duplicate keys, which Compose treats as a fatal error: the home screen crashed on every
     * launch, and because groups were local and survived logout at the time this was written, the
     * only ways out were Clear Data, reinstall, or account deletion - whose UI is behind the
     * screen that is crash-looping. Signing out now clears them too, but that changes nothing
     * about the key argument below either way: an id was always what made two identically-named
     * groups distinguishable, regardless of how long either of them lives.
     *
     * A group's identity is its id. A friend's is their username, which the server guarantees is
     * unique. The label is what the band SAYS, and was never identity.
     */
    val key: String

    data class Friend(val username: String) : SendTarget {
        override val label: String get() = username
        override val key: String get() = "friend-$username"
    }

    data class YoGroup(val group: Group) : SendTarget {
        override val label: String get() = group.name
        override val key: String get() = "group-${group.id}"
    }
}

/**
 * Friends then groups over one continuous colour cycle. Colour follows POSITION, never identity —
 * the same contact provably appeared in different colours across builds, and Yo's own menu screen
 * (whose rows are not contacts) used the identical sequence.
 */
private fun LazyListScope.bandRows(
    targets: List<SendTarget>,
    deliveredTo: String?,
    inFlightTo: String?,
    onSend: (SendTarget) -> Unit,
    onAttach: (SendTarget) -> Unit,
) {
    targets.forEachIndexed { index, target ->
        val isGroup = target is SendTarget.YoGroup
        item(key = "band-${target.key}") {
            Band(
                color = YoPalette.colorForIndex(index),
                // A send is a network round trip of up to ten seconds. Without the pending state
                // an honest flash would show nothing at all on tap over a slow connection, which
                // reads as a dead button.
                label = when (target.label) {
                    deliveredTo -> "YO!"
                    inFlightTo -> "..."
                    else -> target.label
                },
                onClick = { onSend(target) },
                onLongClick = { onAttach(target) },
            )
        }
    }
}

/** Tap to send, long-press to attach — the gesture pair shared by every band. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.yoTap(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    clickLabel: String,
): Modifier = combinedClickable(
    onClick = onClick,
    onLongClick = onLongClick,
    onClickLabel = clickLabel,
    onLongClickLabel = "Attach something to $clickLabel",
)

/**
 * One colour band: fixed 89dp, full-bleed, butted directly against its neighbours. A full-resolution
 * scan of the original screenshots shows single-pixel transitions between bands — no divider, no
 * gutter, no inset, no rounding, no shadow. Text is centred on both axes at a fixed size.
 */
@Composable
private fun Band(
    color: Color,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /**
     * What a screen reader announces. Defaults to the send action every contact band performs;
     * the bands that are not people pass their own, so TalkBack stops saying "Yo + CREATE GROUP".
     */
    clickLabel: String? = null,
) {
    val text = label.uppercase()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(color)
            .yoTap(
                onClick = onClick,
                onLongClick = onLongClick,
                clickLabel = clickLabel ?: "Yo $text",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            // Yo used one fixed size with no shrink-to-fit. Group names can be far longer than the
            // usernames it was tuned for, so anything long steps down once rather than overflowing.
            style = if (text.length > LONG_LABEL_CHARS) YoRowTextSmall else YoRowText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * Yo's only floating control: a red circle bottom-right carrying a white three-dot overflow glyph
 * on Android. ALIZARIN #E74C3C is the one palette entry Yo's guidelines annotate with a usage —
 * "red (menu button)" — so it appears here and nowhere else.
 */
@Composable
private fun MenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(MENU_BUTTON_SIZE)
            .clip(CircleShape)
            .background(YoPalette.Alizarin)
            .yoTap(onClick = onClick, onLongClick = onClick, clickLabel = "Menu"),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "⋮", style = YoRowText.copy(fontSize = 30.sp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YoSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = YoPalette.Amethyst,
        contentColor = YoPalette.OnColor,
    ) {
        content()
    }
}

/**
 * Yo's menu was itself a stack of colour bands — USER:LEO turquoise, INVITE emerald, FIND FRIENDS
 * peter, INDEX asphalt — so the menu reuses the same band treatment and the same colour cycle.
 */
@Composable
private fun MenuSheet(
    username: String,
    onDismiss: () -> Unit,
    onHistory: () -> Unit,
    onCreateGroup: () -> Unit,
    onInvite: () -> Unit,
    onAddFriend: () -> Unit,
    onBlocked: () -> Unit,
    onLogOut: () -> Unit,
    onPrivacy: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    YoSheet(onDismiss = onDismiss) {
        // Scrollable, because the content is now taller than the sheet.
        //
        // Eight 89dp bands plus the drag handle overflow a ModalBottomSheet's maximum height, and
        // a plain Column simply clips: DELETE ACCOUNT rendered about 80px of its 267px on a
        // Galaxy S23 and nothing would scroll to reveal the rest. It stayed reachable - tapping
        // the visible strip did open the confirmation - so this was cosmetic rather than G22
        // repeating, but it is a Play requirement drawn at a third of its size, and a ninth band
        // would have pushed it off the sheet completely.
        //
        // The other sheets were never affected because they are LazyColumns, which scroll by
        // construction. This is the one built from a Column, and it is the one that grew.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // ADD FRIEND is the counterpart of Yo's "FIND FRIENDS" row, and it is load-bearing:
            // friendships are explicit, so without it a fresh account has no bands at all.
            Band(
                color = YoPalette.colorForIndex(0),
                label = "ADD FRIEND",
                onClick = onAddFriend,
                onLongClick = onAddFriend,
            )
            // INVITE is not an invention: Yo's own menu render carried INVITE and FIND FRIENDS
            // rows, and the Windows Phone build had a dedicated INVITE band in the contact list.
            Band(
                color = YoPalette.colorForIndex(1),
                label = "INVITE",
                onClick = onInvite,
                onLongClick = onInvite,
            )
            Band(
                color = YoPalette.colorForIndex(2),
                label = "HISTORY",
                onClick = onHistory,
                onLongClick = onHistory,
            )
            Band(
                color = YoPalette.colorForIndex(3),
                label = "NEW GROUP",
                onClick = onCreateGroup,
                onLongClick = onCreateGroup,
            )
            // The other half of BLOCK. Without a route back, blocking someone by mistake is
            // permanent from inside the app even though the server has always supported undoing it.
            Band(
                color = YoPalette.colorForIndex(4),
                label = "BLOCKED",
                onClick = onBlocked,
                onLongClick = onBlocked,
                clickLabel = "See who you have blocked",
            )
            Band(
                color = YoPalette.colorForIndex(5),
                label = if (username.isEmpty()) "LOG OUT" else "LOG OUT $username",
                onClick = onLogOut,
                onLongClick = onLogOut,
                clickLabel = "Log out",
            )
            // Alizarin is the palette's only red and the doc reserves it for the menu button, so
            // it is the one colour here that reads as "this one is different". Deleting an account
            // is the only irreversible thing the app can do.
            Band(
                color = YoPalette.colorForIndex(6),
                label = "PRIVACY",
                onClick = onPrivacy,
                onLongClick = onPrivacy,
                clickLabel = "Open the privacy policy",
            )
            Band(
                color = YoPalette.Alizarin,
                label = "DELETE ACCOUNT",
                onClick = onDeleteAccount,
                onLongClick = onDeleteAccount,
                clickLabel = "Delete account",
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * The confirmation Google Play's deletion requirement implies: the policy asks for deletion to be
 * reachable in the app, not for it to be one tap away from a menu. The account name is spelled out
 * because it is the thing being destroyed, and the consequences are listed rather than summarised
 * as "this cannot be undone" - a user who has not been told their friends lose them too has not
 * really been told.
 */
@Composable
private fun DeleteAccountSheet(
    username: String,
    deleting: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    YoSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (username.isEmpty()) "DELETE ACCOUNT" else "DELETE $username",
                style = YoLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
            )
            Text(
                text =
                    "THIS ERASES YOUR ACCOUNT, YOUR FRIENDS AND YOUR HISTORY. " +
                        "YOU DISAPPEAR FROM OTHER PEOPLE'S LISTS. IT CANNOT BE UNDONE.",
                style = YoBody,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
            if (failed) {
                Text(
                    text = "COULD NOT DELETE - NOTHING WAS CHANGED. TRY AGAIN.",
                    style = YoLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
            }
            Band(
                color = YoPalette.Alizarin,
                label = if (deleting) "DELETING..." else "DELETE FOREVER",
                onClick = { if (!deleting) onConfirm() },
                onLongClick = { if (!deleting) onConfirm() },
                clickLabel = "Delete account permanently",
            )
            Band(
                color = YoPalette.colorForIndex(0),
                label = "KEEP MY ACCOUNT",
                onClick = onDismiss,
                onLongClick = onDismiss,
                clickLabel = "Keep my account",
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Add somebody by typing their username. Unilateral and with no acceptance step, matching Yo:
 * you added a name and could Yo it immediately. Blocking, not approval, was the control.
 */
@Composable
private fun AddFriendSheet(
    outcome: AddFriendOutcome?,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var candidate by rememberSaveable { mutableStateOf("") }

    // Close on success rather than making the user dismiss a sheet that has done its job.
    LaunchedEffect(outcome) {
        if (outcome == AddFriendOutcome.Added) {
            onDismiss()
        }
    }

    YoSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(modifier = Modifier.height(18.dp))
            ChromelessField(
                value = candidate,
                onValueChange = { candidate = it.uppercase() },
                placeholder = "USERNAME",
            )
            val problem = when (outcome) {
                AddFriendOutcome.NoSuchUser -> "NOBODY HERE BY THAT NAME"
                AddFriendOutcome.Rejected -> "THAT IS NOT A USERNAME YOU CAN ADD"
                AddFriendOutcome.Unreachable -> "CAN'T REACH YO"
                AddFriendOutcome.Added, null -> null
            }
            if (problem != null) {
                Text(
                    text = problem,
                    style = YoLabel,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
        }
        Band(
            color = YoPalette.colorForIndex(0),
            label = "ADD",
            onClick = { onAdd(candidate) },
            onLongClick = { onAdd(candidate) },
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Pick a contact, hand the invite to the system share sheet. Yo never sees who you sent it to and
 * never reads a phone number — [PhoneContact] carries only an id and a display name, and the
 * recipient is chosen inside WhatsApp/Viber/Messenger/SMS after the chooser opens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InviteSheet(
    contacts: List<PhoneContact>,
    query: String,
    filteredToNothing: Boolean,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefreshContacts: () -> Unit,
    inviteMessageFor: (PhoneContact?) -> String,
) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (granted) onRefreshContacts()
    }

    // Read on open, not at startup: the grant may have happened seconds ago, or the address book
    // may have changed since the app launched.
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onRefreshContacts()
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    YoSheet(onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // The header stays pinned outside the scrolling list further down; here it is the
            // title plus the search field, which has to remain reachable with a long address book.
            stickyHeader(key = "invite-header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(YoPalette.Amethyst),
                ) {
                    Text(
                        text = "INVITE TO YO",
                        style = YoLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 12.dp),
                    )
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        ChromelessField(
                            value = query,
                            onValueChange = onQueryChange,
                            placeholder = "SEARCH CONTACTS",
                            // Names are mixed case, so the search field must not force caps the way
                            // the link/hashtag fields do.
                            capitalize = false,
                        )
                    }
                }
            }

            // Always available, permission or not — sharing the link never needed the address book.
            item(key = "invite-share-anyone") {
                Band(
                    color = YoPalette.colorForIndex(0),
                    label = "SHARE LINK",
                    onClick = { context.shareInvite(inviteMessageFor(null)) },
                    onLongClick = { context.shareInvite(inviteMessageFor(null)) },
                )
            }

            if (contacts.isEmpty()) {
                item(key = "invite-empty") {
                    Text(
                        text = when {
                            filteredToNothing -> "NO CONTACT MATCHES \"${query.trim().uppercase()}\""
                            permissionDenied -> "NO CONTACTS ACCESS — SHARE LINK STILL WORKS"
                            else -> "NO CONTACTS FOUND"
                        },
                        style = YoLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                    )
                }
            }

            itemsIndexed(contacts, key = { _, contact -> contact.id }) { index, contact ->
                Band(
                    // +1 so the first contact does not repeat the SHARE LINK band's colour.
                    color = YoPalette.colorForIndex(index + 1),
                    label = contact.displayName,
                    onClick = { context.shareInvite(inviteMessageFor(contact)) },
                    onLongClick = { context.shareInvite(inviteMessageFor(contact)) },
                )
            }

            item(key = "invite-bottom") {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

/**
 * Fires the system chooser. ACTION_SEND with text/plain is what every messenger registers for, so
 * Viber, Messenger, WhatsApp, Signal, Telegram, SMS and email all appear without Yo integrating
 * with any of them.
 */
private fun Context.shareInvite(message: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Yo")
        putExtra(Intent.EXTRA_TEXT, message)
    }
    val chooser = Intent.createChooser(send, "Invite to Yo").apply {
        // The sheet is launched from a Composable, whose context may not be an Activity.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(chooser) }
}

/** Opens a URL in the browser. Used for the privacy policy, which Play requires to be
 * reachable from inside the app as well as from the store listing. */
private fun Context.openUrl(url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(view) }
}

/**
 * Opens the shared point on a map. Built by the same factory the notification uses, so a location
 * behaves identically whether it is opened from the shade or from history.
 */
private fun Context.openMap(latitude: Double, longitude: Double, label: String) {
    val intent =
        MapIntentFactory.forCoordinates(this, latitude, longitude, label)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
    runCatching { startActivity(intent) }
}

/**
 * Everyone this account has blocked, each row tapping to undo it. A blocked sender is told nothing
 * - the server answers their Yo as delivered on purpose, so the block is not a notification for
 * the person blocked - which means this list is the only place either party can see it exists.
 */
@Composable
private fun BlockedSheet(
    blocked: List<String>,
    loadFailed: Boolean,
    onDismiss: () -> Unit,
    onUnblock: (String) -> Unit,
) {
    YoSheet(onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(key = "blocked-title") {
                Text(
                    text = "BLOCKED",
                    style = YoLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp),
                )
            }
            if (blocked.isEmpty()) {
                item(key = "blocked-empty") {
                    Text(
                        // "NOBODY" is an assertion, and it must only be made when the server
                        // actually said so. A failed fetch used to render it too, telling the
                        // user they had blocked no one on the one screen that exists to show
                        // exactly that - the friends list already drew this distinction.
                        text = if (loadFailed) "COULDN'T LOAD THIS LIST" else "NOBODY",
                        style = YoLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
            } else {
                item(key = "blocked-hint") {
                    Text(
                        text = "TAP SOMEONE TO UNBLOCK THEM",
                        style = YoLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                    )
                }
            }
            itemsIndexed(blocked, key = { _, name -> name }) { index, name ->
                Band(
                    color = YoPalette.colorForIndex(index),
                    label = name.uppercase(),
                    onClick = { onUnblock(name) },
                    onLongClick = { onUnblock(name) },
                    clickLabel = "Unblock $name",
                )
            }
            item(key = "blocked-bottom") {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun HistorySheet(
    history: List<YoMessage>,
    onDismiss: () -> Unit,
) {
    YoSheet(onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(key = "history-title") {
                Text(
                    text = "HISTORY",
                    style = YoLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp),
                )
            }
            if (history.isEmpty()) {
                item(key = "history-empty") {
                    Text(
                        text = "NOTHING YET",
                        style = YoLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
            }
            items(history, key = { it.id }) { message ->
                HistoryRow(message)
            }
            item(key = "history-bottom") {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun HistoryRow(message: YoMessage) {
    val context = LocalContext.current
    val latitude = message.latitude
    val longitude = message.longitude
    val hasLocation = latitude != null && longitude != null &&
        LocationLink.isValid(latitude, longitude)
    val line = buildString {
        append("${message.sender.uppercase()} → ${message.recipient.uppercase()}")
        message.link?.let { append("  ·  $it") }
        message.hashtag?.let { append("  ·  #$it") }
        if (hasLocation) {
            append("  ·  ${LocationLink.format(latitude!!)}, ${LocationLink.format(longitude!!)}")
        }
        // Only `false` is marked. The three states are genuinely distinct and only one of them is
        // worth saying out loud:
        //
        //   true  - delivered, which is what a plain row has always meant. Adding "SENT" to every
        //           successful row would be noise on the common case.
        //   false - did NOT arrive. This is the whole point: the band said COULDN'T YO and then
        //           the moment passed, while this row - the surface that LASTS - went on looking
        //           exactly like a Yo that landed.
        //   null  - written before the column existed, so the state is unknown. Rendered as it
        //           always was, because inventing either answer for an old row would be the same
        //           class of lie in a new place.
        if (message.delivered == false) {
            append("  ·  NOT DELIVERED")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasLocation) {
                    Modifier.clickable {
                        // Labelled with the sender: the pin marks where *they* were, which from
                        // this device's own history is the person who sent it.
                        context.openMap(latitude!!, longitude!!, message.sender)
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        Text(text = line, style = YoBody)
    }
}

@Composable
private fun AttachSheet(
    target: SendTarget,
    accent: Color,
    onDismiss: () -> Unit,
    onSend: (link: String, hashtag: String, attachLocation: Boolean) -> Unit,
    onRemoveFriend: () -> Unit,
    onBlock: () -> Unit,
) {
    var link by rememberSaveable { mutableStateOf("") }
    var hashtag by rememberSaveable { mutableStateOf("") }
    var attachLocation by rememberSaveable { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> attachLocation = granted }

    YoSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = target.label.uppercase(),
                style = YoRowTextSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(18.dp))

            if (target is SendTarget.YoGroup) {
                Text(
                    text = "GROUPS SEND A PLAIN YO",
                    style = YoLabel,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            } else {
                ChromelessField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = "LINK",
                )
                ChromelessField(
                    value = hashtag,
                    onValueChange = { hashtag = it },
                    placeholder = "HASHTAG",
                )
                PillRow(
                    label = if (attachLocation) "LOCATION ON" else "ATTACH LOCATION",
                    color = if (attachLocation) YoPalette.colorForIndex(1) else YoPalette.colorForIndex(3),
                    onClick = {
                        if (attachLocation) {
                            attachLocation = false
                        } else {
                            locationPermissionLauncher.launch(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            )
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            // The wordmark is "Yo" — Yo's guidelines explicitly forbid "YO", so the send control
            // that carries the brand name keeps its mixed case even though rows are uppercase.
            SendButton(label = "Yo", color = accent) {
                onSend(link, hashtag, attachLocation)
            }

            // Both endpoints and both view-model calls already existed; nothing in the UI reached
            // them, so the app shipped with no way to stop someone contacting you. Long-press on
            // the person is where this belongs - it is the only per-friend surface there is.
            if (target is SendTarget.Friend) {
                Spacer(modifier = Modifier.height(22.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    PillRow(
                        label = "REMOVE",
                        color = YoPalette.colorForIndex(4),
                        onClick = onRemoveFriend,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    PillRow(
                        label = "BLOCK",
                        color = YoPalette.colorForIndex(6),
                        onClick = onBlock,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "BLOCKING IS UNDONE FROM THE MENU", style = YoLabel)
            }
        }
    }
}

@Composable
private fun CreateGroupSheet(
    friends: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit,
) {
    var groupName by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    YoSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "NEW GROUP", style = YoRowTextSmall)
            Spacer(modifier = Modifier.height(16.dp))
            ChromelessField(
                value = groupName,
                onValueChange = { groupName = it },
                placeholder = "GROUP NAME",
            )
            friends.forEachIndexed { index, friend ->
                PillRow(
                    label = friend.uppercase(),
                    color = if (friend in selected) {
                        YoPalette.colorForIndex(index)
                    } else {
                        YoPalette.Amethyst
                    },
                    onClick = {
                        selected = if (friend in selected) selected - friend else selected + friend
                    },
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Spacer(modifier = Modifier.height(14.dp))
            SendButton(
                label = "CREATE",
                color = YoPalette.colorForIndex(1),
                enabled = groupName.isNotBlank(),
            ) {
                onCreate(groupName.trim(), friends.filter { it in selected })
            }
        }
    }
}

/**
 * Yo's inputs carried no chrome at all: `background-color: transparent; border: 0; width: 100%;
 * border-radius: 0`, white text, `rgba(255,255,255,0.8)` placeholders. One rule underneath is the
 * only affordance, so the field reads as type on colour.
 */
@Composable
private fun ChromelessField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    capitalize: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = YoLabel.copy(color = YoPalette.Placeholder, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = YoLabel.copy(fontSize = 16.sp),
                cursorBrush = SolidColor(YoPalette.OnColor),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (capitalize) {
                        KeyboardCapitalization.Characters
                    } else {
                        KeyboardCapitalization.None
                    },
                    imeAction = if (capitalize) ImeAction.Done else ImeAction.Search,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(YoPalette.Placeholder),
        )
        Spacer(modifier = Modifier.height(14.dp))
    }
}

/** A short colour band used inside sheets, where a full 89dp row would not fit. */
@Composable
private fun PillRow(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color)
            .yoTap(onClick = onClick, onLongClick = onClick, clickLabel = label)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = YoLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SendButton(
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (enabled) color else YoPalette.Amethyst)
            .yoTap(
                onClick = { if (enabled) onClick() },
                onLongClick = { if (enabled) onClick() },
                clickLabel = label,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = YoRowText, maxLines = 1)
    }
}

