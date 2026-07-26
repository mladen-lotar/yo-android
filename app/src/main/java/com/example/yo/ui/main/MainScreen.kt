package com.example.yo.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.yo.data.photo.decodeSampledBitmap
import com.example.yo.domain.model.Group
import com.example.yo.data.remote.AddFriendOutcome
import com.example.yo.domain.model.PhoneContact
import com.example.yo.domain.model.YoMessage
import com.example.yo.ui.theme.YoBody
import com.example.yo.ui.theme.YoLabel
import com.example.yo.ui.theme.YoPalette
import com.example.yo.ui.theme.YoRowText
import com.example.yo.ui.theme.YoRowTextSmall
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val THUMBNAIL_MAX_EDGE_PX = 512
private const val CAPTURED_PHOTOS_DIRECTORY = "captured-photos"

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
    val groups by viewModel.groups.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactQuery by viewModel.contactQuery.collectAsState()
    val contactsFilteredToNothing by viewModel.contactsFilteredToNothing.collectAsState()
    val addFriendOutcome by viewModel.addFriendOutcome.collectAsState()

    var attachTarget by remember { mutableStateOf<SendTarget?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var lastSentTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lastSentTo) {
        if (lastSentTo != null) {
            delay(SENT_FLASH_MILLIS)
            lastSentTo = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YoPalette.Amethyst),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val targets = friends.map { SendTarget.Friend(it) } +
                groups.map { SendTarget.YoGroup(it) }

            bandRows(
                targets = targets,
                lastSentTo = lastSentTo,
                onSend = { target ->
                    when (target) {
                        is SendTarget.Friend -> viewModel.sendYo(recipient = target.username)
                        is SendTarget.YoGroup -> viewModel.sendYoToGroup(target.group.id)
                    }
                    lastSentTo = target.label
                },
                onAttach = { attachTarget = it },
            )

            // Yo's "+" row: the bottom-most band, same height and treatment as any contact, and it
            // takes whatever colour its position lands on rather than a reserved one.
            item(key = "add-row") {
                Band(
                    color = YoPalette.colorForIndex(targets.size),
                    label = "+",
                    onClick = { sheet = Sheet.CreateGroup },
                    onLongClick = { sheet = Sheet.CreateGroup },
                )
            }

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
        }

        MenuButton(
            onClick = { sheet = Sheet.Menu },
            modifier = Modifier
                .align(Alignment.BottomEnd)
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
            onSend = { link, hashtag, attachLocation, photoUri ->
                when (target) {
                    is SendTarget.Friend ->
                        viewModel.sendYo(
                            recipient = target.username,
                            link = link,
                            hashtag = hashtag,
                            attachLocation = attachLocation,
                            photoUri = photoUri,
                        )
                    // Group sends fan out through the shared pipeline; per-Yo attachments were a
                    // single-recipient affordance, so a group Yo stays a plain Yo.
                    is SendTarget.YoGroup -> viewModel.sendYoToGroup(target.group.id)
                }
                lastSentTo = target.label
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
            onLogOut = {
                sheet = null
                viewModel.logOut()
            },
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

private enum class Sheet { Menu, History, CreateGroup, Invite, AddFriend }

/** A send target — a person or a group. Both render as an identical colour band. */
private sealed interface SendTarget {
    val label: String

    data class Friend(val username: String) : SendTarget {
        override val label: String get() = username
    }

    data class YoGroup(val group: Group) : SendTarget {
        override val label: String get() = group.name
    }
}

/**
 * Friends then groups over one continuous colour cycle. Colour follows POSITION, never identity —
 * the same contact provably appeared in different colours across builds, and Yo's own menu screen
 * (whose rows are not contacts) used the identical sequence.
 */
private fun LazyListScope.bandRows(
    targets: List<SendTarget>,
    lastSentTo: String?,
    onSend: (SendTarget) -> Unit,
    onAttach: (SendTarget) -> Unit,
) {
    targets.forEachIndexed { index, target ->
        val isGroup = target is SendTarget.YoGroup
        item(key = "band-${if (isGroup) "group" else "friend"}-${target.label}") {
            Band(
                color = YoPalette.colorForIndex(index),
                label = if (lastSentTo == target.label) "YO!" else target.label,
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
) {
    val text = label.uppercase()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(color)
            .yoTap(onClick = onClick, onLongClick = onLongClick, clickLabel = "Yo $text"),
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
    onLogOut: () -> Unit,
) {
    YoSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
            Band(
                color = YoPalette.colorForIndex(4),
                label = if (username.isEmpty()) "LOG OUT" else "LOG OUT $username",
                onClick = onLogOut,
                onLongClick = onLogOut,
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
    val line = buildString {
        append("${message.sender.uppercase()} → ${message.recipient.uppercase()}")
        message.link?.let { append("  ·  $it") }
        message.hashtag?.let { append("  ·  #$it") }
        if (message.latitude != null && message.longitude != null) {
            append("  ·  ${message.latitude}, ${message.longitude}")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        Text(text = line, style = YoBody)
        message.photoUri?.let { photoUri ->
            Spacer(modifier = Modifier.height(6.dp))
            PhotoThumbnail(
                photoUri = Uri.parse(photoUri),
                contentDescription = "Photo sent to ${message.recipient}",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
        }
    }
}

@Composable
private fun AttachSheet(
    target: SendTarget,
    accent: Color,
    onDismiss: () -> Unit,
    onSend: (link: String, hashtag: String, attachLocation: Boolean, photoUri: String?) -> Unit,
) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    var hashtag by rememberSaveable { mutableStateOf("") }
    var attachLocation by rememberSaveable { mutableStateOf(false) }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCaptureUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> attachLocation = granted }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        if (captured) photoUri = pendingCaptureUri
        pendingCaptureUri = null
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            createCapturedPhotoUri(context)?.let { uri ->
                pendingCaptureUri = uri
                runCatching { takePictureLauncher.launch(uri) }
                    .onFailure { pendingCaptureUri = null }
            }
        }
    }
    val choosePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { sourceUri ->
            copyPhotoToCache(context, sourceUri)?.let { cachedUri -> photoUri = cachedUri }
        }
    }

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
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    PillRow(
                        label = "CAMERA",
                        color = YoPalette.colorForIndex(6),
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    PillRow(
                        // Wisteria is skipped here: at #8E44AD it sits too close to the Amethyst
                        // sheet background to read as a distinct control.
                        label = "GALLERY",
                        color = YoPalette.colorForIndex(4),
                        onClick = { choosePhotoLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    )
                }
                photoUri?.let { uri ->
                    Spacer(modifier = Modifier.height(12.dp))
                    PhotoThumbnail(
                        photoUri = uri,
                        contentDescription = "Attached photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            // The wordmark is "Yo" — Yo's guidelines explicitly forbid "YO", so the send control
            // that carries the brand name keeps its mixed case even though rows are uppercase.
            SendButton(label = "Yo", color = accent) {
                onSend(link, hashtag, attachLocation, photoUri?.toString())
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

@Composable
private fun PhotoThumbnail(
    photoUri: Uri,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = photoUri,
    ) {
        value = null
        value =
            try {
                withContext(Dispatchers.IO) {
                    decodeSampledBitmap(context, photoUri, THUMBNAIL_MAX_EDGE_PX)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

private fun createCapturedPhotoUri(context: Context): Uri? =
    runCatching {
        val directory = capturedPhotosDirectory(context)
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        if (!file.createNewFile()) {
            return@runCatching null
        }
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }.getOrNull()

private fun copyPhotoToCache(
    context: Context,
    sourceUri: Uri,
): Uri? =
    runCatching {
        val directory = capturedPhotosDirectory(context)
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return@runCatching null
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }.getOrNull()

private fun capturedPhotosDirectory(context: Context): File =
    File(context.cacheDir, CAPTURED_PHOTOS_DIRECTORY).apply {
        check(exists() || mkdirs())
    }
