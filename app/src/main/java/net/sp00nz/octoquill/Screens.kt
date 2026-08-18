package net.sp00nz.octoquill

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Things you cannot edit in a text field. Hidden unless "show everything" is on. */
private val MEDIA = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "svg", "pdf", "zip", "gz", "tar",
    "mp3", "mp4", "mov", "wav", "ttf", "otf", "woff", "woff2", "pyc", "class", "jar",
    "so", "dll", "bin", "db", "sqlite", "psd", "heic",
)

private fun isMedia(name: String) = name.substringAfterLast('.', "").lowercase() in MEDIA

/** Word count for prose. One pass, cheap enough to run per keystroke. */
private fun wordCount(s: String): Int {
    var n = 0
    var inWord = false
    for (c in s) {
        if (c.isWhitespace()) inWord = false
        else if (!inWord) {
            inWord = true; n++
        }
    }
    return n
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: Vm) {
    val snack = remember { SnackbarHostState() }
    var confirmLeave by remember { mutableStateOf(false) }
    var commitOpen by remember { mutableStateOf(false) }
    var newFileOpen by remember { mutableStateOf(false) }

    fun leave() = if (vm.screen is Screen.Edit && vm.dirty) confirmLeave = true else vm.back()

    BackHandler(enabled = vm.stack.size > 1) { leave() }
    LaunchedEffect(vm.error) { vm.error?.let { snack.showSnackbar(it); vm.error = null } }
    LaunchedEffect(vm.notice) { vm.notice?.let { snack.showSnackbar(it); vm.notice = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) { Snackbar(it) } },
        topBar = {
            when (val s = vm.screen) {
                Screen.Login -> Unit

                Screen.Repos -> TopAppBar(
                    title = { Text(vm.user?.login ?: "Repositories") },
                    actions = {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(onClick = vm::signOut) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, "Sign out")
                        }
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
                    navigationIcon = {
                        IconButton(onClick = { leave() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        BranchPicker(vm, s.branch)
                        IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    },
                )

                is Screen.Edit -> {
                    val words = remember(vm.text) { wordCount(vm.text) }
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    s.path.substringAfterLast('/') + if (vm.dirty) " •" else "",
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
                        navigationIcon = {
                            IconButton(onClick = { leave() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            when (vm.screen) {
                is Screen.Browse -> FloatingActionButton(onClick = { newFileOpen = true }) {
                    Icon(Icons.Default.Add, "New file")
                }

                is Screen.Edit -> if (vm.dirty || vm.isNewFile) {
                    FloatingActionButton(onClick = { commitOpen = true }) {
                        Icon(Icons.Default.Check, "Commit")
                    }
                } else Unit

                else -> Unit
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Repos -> RepoList(vm)
                is Screen.Browse -> Browser(vm)
                is Screen.Edit -> Editor(vm)
            }
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Leave without committing?") },
        text = {
            Text(
                "Your edits stay saved on this phone and will be here when you come back. " +
                    "They are just not on GitHub yet."
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
                    "This file was committed somewhere else — the web editor, or a laptop — " +
                        "since you opened it. Overwriting replaces that version with yours; " +
                        "it stays in the repo history either way. Your text is saved regardless."
                )
            },
            confirmButton = { TextButton(onClick = vm::overwrite) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = vm::dismissConflict) { Text("Cancel") } },
        )
    }

    if (commitOpen) {
        val s = vm.screen as? Screen.Edit
        CommitDialog(
            default = if (vm.isNewFile) "Add ${s?.path?.substringAfterLast('/')}"
            else "Update ${s?.path?.substringAfterLast('/')}",
            branch = s?.branch.orEmpty(),
            onDismiss = { commitOpen = false },
            onCommit = { commitOpen = false; vm.commit(it) },
        )
    }

    if (newFileOpen) NewFileDialog(
        onDismiss = { newFileOpen = false },
        onCreate = { newFileOpen = false; vm.newFile(it) },
    )
}

@Composable
private fun LoginScreen(vm: Vm) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var pat by remember { mutableStateOf("") }
    val open = { url: String -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Octoquill", style = MaterialTheme.typography.displaySmall)
        Text(
            "Write, commit, push",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        val dc = vm.device
        if (dc != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
                        OutlinedButton(onClick = {
                            clip.setText(AnnotatedString(dc.user_code))
                        }) { Text("Copy code") }
                        Button(onClick = { open(dc.verification_uri) }) { Text("Open GitHub") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Waiting for you to approve…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = vm::cancelDeviceSignIn) { Text("Cancel") }
                }
            }
            return@Column
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
            onClick = { vm.signInWithToken(pat) },
            enabled = pat.isNotBlank() && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use token") }
        TextButton(onClick = {
            open("https://github.com/settings/tokens/new?scopes=repo&description=Octoquill")
        }) { Text("Create a token on GitHub") }
    }
}

@Composable
private fun RepoList(vm: Vm) {
    var q by remember { mutableStateOf("") }
    val shown = remember(q, vm.repos) {
        if (q.isBlank()) vm.repos
        else vm.repos.filter { it.full_name.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("Filter") },
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
                    trailingContent = {
                        // star a repo and the app opens straight into it next launch
                        IconButton(onClick = { vm.setHome(r.full_name) }) {
                            Text(if (vm.homeRepo == r.full_name) "★" else "☆")
                        }
                    },
                    modifier = Modifier.clickable { vm.openRepo(r) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Browser(vm: Vm) {
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
                                "${e.size / 1024} KB" +
                                    if (e.size > BIG_FILE_BYTES) " · large" else "",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else null,
                    modifier = Modifier.clickable { vm.open(e) },
                )
                HorizontalDivider()
            }
        }
        if (hidden > 0 || vm.showAll) {
            TextButton(
                onClick = { vm.showAll = !vm.showAll },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (vm.showAll) "Hide images and binaries"
                    else "$hidden hidden — show everything"
                )
            }
        }
    }
}

@Composable
private fun Editor(vm: Vm) {
    Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        BasicTextField(
            value = vm.text,
            onValueChange = vm::onTextChange,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (vm.text.isEmpty()) {
                    Text(
                        "Empty file — start writing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
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
                DropdownMenuItem(
                    text = { Text(b) },
                    onClick = { open = false; vm.switchBranch(b) },
                )
            }
        }
    }
}

@Composable
private fun CommitDialog(
    default: String,
    branch: String,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    var msg by remember { mutableStateOf(default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit to $branch") },
        text = {
            OutlinedTextField(
                value = msg,
                onValueChange = { msg = it },
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCommit(msg) }, enabled = msg.isNotBlank()) {
                Text("Commit & push")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewFileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New file") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Filename") },
                placeholder = { Text("napa-rewrite.md") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
