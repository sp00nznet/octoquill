package net.sp00nz.octoquill

import android.content.Context
import java.io.File

/**
 * Prose typed on a phone has to survive the process being killed — Android will do that
 * the moment you switch apps on a tight-memory device, and an uncommitted chapter is gone
 * with it. The editor buffer is mirrored to disk as you type and only cleared once the
 * commit actually lands on GitHub.
 */
class Drafts(ctx: Context) {

    private val dir = File(ctx.filesDir, "drafts").apply { mkdirs() }

    private fun file(repo: String, branch: String, path: String): File {
        val raw = "$repo@$branch:$path"
        // readable name for `adb shell`, hash suffix so two long paths can't collide
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
        return File(dir, "$safe-${raw.hashCode().toUInt().toString(16)}.txt")
    }

    fun save(repo: String, branch: String, path: String, text: String) {
        runCatching { file(repo, branch, path).writeText(text) }
    }

    fun load(repo: String, branch: String, path: String): String? =
        file(repo, branch, path).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(repo: String, branch: String, path: String) {
        runCatching { file(repo, branch, path).delete() }
    }
}
