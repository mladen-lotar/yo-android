package com.example.yo.ui.main

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.yo.data.photo.decodeSampledBitmap
import com.example.yo.domain.model.Group
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMBNAIL_MAX_EDGE_PX = 512
private const val CAPTURED_PHOTOS_DIRECTORY = "captured-photos"

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val history by viewModel.history.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val friendsLoadFailed by viewModel.friendsLoadFailed.collectAsState()
    val groups by viewModel.groups.collectAsState()
    var selectedFriend by remember { mutableStateOf<String?>(null) }
    var linkText by remember { mutableStateOf("") }
    var hashtagText by remember { mutableStateOf("") }
    var attachLocation by remember { mutableStateOf(false) }
    var capturedPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCaptureUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        attachLocation = granted
    }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        if (captured) {
            capturedPhotoUri = pendingCaptureUri
        }
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
            copyPhotoToCache(context, sourceUri)?.let { cachedUri ->
                capturedPhotoUri = cachedUri
            }
        }
    }

    LaunchedEffect(friends) {
        if (selectedFriend !in friends) {
            selectedFriend = friends.firstOrNull()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        item(key = "main-screen-header") {
            Text(
                text = "Choose a friend",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (friendsLoadFailed) {
                Text("Couldn't load friends")
            }
            friends.forEach { friend ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = friend == selectedFriend,
                            onClick = { selectedFriend = friend },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = friend == selectedFriend,
                        onClick = null,
                    )
                    Text(
                        text = friend,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = linkText,
                onValueChange = { linkText = it },
                label = { Text("Link (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = hashtagText,
                onValueChange = { hashtagText = it },
                label = { Text("Hashtag (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = attachLocation,
                        onClick = {
                            if (attachLocation) {
                                attachLocation = false
                            } else {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        },
                        role = Role.Checkbox,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = attachLocation,
                    onCheckedChange = { checked ->
                        if (checked) {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else {
                            attachLocation = false
                        }
                    },
                )
                Text(
                    text = "Attach my location",
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Take Photo")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { choosePhotoLauncher.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose Photo")
            }
            capturedPhotoUri?.let { uri ->
                Spacer(modifier = Modifier.height(8.dp))
                PhotoThumbnail(
                    photoUri = uri,
                    contentDescription = "Attached photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    selectedFriend?.let { friend ->
                        viewModel.sendYo(
                            recipient = friend,
                            link = linkText,
                            hashtag = hashtagText,
                            attachLocation = attachLocation,
                            photoUri = capturedPhotoUri?.toString(),
                        )
                    }
                },
                enabled = selectedFriend != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Yo")
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "History",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(history, key = { it.id }) { message ->
            val extras = buildString {
                append("${message.sender} sent Yo to ${message.recipient}")
                message.link?.let { append(" · $it") }
                message.hashtag?.let { append(" · #$it") }
                if (message.latitude != null && message.longitude != null) {
                    append(" · (${message.latitude}, ${message.longitude})")
                }
            }
            Column {
                Text(extras)
                message.photoUri?.let { photoUri ->
                    PhotoThumbnail(
                        photoUri = Uri.parse(photoUri),
                        contentDescription = "Photo sent to ${message.recipient}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        item(key = "main-screen-groups-section") {
            GroupsSection(
                groups = groups,
                friends = friends,
                onSendYoToGroup = viewModel::sendYoToGroup,
                onCreateGroup = viewModel::createGroup,
            )
        }
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

@Composable
private fun GroupsSection(
    groups: List<Group>,
    friends: List<String>,
    onSendYoToGroup: (String) -> Unit,
    onCreateGroup: (String, List<String>) -> Unit,
) {
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var groupName by rememberSaveable { mutableStateOf("") }
    var selectedMemberUsernames by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(groups) {
        if (groups.none { it.id == selectedGroupId }) {
            selectedGroupId = groups.firstOrNull()?.id
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Groups",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        groups.forEach { group ->
            key(group.id) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = group.id == selectedGroupId,
                            onClick = { selectedGroupId = group.id },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = group.id == selectedGroupId,
                        onClick = null,
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(group.name)
                        Text(
                            text = "${group.memberUsernames.size} members",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { selectedGroupId?.let(onSendYoToGroup) },
            enabled = selectedGroupId != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Yo Group")
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Create group",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = groupName,
            onValueChange = { groupName = it },
            label = { Text("Group name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        friends.forEach { friend ->
            val isSelected = friend in selectedMemberUsernames
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { checked ->
                        selectedMemberUsernames =
                            if (checked) {
                                selectedMemberUsernames + friend
                            } else {
                                selectedMemberUsernames - friend
                            }
                    },
                )
                Text(
                    text = friend,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                onCreateGroup(
                    groupName.trim(),
                    friends.filter { it in selectedMemberUsernames },
                )
                groupName = ""
                selectedMemberUsernames = emptySet()
            },
            enabled = groupName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create Group")
        }
    }
}
