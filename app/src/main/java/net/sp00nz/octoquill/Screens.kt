package net.sp00nz.octoquill

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Short notes are common in a writing repo, and "0 KB" tells you nothing. */
private fun humanSize(bytes: Long) = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: Vm) {
    val snack = remember { SnackbarHostState() }
    var confirmLeave by remember { mutableStateOf(false) }
    var commitOpen by remember { mutableStateOf(false) }
    var newFileOpen by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<Entry?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    var unlinking by remember { mutableStateOf<Linked?>(null) }

    fun leave() = if (vm.screen is Screen.Edit && vm.dirty) confirmLeave = true else vm.back()

    BackHandler(enabled = vm.stack.size > 1) { leave() }
    LaunchedEffect(vm.error) { vm.error?.let { snack.showSnackbar(it); vm.error = null } }
    LaunchedEffect(vm.notice) { vm.notice?.let { snack.showSnackbar(it); vm.notice = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) { Snackbar(it) } },
        topBar = { TopBar(vm, ::leave) },
        floatingActionButton = {
            when (vm.screen) {
                Screen.Home -> FloatingActionButton(onClick = vm::startAdd) {
                    Icon(Icons.Default.Add, "Add a repo")
                }

                is Screen.Browse -> FloatingActionButton(onClick = { newFileOpen = true }) {
                    Icon(Icons.Default.Add, "New file")
                }

                is Screen.Edit, is Screen.Outline -> if (vm.dirty || vm.isNewFile) {
                    FloatingActionButton(onClick = { commitOpen = true }) {
                        Icon(Icons.Default.Check, "Commit")
                    }
                } else Unit

                else -> Unit
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.offline || vm.queuedCount > 0) StatusStrip(vm)
            Box(Modifier.fillMaxSize()) {
                when (vm.screen) {
                    Screen.Home -> HomeScreen(vm) { unlinking = it }
                    Screen.Add -> AddScreen(vm)
                    Screen.Queue -> QueueScreen(vm)
                    is Screen.Browse -> Browser(vm) { acting = it }
                    is Screen.Outline -> Outline(vm)
                    is Screen.Edit -> Editor(vm)
                    is Screen.Search -> SearchScreen(vm)
                }
                if (vm.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }

    if (vm.historyOpen) HistoryDialog(vm)
    vm.comparing?.let { (p, diff) -> CompareDialog(vm, p, diff) }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Leave without committing?") },
        text = {
            Text(
                "Your edits stay saved on this phone and will be here when you come back. " +
                    "They are just not committed yet."
            )
        },
        confirmButton = {
            TextButton(onClick = { confirmLeave = false; vm.back() }) { Text("Leave") }
        },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
    )

    vm.pendingDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Unsaved writing found") },
            text = {
                Text(
                    "You edited this file on the phone and never committed it " +
                        "(${wordCount(draft)} words). Keep that, or start from what is on GitHub?"
                )
            },
            confirmButton = { TextButton(onClick = vm::restoreDraft) { Text("Keep mine") } },
            dismissButton = { TextButton(onClick = vm::discardDraft) { Text("Use GitHub version") } },
        )
    }

    vm.conflict?.let {
        AlertDialog(
            onDismissRequest = vm::dismissConflict,
            title = { Text("Changed on GitHub") },
            text = {
                Text(
                    "This file was committed somewhere else since you opened it. " +
                        "Your version is queued and safe - open Pending to overwrite or discard it."
                )
            },
            confirmButton = { TextButton(onClick = vm::dismissConflict) { Text("OK") } },
        )
    }

    if (commitOpen) {
        val path = when (val s = vm.screen) {
            is Screen.Edit -> s.path
            is Screen.Outline -> s.path
            else -> ""
        }
        val branch = when (val s = vm.screen) {
            is Screen.Edit -> s.branch
            is Screen.Outline -> s.branch
            else -> ""
        }
        CommitDialog(
            default = if (vm.isNewFile) "Add ${path.substringAfterLast('/')}"
            else "Update ${path.substringAfterLast('/')}",
            branch = branch,
            offline = vm.offline,
            onDismiss = { commitOpen = false },
            onCommit = { commitOpen = false; vm.commit(it) },
        )
    }

    if (newFileOpen) TextPromptDialog(
        title = "New file",
        label = "Filename",
        placeholder = "notes.md",
        initial = "",
        confirm = "Create",
        onDismiss = { newFileOpen = false },
        onConfirm = { newFileOpen = false; vm.newFile(it) },
    )

    acting?.let { e ->
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text(e.name) },
            text = { Text("What do you want to do with this file?") },
            confirmButton = {
                TextButton(onClick = { renaming = e; acting = null }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = e; acting = null }) { Text("Delete") }
            },
        )
    }

    renaming?.let { e ->
        TextPromptDialog(
            title = "Rename",
            label = "New filename",
            placeholder = e.name,
            initial = e.name,
            confirm = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { renaming = null; vm.renameFile(e, it) },
        )
    }

    unlinking?.let { l ->
        val waiting = vm.queue.count { it.repo == l.repo.full_name }
        AlertDialog(
            onDismissRequest = { unlinking = null },
            title = { Text("Remove ${l.repo.name}?") },
            text = {
                Text(
                    "Takes it off this list. Nothing on GitHub changes." +
                        if (waiting > 0) " Its $waiting unsent commit(s) stay on the phone and " +
                            "push once you add it back." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = { unlinking = null; vm.unlink(l) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { unlinking = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${e.name}?") },
            text = { Text("It stays in the repo history, but it goes from the branch. This needs signal.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; vm.deleteFile(e) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(vm: Vm, leave: () -> Unit) {
    val backButton: @Composable () -> Unit = {
        IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }

    when (val s = vm.screen) {
        Screen.Home -> TopAppBar(
            title = { Text("Octoquill") },
            actions = { QueueAction(vm) },
        )

        Screen.Add -> TopAppBar(
            title = { Text(if (vm.addToken == null) "Add a repo" else "Pick a repo") },
            navigationIcon = backButton,
        )

        Screen.Queue -> TopAppBar(
            title = { Text("Pending") },
            navigationIcon = backButton,
            actions = {
                IconButton(onClick = vm::syncNow) { Icon(Icons.Default.Refresh, "Push now") }
            },
        )

        is Screen.Browse -> TopAppBar(
            title = {
                Column {
                    Text(s.repo.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "/" + s.path,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = backButton,
            actions = {
                QueueAction(vm)
                BranchPicker(vm, s.branch)
                IconButton(onClick = vm::openSearch) { Icon(Icons.Default.Search, "Search") }
                IconButton(onClick = vm::syncForOffline) {
                    Icon(Icons.Default.Done, "Save repo for offline")
                }
                IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
            },
        )

        is Screen.Search -> TopAppBar(
            title = { Text("Search ${s.repo.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = backButton,
        )

        is Screen.Outline -> TopAppBar(
            title = {
                Column {
                    Text(s.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${vm.sections.size} sections" + if (vm.dirty) " - uncommitted" else "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            navigationIcon = backButton,
            actions = {
                IconButton(onClick = vm::loadHistory) {
                    Icon(Icons.AutoMirrored.Filled.List, "History")
                }
            },
        )

        is Screen.Edit -> {
            val words = remember(vm.text) { wordCount(vm.text) }
            TopAppBar(
                title = {
                    Column {
                        Text(
                            (vm.section?.title ?: s.path.substringAfterLast('/')) +
                                if (vm.dirty) " " + "•" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${s.branch} · $words words",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = backButton,
                actions = {
                    if (isMarkdown(s.path)) {
                        TextButton(onClick = { vm.preview = !vm.preview }) {
                            Text(if (vm.preview) "Edit" else "Read")
                        }
                    }
                    IconButton(onClick = vm::loadHistory) {
                        Icon(Icons.AutoMirrored.Filled.List, "History")
                    }
                },
            )
        }
    }
}

@Composable
private fun QueueAction(vm: Vm) {
    if (vm.queuedCount == 0) return
    BadgedBox(badge = { Badge { Text("${vm.queuedCount}") } }) {
        IconButton(onClick = vm::showQueue) {
            Icon(Icons.Default.Check, "Pending commits")
        }
    }
}

/** One line that says whether your writing is safe and where it currently lives. */
@Composable
private fun StatusStrip(vm: Vm) {
    val error = vm.conflictedCount > 0 || vm.strandedCount > 0
    val bg = if (error) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

    val msg = when {
        vm.conflictedCount > 0 -> "${vm.conflictedCount} need attention - tap Pending"
        vm.strandedCount > 0 -> "${vm.strandedCount} commit(s) wait for their repo to be added again"
        vm.queuedCount > 0 && vm.offline -> "No signal - ${vm.queuedCount} commit(s) saved, will push automatically"
        vm.queuedCount > 0 -> "${vm.queuedCount} commit(s) waiting to push"
        else -> "No signal - showing what was last synced"
    }

    Row(
        Modifier.fillMaxWidth().background(bg).clickable { vm.showQueue() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Repos on this phone. Needs no network, so it is what you see the moment the app opens. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(vm: Vm, onLongPress: (Linked) -> Unit) {
    if (vm.linked.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Octoquill", style = MaterialTheme.typography.displaySmall)
            Text(
                "Write, commit, push",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = vm::startAdd) { Text("Add a repo") }
            if (vm.queuedCount > 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "${vm.queuedCount} commit(s) are still saved on this phone - add their repo " +
                        "back and they push.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(vm.linked, key = { it.repo.full_name }) { l ->
            val waiting = vm.queue.count { it.repo == l.repo.full_name }
            ListItem(
                headlineContent = { Text(l.repo.full_name) },
                supportingContent = {
                    Text(
                        when {
                            l.rejected -> "GitHub refused this token - tap + to add it again"
                            waiting > 0 -> "$waiting commit(s) waiting to push"
                            else -> l.repo.description ?: l.repo.default_branch
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (l.rejected) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.combinedClickable(
                    onClick = { vm.openRepo(l.repo) },
                    onLongClick = { onLongPress(l) },
                ),
            )
            HorizontalDivider()
        }
    }
}

/** Step one: a sign-in (saved, device flow, or a pasted token). Step two: pick a repo it reaches. */
@Composable
private fun AddScreen(vm: Vm) {
    if (vm.addToken != null) {
        RepoPicker(vm)
        return
    }

    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var pat by remember { mutableStateOf("") }
    val open = { url: String -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val dc = vm.device
        if (dc != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Enter this code at", style = MaterialTheme.typography.bodyMedium)
                    Text(dc.verification_uri, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        dc.user_code,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clip.setText(AnnotatedString(dc.user_code)) }) {
                            Text("Copy code")
                        }
                        Button(onClick = { open(dc.verification_uri) }) { Text("Open GitHub") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Waiting for you to approve...", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = vm::cancelDeviceSignIn) { Text("Cancel") }
                }
            }
            return@Column
        }

        vm.savedSignIns.forEach { (token, label) ->
            OutlinedButton(
                onClick = { vm.useToken(token) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Same sign-in as $label", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(8.dp))
        }
        if (vm.savedSignIns.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("or a new one", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }

        if (vm.hasOAuthApp) {
            Button(
                onClick = vm::deviceSignIn,
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in with GitHub") }
            Spacer(Modifier.height(20.dp))
            Text("or", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }

        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it },
            label = { Text("Personal access token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.useToken(pat) },
            enabled = pat.isNotBlank() && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use token") }
        TextButton(onClick = {
            open("https://github.com/settings/tokens/new?scopes=repo&description=Octoquill")
        }) { Text("Create a token on GitHub") }
    }
}

@Composable
private fun RepoPicker(vm: Vm) {
    var q by remember { mutableStateOf("") }
    val onList = vm.linked.map { it.repo.full_name }.toSet()
    val shown = remember(q, vm.candidates) {
        if (q.isBlank()) vm.candidates
        else vm.candidates.filter { it.full_name.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("Filter ${vm.addLogin}'s repos") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.full_name }) { r ->
                ListItem(
                    headlineContent = { Text(r.full_name) },
                    supportingContent = r.description?.let {
                        { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    },
                    trailingContent = if (r.full_name in onList) {
                        { Text("added", style = MaterialTheme.typography.labelSmall) }
                    } else null,
                    modifier = Modifier.clickable { vm.link(r) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Browser(vm: Vm, onLongPress: (Entry) -> Unit) {
    val shown = if (vm.showAll) vm.entries
    else vm.entries.filter { it.type == "dir" || !isMedia(it.name) }
    val hidden = vm.entries.size - shown.size

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.path }) { e ->
                ListItem(
                    leadingContent = { Text(if (e.type == "dir") "📁" else "📄") },
                    headlineContent = { Text(e.name) },
                    supportingContent = if (e.type == "file") {
                        {
                            Text(
                                humanSize(e.size) +
                                    if (e.size > BIG_FILE_BYTES) " · large" else "",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else null,
                    modifier = Modifier.combinedClickable(
                        onClick = { vm.open(e) },
                        onLongClick = { if (e.type == "file") onLongPress(e) },
                    ),
                )
                HorizontalDivider()
            }
        }
        if (hidden > 0 || vm.showAll) {
            TextButton(onClick = { vm.showAll = !vm.showAll }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (vm.showAll) "Hide images and binaries"
                    else "$hidden hidden - show everything"
                )
            }
        }
    }
}

@Composable
private fun SearchScreen(vm: Vm) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::search,
            label = { Text("Search saved files") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            "Looks through files saved on this phone, so it works without signal. " +
                "Tap Save for offline in the repo first to search all of it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.hits) { h ->
                ListItem(
                    headlineContent = { Text(h.entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (h.line > 0) {
                        { Text("${h.line}: ${h.snippet}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    } else null,
                    modifier = Modifier.clickable { vm.openHit(h) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun BranchPicker(vm: Vm, current: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(current, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vm.branches.forEach { b ->
                DropdownMenuItem(text = { Text(b) }, onClick = { open = false; vm.switchBranch(b) })
            }
        }
    }
}

@Composable
private fun CommitDialog(
    default: String,
    branch: String,
    offline: Boolean,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    var msg by remember { mutableStateOf(default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit to $branch") },
        text = {
            Column {
                OutlinedTextField(
                    value = msg,
                    onValueChange = { msg = it },
                    label = { Text("Commit message") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (offline) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No signal right now - this is saved on the phone and pushes itself " +
                            "the moment you get a bar.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCommit(msg) }, enabled = msg.isNotBlank()) {
                Text(if (offline) "Commit" else "Commit & push")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    placeholder: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
