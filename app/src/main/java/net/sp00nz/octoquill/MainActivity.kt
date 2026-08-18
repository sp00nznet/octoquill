package net.sp00nz.octoquill

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Login : Screen
    data object Repos : Screen
    data class Browse(val repo: Repo, val branch: String, val path: String) : Screen
    data class Outline(val repo: Repo, val branch: String, val path: String) : Screen
    data class Edit(val repo: Repo, val branch: String, val path: String) : Screen
    data object Queue : Screen
}

/** Big enough that a phone text field starts to hurt, so open the outline instead. */
const val BIG_FILE_BYTES = 64_000L

private val MARKDOWN = setOf("md", "markdown", "mdown", "mkd")

fun isMarkdown(path: String) = path.substringAfterLast('.', "").lowercase() in MARKDOWN

/** Things you cannot edit in a text field, and never worth caching for offline writing. */
private val MEDIA = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "svg", "pdf", "zip", "gz", "tar",
    "mp3", "mp4", "mov", "wav", "ttf", "otf", "woff", "woff2", "pyc", "class", "jar",
    "so", "dll", "bin", "db", "sqlite", "psd", "heic",
)

fun isMedia(name: String) = name.substringAfterLast('.', "").lowercase() in MEDIA

class Vm(app: Application) : AndroidViewModel(app) {

    // ponytail: token lives in app-private prefs with allowBackup=false. Android's
    // file-based encryption already covers it at rest; reach for EncryptedSharedPreferences
    // only if this ever ships somewhere that isn't true.
    private val prefs = app.getSharedPreferences("octoquill", Context.MODE_PRIVATE)
    private val drafts = Drafts(app)
    private val cache = Cache(app)
    private val outbox = Outbox(app)
    private var gh: Gh? = null

    val stack = mutableStateListOf<Screen>(Screen.Login)
    val screen: Screen get() = stack.last()

    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /** True when the last load came off the disk cache instead of the network. */
    var offline by mutableStateOf(false); private set

    var user by mutableStateOf<User?>(null); private set
    var repos by mutableStateOf(emptyList<Repo>()); private set
    var entries by mutableStateOf(emptyList<Entry>()); private set
    var branches by mutableStateOf(emptyList<String>()); private set

    var homeRepo by mutableStateOf(prefs.getString("home", null)); private set
    var showAll by mutableStateOf(false)

    /** Commits written on the phone that have not reached GitHub yet. */
    var queue by mutableStateOf(emptyList<Pending>()); private set
    val queuedCount: Int get() = queue.size
    val conflictedCount: Int get() = queue.count { it.conflicted }

    // ---- the open file ----------------------------------------------------
    // fullText is the file. `section` picks the slice being edited; `text` is that slice.
    private var fullText by mutableStateOf("")
    private var savedFull by mutableStateOf("")
    private var blobSha by mutableStateOf<String?>(null)
    private var sliceOriginal by mutableStateOf("")
    private var fileDirty by mutableStateOf(false)
    private var openPath by mutableStateOf("")

    var sections by mutableStateOf(emptyList<Section>()); private set
    var section by mutableStateOf<Section?>(null); private set

    var text by mutableStateOf("")
    var preview by mutableStateOf(false)
    var history by mutableStateOf<List<LogEntry>>(emptyList()); private set
    var historyOpen by mutableStateOf(false)

    val dirty: Boolean get() = fileDirty || text != sliceOriginal
    val isNewFile: Boolean get() = blobSha == null

    var pendingDraft by mutableStateOf<String?>(null); private set
    var conflict by mutableStateOf<String?>(null); private set

    var device by mutableStateOf<DeviceCode?>(null); private set
    private var deviceJob: Job? = null
    private var draftJob: Job? = null

    val hasOAuthApp = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()

    private val conn = app.getSystemService(ConnectivityManager::class.java)

    /** Ask the system rather than waiting for a request to fail, so the UI can say so up front. */
    private fun hasNetwork(): Boolean {
        val n = conn?.activeNetwork ?: return false
        val caps = conn.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val onNetwork = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            offline = false
            // signal came back in the woods - land whatever was written without it
            if (queue.isNotEmpty()) job { drain(announce = true) }
        }

        override fun onLost(network: Network) {
            offline = !hasNetwork()
        }
    }

    init {
        refreshQueue()
        runCatching { conn?.registerDefaultNetworkCallback(onNetwork) }
        offline = !hasNetwork()
        prefs.getString("token", null)?.let(::restore)
    }

    private fun job(block: suspend () -> Unit): Job = viewModelScope.launch {
        busy = true
        error = null
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            error = t.readable()
        } finally {
            busy = false
        }
    }

    private fun api(): Gh = gh ?: error("Not signed in")

    /**
     * Network first, disk second. No signal should make the app read-only, not broken.
     * A response with an HTTP status is a real answer and is never masked by the cache.
     */
    private suspend fun cached(
        kind: String,
        repo: String,
        branch: String,
        path: String,
        fetch: suspend () -> String,
    ): String = try {
        fetch().also {
            cache.put(kind, repo, branch, path, it)
            offline = false
        }
    } catch (t: Throwable) {
        val hit = if (t.httpStatus == null) cache.get(kind, repo, branch, path) else null
        if (hit == null) throw t
        offline = true
        hit
    }

    // ---- auth -------------------------------------------------------------

    private suspend fun applyToken(token: String, persist: Boolean) {
        val g = Gh(token.trim())
        val who = try {
            g.me()
        } catch (t: Throwable) {
            g.close(); throw t
        }
        gh?.close()
        gh = g
        user = who
        if (persist) prefs.edit().putString("token", token.trim()).apply()
        stack.clear()
        stack += Screen.Repos
        if (queue.isNotEmpty()) runCatching { drain(announce = true) }
        loadRepos()
        repos.firstOrNull { it.full_name == homeRepo }?.let { enterRepo(it) }
    }

    /** The repo list is cached whole, so a cold start with no signal still gets you in. */
    private suspend fun loadRepos() {
        repos = parseRepos(cached("repos", "", "", "") { encodeRepos(api().repos()) })
    }

    fun signInWithToken(token: String) {
        job { applyToken(token, persist = true) }
    }

    /**
     * Resuming a stored session. A phone opens this app on bad signal all the time, and a
     * dropped request must not look like being signed out - the sign-in screen is useless
     * to someone whose token is already sitting in prefs. Only a 401 really signs you out.
     */
    private fun restore(token: String) {
        job {
            val g = Gh(token)
            gh?.close()
            gh = g
            stack.clear()
            stack += Screen.Repos

            val online = try {
                user = g.me()
                true
            } catch (t: Throwable) {
                if (t.httpStatus == 401) {
                    signOut()
                    throw t
                }
                offline = true
                false
            }

            if (online && queue.isNotEmpty()) runCatching { drain(announce = true) }
            loadRepos()
            repos.firstOrNull { it.full_name == homeRepo }?.let { enterRepo(it) }
        }
    }

    fun deviceSignIn() {
        deviceJob = job {
            val id = BuildConfig.GITHUB_CLIENT_ID
            check(id.isNotBlank()) { "No OAuth client ID compiled in - set octoquill.clientId" }
            val dc = DeviceFlow.start(id)
            device = dc
            try {
                applyToken(DeviceFlow.await(id, dc), persist = true)
            } finally {
                device = null
            }
        }
    }

    fun cancelDeviceSignIn() {
        deviceJob?.cancel()
        deviceJob = null
        device = null
        busy = false
    }

    fun signOut() {
        cancelDeviceSignIn()
        prefs.edit().remove("token").apply()
        gh?.close(); gh = null
        user = null; repos = emptyList(); entries = emptyList(); branches = emptyList()
        stack.clear()
        stack += Screen.Login
        // ponytail: drafts and the outbox deliberately survive sign-out - signing out
        // must never eat writing that has not landed yet
    }

    // ---- navigation -------------------------------------------------------

    fun setHome(fullName: String) {
        homeRepo = if (homeRepo == fullName) null else fullName
        prefs.edit().putString("home", homeRepo).apply()
    }

    private suspend fun loadDir(repo: Repo, branch: String, path: String) {
        entries = parseList(cached("dir", repo.full_name, branch, path) {
            api().contentsJson(repo.full_name, path, branch)
        })
    }

    private suspend fun enterRepo(r: Repo) {
        loadDir(r, r.default_branch, "")
        branches = runCatching { api().branches(r.full_name) }
            .getOrDefault(listOf(r.default_branch))
        stack += Screen.Browse(r, r.default_branch, "")
    }

    fun openRepo(r: Repo) = job { enterRepo(r) }

    /**
     * Pull the whole repo down before you lose signal. Without this you can only read the
     * files you happened to open, which is no use if the plan is to go somewhere with no
     * bars and write. Skips media and anything the contents API will not inline.
     */
    fun syncForOffline() {
        val b = screen as? Screen.Browse ?: return
        job {
            val n = cacheTree(b.repo, b.branch, "")
            notice = "$n files available offline"
        }
    }

    private suspend fun cacheTree(repo: Repo, branch: String, path: String): Int {
        val json = api().contentsJson(repo.full_name, path, branch)
        cache.put("dir", repo.full_name, branch, path, json)

        var n = 0
        for (e in parseList(json)) {
            if (e.type == "dir") {
                n += cacheTree(repo, branch, e.path)
            } else if (!isMedia(e.name) && e.size in 1..MAX_EDITABLE_BYTES) {
                // one bad file should not abandon the rest of the trip
                runCatching {
                    cache.put(
                        "file", repo.full_name, branch, e.path,
                        api().contentsJson(repo.full_name, e.path, branch),
                    )
                    n++
                }
            }
        }
        return n
    }

    fun open(e: Entry) {
        val b = screen as? Screen.Browse ?: return
        job {
            if (e.type == "dir") {
                loadDir(b.repo, b.branch, e.path)
                stack += b.copy(path = e.path)
                return@job
            }

            val (remote, sha) = parseFile(cached("file", b.repo.full_name, b.branch, e.path) {
                api().contentsJson(b.repo.full_name, e.path, b.branch)
            })
            loadFile(b.repo, b.branch, e.path, remote, sha)

            if (sections.size >= 2 && (e.size > BIG_FILE_BYTES || sections.size >= 12)) {
                stack += Screen.Outline(b.repo, b.branch, e.path)
            } else {
                selectSection(null)
                stack += Screen.Edit(b.repo, b.branch, e.path)
            }
        }
    }

    private fun loadFile(repo: Repo, branch: String, path: String, remote: String, sha: String?) {
        openPath = path
        blobSha = sha
        preview = false
        history = emptyList()
        historyOpen = false

        // a commit queued on this phone is newer than anything GitHub can tell us
        val queued = outbox.get(repo.full_name, branch, path)
        val body = queued?.text ?: remote

        fullText = body
        savedFull = body
        fileDirty = false
        sections = if (isMarkdown(path)) parseSections(body) else emptyList()
        pendingDraft = drafts.load(repo.full_name, branch, path)?.takeIf { it != body }
    }

    /** Pick the slice to edit. Null means the whole file. */
    fun selectSection(s: Section?) {
        section = s
        text = if (s == null) fullText else fullText.substring(s.start, s.end)
        sliceOriginal = text
        preview = false
    }

    fun openSection(s: Section?) {
        val o = screen as? Screen.Outline ?: return
        selectSection(s)
        stack += Screen.Edit(o.repo, o.branch, o.path)
    }

    fun newFile(name: String) {
        val b = screen as? Screen.Browse ?: return
        val path = if (b.path.isEmpty()) name.trim('/') else "${b.path}/${name.trim('/')}"
        loadFile(b.repo, b.branch, path, "", null)
        pendingDraft = null
        selectSection(null)
        stack += Screen.Edit(b.repo, b.branch, path)
    }

    fun switchBranch(name: String) {
        val b = screen as? Screen.Browse ?: return
        if (b.branch == name) return
        job {
            loadDir(b.repo, name, b.path)
            for (i in stack.indices) (stack[i] as? Screen.Browse)?.let { stack[i] = it.copy(branch = name) }
        }
    }

    fun refresh() {
        when (val s = screen) {
            is Screen.Repos -> job { loadRepos() }
            is Screen.Browse -> job { loadDir(s.repo, s.branch, s.path) }
            is Screen.Queue -> job { drain(announce = true) }
            else -> Unit
        }
    }

    fun showQueue() {
        refreshQueue()
        // the status strip is tappable from every screen; do not stack up copies of this
        if (screen != Screen.Queue) stack += Screen.Queue
    }

    fun back() {
        if (stack.size <= 1) return
        if (screen is Screen.Edit) spliceBack()
        flushDraft()
        stack.removeAt(stack.lastIndex)
        (screen as? Screen.Browse)?.let { s -> job { loadDir(s.repo, s.branch, s.path) } }
    }

    // ---- editing ----------------------------------------------------------

    /** Fold the edited slice back into the file and re-derive the outline. */
    private fun spliceBack() {
        val s = section
        if (s != null && text != sliceOriginal) {
            fullText = splice(fullText, s, text)
            fileDirty = fullText != savedFull
            if (isMarkdown(openPath)) sections = parseSections(fullText)
            sliceOriginal = text
        } else if (s == null && text != sliceOriginal) {
            fullText = text
            fileDirty = fullText != savedFull
            if (isMarkdown(openPath)) sections = parseSections(fullText)
            sliceOriginal = text
        }
        section = null
    }

    /** The whole file as it stands, including the slice currently being edited. */
    private fun composed(): String {
        val s = section ?: return if (screen is Screen.Edit) text else fullText
        return splice(fullText, s, text)
    }

    private fun openTarget(): Triple<String, String, String>? = when (val s = screen) {
        is Screen.Edit -> Triple(s.repo.full_name, s.branch, s.path)
        is Screen.Outline -> Triple(s.repo.full_name, s.branch, s.path)
        else -> null
    }

    /** Every keystroke updates the buffer; a debounced write mirrors the file to disk. */
    fun onTextChange(v: String) {
        text = v
        val (repo, branch, path) = openTarget() ?: return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(600)
            // ponytail: writes the whole file each time, so there is one draft per file
            // however you reached it. Fine for prose; revisit for megabyte files.
            drafts.save(repo, branch, path, composed())
        }
    }

    /** Called when the app is backgrounded - no debounce, write it now. */
    fun flushDraft() {
        val (repo, branch, path) = openTarget() ?: return
        draftJob?.cancel()
        if (dirty || isNewFile) drafts.save(repo, branch, path, composed())
    }

    fun restoreDraft() {
        val d = pendingDraft ?: return
        pendingDraft = null
        fullText = d
        savedFull = d
        fileDirty = true
        if (isMarkdown(openPath)) sections = parseSections(d)
        selectSection(if (screen is Screen.Edit) null else null)
    }

    fun discardDraft() {
        pendingDraft = null
        val (repo, branch, path) = openTarget() ?: return
        drafts.clear(repo, branch, path)
    }

    fun loadHistory() {
        val (repo, branch, path) = openTarget() ?: return
        historyOpen = true
        job { history = api().history(repo, path, branch) }
    }

    fun closeHistory() {
        historyOpen = false
        history = emptyList()
    }

    // ---- file operations --------------------------------------------------

    fun deleteFile(e: Entry) {
        val b = screen as? Screen.Browse ?: return
        job {
            api().delete(b.repo.full_name, e.path, "Delete ${e.name}", b.branch, e.sha)
            drafts.clear(b.repo.full_name, b.branch, e.path)
            notice = "Deleted ${e.name}"
            loadDir(b.repo, b.branch, b.path)
        }
    }

    /**
     * Rename is create-then-delete, so it lands as two commits. One commit would mean
     * hand-building a tree through the Git Data API for something that happens rarely.
     * Needs the network - it reads the old blob and writes two commits.
     */
    fun renameFile(e: Entry, newName: String) {
        val b = screen as? Screen.Browse ?: return
        val target = if (b.path.isEmpty()) newName.trim('/') else "${b.path}/${newName.trim('/')}"
        if (target == e.path || newName.isBlank()) return
        job {
            val (body, sha) = api().read(b.repo.full_name, e.path, b.branch)
            api().commit(b.repo.full_name, target, body, "Rename ${e.name} to $newName", b.branch, null)
            api().delete(b.repo.full_name, e.path, "Rename ${e.name} to $newName (remove old)", b.branch, sha)
            drafts.clear(b.repo.full_name, b.branch, e.path)
            notice = "Renamed to $newName"
            loadDir(b.repo, b.branch, b.path)
        }
    }

    // ---- committing -------------------------------------------------------

    private fun refreshQueue() {
        queue = outbox.all()
    }

    /**
     * Queue the commit, then try to land it. Same path with or without signal - in a dead
     * spot the commit simply waits on disk and goes out when the network returns.
     */
    fun commit(message: String) {
        val (repo, branch, path) = openTarget() ?: return
        val body = composed()
        outbox.put(
            Pending(
                repo = repo,
                branch = branch,
                path = path,
                message = message.ifBlank { "Update ${path.substringAfterLast('/')}" },
                baseSha = blobSha,
                text = body,
                queuedAt = System.currentTimeMillis(),
            )
        )
        drafts.clear(repo, branch, path)
        fullText = body
        savedFull = body
        sliceOriginal = text
        fileDirty = false
        refreshQueue()
        back()
        job { drain(announce = true) }
    }

    fun syncNow() = job { drain(announce = true) }

    /**
     * Push everything queued. Stops at the first network failure - if there is no signal
     * for one commit there is none for the next, and hammering it wastes battery.
     */
    private suspend fun drain(announce: Boolean) {
        if (gh == null) return
        var landed = 0
        for (p in outbox.all()) {
            if (p.conflicted) continue
            try {
                val c = api().commit(p.repo, p.path, p.text, p.message, p.branch, p.baseSha)
                outbox.remove(p)
                landed++
                if (announce) notice = "Pushed ${c.sha.take(7)} to ${p.branch}"
                offline = false
            } catch (t: Throwable) {
                when (t.httpStatus) {
                    409, 422 -> {
                        outbox.put(p.copy(conflicted = true))
                        if (announce) notice = "${p.name} changed on GitHub - open Pending to resolve"
                    }

                    null -> {
                        offline = true
                        if (announce && landed == 0) notice = "No signal - queued to push later"
                        refreshQueue()
                        return
                    }

                    else -> {
                        refreshQueue()
                        throw t
                    }
                }
            }
        }
        refreshQueue()
        if (announce && landed > 1) notice = "Pushed $landed commits"
    }

    /** Resolve a conflicted queue entry by writing over whatever is on the branch now. */
    fun resolveOverwrite(p: Pending) {
        job {
            val fresh = api().shaOf(p.repo, p.path, p.branch)
            val c = api().commit(p.repo, p.path, p.text, p.message, p.branch, fresh)
            outbox.remove(p)
            refreshQueue()
            notice = "Pushed ${c.sha.take(7)} to ${p.branch}"
        }
    }

    fun discardPending(p: Pending) {
        outbox.remove(p)
        refreshQueue()
        notice = "Discarded ${p.name}"
    }

    fun dismissConflict() {
        conflict = null
    }

    override fun onCleared() {
        flushDraft()
        runCatching { conn?.unregisterNetworkCallback(onNetwork) }
        gh?.close()
    }
}

class MainActivity : ComponentActivity() {

    private val vm: Vm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OctoquillTheme { App(vm) } }
    }

    /** Last chance to get the buffer onto disk before Android is free to kill us. */
    override fun onStop() {
        super.onStop()
        vm.flushDraft()
    }
}

@Composable
fun OctoquillTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
