package com.tonyisup.poseguidesnap.ui.shoots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ShootListScreen(
    state: ShootListUiState,
    onRetry: () -> Unit,
    onCreate: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (ShootListItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Your shoots",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            ShootListUiState.Loading -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { contentDescription = "Loading shoots" },
                ) {
                    CircularProgressIndicator()
                    Text("Loading shoots")
                }
            }
            ShootListUiState.Unavailable -> {
                Text("Shoot list unavailable. Try again.")
                Button(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Retry")
                }
            }
            is ShootListUiState.Loaded -> LoadedShootList(
                data = state.data,
                onCreate = onCreate,
                onLoadMore = onLoadMore,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun LoadedShootList(
    data: ShootListData,
    onCreate: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (ShootListItem) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    LaunchedEffect(data.createStatus) {
        if (data.createStatus == ShootListCreateStatus.Succeeded) name = ""
    }
    OutlinedTextField(
        value = name,
        onValueChange = { candidate -> if (candidate.length <= 200) name = candidate },
        label = { Text("Shoot name") },
        enabled = data.createStatus !is ShootListCreateStatus.Pending,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onCreate(name.trim()) },
        enabled = name.trim().isNotEmpty() && data.createStatus !is ShootListCreateStatus.Pending,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(if (data.createStatus is ShootListCreateStatus.Pending) "Creating" else "Create")
    }
    when (data.createStatus) {
        ShootListCreateStatus.Succeeded -> Text("Shoot created.")
        ShootListCreateStatus.Rejected -> Text("Enter a valid shoot name.")
        ShootListCreateStatus.Unavailable -> Text("Could not create shoot. Try again.")
        ShootListCreateStatus.Idle,
        is ShootListCreateStatus.Pending,
        -> Unit
    }

    if (data.items.isEmpty()) {
        Text("No shoots yet. Create one to begin building a pose playlist.")
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(data.items) { item ->
                Button(
                    onClick = { onOpen(item) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription =
                                "Open shoot ${item.name}, ${item.referenceCount} references, ${item.lifecycleText}"
                        },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(item.name)
                        Text("${item.referenceCount} references · ${item.lifecycleText}")
                    }
                }
            }
            if (data.hasMore) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (data.pageStatus) {
                            ShootListPageStatus.LoadingMore -> Text("Loading more shoots")
                            ShootListPageStatus.Unavailable ->
                                Text("Could not load more shoots. Try again.")
                            ShootListPageStatus.Idle -> Unit
                        }
                        Button(
                            onClick = onLoadMore,
                            enabled = data.pageStatus != ShootListPageStatus.LoadingMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("Load more shoots")
                        }
                    }
                }
            }
        }
    }
}
