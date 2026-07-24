package com.example.yo.ui.main

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

// Local friend picker stub; no contacts backend is wired yet.
private val friends = listOf("Alice", "Bob", "Charlie")

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsState()
    var selectedFriend by remember { mutableStateOf(friends.first()) }

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
        Button(
            onClick = { viewModel.sendYo(selectedFriend) },
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
                Text("${message.sender} sent Yo to ${message.recipient}")
            }
        }
    }
}
