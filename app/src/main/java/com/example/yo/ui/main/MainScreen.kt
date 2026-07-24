package com.example.yo.ui.main

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val friendsLoadFailed by viewModel.friendsLoadFailed.collectAsState()
    var selectedFriend by remember { mutableStateOf<String?>(null) }
    var linkText by remember { mutableStateOf("") }
    var hashtagText by remember { mutableStateOf("") }
    var attachLocation by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        attachLocation = granted
    }

    LaunchedEffect(friends) {
        if (selectedFriend !in friends) {
            selectedFriend = friends.firstOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
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
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                selectedFriend?.let { friend ->
                    viewModel.sendYo(
                        recipient = friend,
                        link = linkText,
                        hashtag = hashtagText,
                        attachLocation = attachLocation,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = history,
                key = { message -> message.id },
            ) { message ->
                val extras = buildString {
                    append("${message.sender} sent Yo to ${message.recipient}")
                    message.link?.let { append(" · $it") }
                    message.hashtag?.let { append(" · #$it") }
                    if (message.latitude != null && message.longitude != null) {
                        append(" · (${message.latitude}, ${message.longitude})")
                    }
                }
                Text(extras)
            }
        }
    }
}
