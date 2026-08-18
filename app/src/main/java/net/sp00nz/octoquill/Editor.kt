package net.sp00nz.octoquill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The outline of a long document. A phone text field cannot hold a whole manuscript
 * comfortably, so pick a heading and edit only that slice; it is spliced back exactly.
 */
@Composable
fun Outline(vm: Vm) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text("Whole file") },
                supportingContent = {
                    Text(
                        "${vm.sections.size} sections - may be slow to edit at once",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.clickable { vm.openSection(null) },
            )
            HorizontalDivider()
        }
        items(vm.sections, key = { "${it.start}:${it.title}" }) { s ->
            ListItem(
                headlineContent = {
                    Text(
                        s.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = when (s.level) {
                            1 -> MaterialTheme.typography.titleMedium
                            2 -> MaterialTheme.typography.bodyLarge
                            else -> MaterialTheme.typography.bodyMedium
                        },
                    )
                },
                supportingContent = {
                    Text(
                        "${s.end - s.start} chars",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingContent = if (s.level > 1) {
                    { Text("  ".repeat(s.level - 1) + "·") }
                } else null,
                modifier = Modifier.clickable { vm.openSection(s) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun Editor(vm: Vm) {
    if (vm.preview) {
        val scheme = MaterialTheme.colorScheme
        val rendered = remember(vm.text, scheme) {
            renderMarkdown(vm.text, scheme.onSurface, scheme.primary, scheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(rendered, Modifier.fillMaxWidth().padding(16.dp), lineHeight = 22.sp)
        }
        return
    }

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
                        "Empty file - start writing",
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
fun HistoryDialog(vm: Vm) {
    AlertDialog(
        onDismissRequest = vm::closeHistory,
        title = { Text("History") },
        text = {
            if (vm.history.isEmpty()) {
                Text(if (vm.busy) "Loading..." else "No commits found for this file.")
            } else {
                LazyColumn {
                    items(vm.history, key = { it.sha }) { c ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                c.commit.message.lineSequence().first(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row {
                                Text(
                                    c.sha.take(7),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "  " + (c.commit.author?.date?.take(10) ?: "") +
                                        "  " + (c.commit.author?.name ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeHistory) { Text("Close") } },
    )
}

/** Commits written on the phone that have not landed yet. */
@Composable
fun QueueScreen(vm: Vm) {
    if (vm.queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
                "Nothing waiting - everything you wrote is on GitHub.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(vm.queue, key = { it.repo + it.branch + it.path }) { p ->
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = {
                    Column {
                        Text(p.message, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        Text(
                            if (p.conflicted) "changed on GitHub since you wrote this"
                            else "${wordCount(p.text)} words waiting for signal",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (p.conflicted) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingContent = {
                    Row {
                        if (p.conflicted) {
                            TextButton(onClick = { vm.resolveOverwrite(p) }) { Text("Overwrite") }
                        }
                        TextButton(onClick = { vm.discardPending(p) }) { Text("Discard") }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}
