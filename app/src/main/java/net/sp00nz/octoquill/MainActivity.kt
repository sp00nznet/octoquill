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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data class Browse(val repo: Repo, val branch: String, val path: String) : Screen
    data class Outline(val repo: Repo, val branch: String, val path: String) : Screen
    data class Edit(val repo: Repo, val branch: String, val path: String) : Screen
    data class Search(val repo: Repo, val branch: String) : Screen
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

/** A search match. [line] is 1-based, or 0 when it was the filename that matched. */
data class Hit(val entry: Entry, val line: Int, val snippet: String)

private const val MAX_HITS = 200

class Vm(app: Application) : AndroidViewModel(app) {

    // ponytail: token lives in app-private prefs with allowBackup=false. Android's
    // file-based encryption already covers it at rest; reach for EncryptedSharedPreferences
    // only if this ever ships somewhere that isn't true.
    private val prefs = app.getSharedPreferences("octoquill", Context.MODE_PRIVATE)
    private val drafts = Drafts(app)
    private val cache = Cache(app)
    private val outbox = Outbox(app)
    // one client per distinct token; repos that share a sign-in share a client
    private val clients = mutableMapOf<String, Gh>()

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /** True when the last load came off the disk cache instead of the network. */
    var offline by mutableStateOf(false); private set

    /** The home screen. Read from prefs, so it is on screen before any network is tried. */
    var linked by mutableStateOf(loadLinks()); private set
    var entries by mutableStateOf(emptyList<Entry>()); private set
    var branches by mutableStateOf(emptyList<String>()); private set

    var showAll by mutableStateOf(false)

    /** Commits written on the phone that have not reached GitHub yet. */
    var queue by mutableStateOf(emptyList<Pending>()); private set
    val queuedCount: Int get() = queue.size
    val conflictedCount: Int get() = queue.count { it.conflicted }

    /** Queued for a repo that is off the list or whose token was refused - cannot push yet. */
    val strandedCount: Int
        get() = queue.count { p -> linked.none { it.repo.full_name == p.repo && !it.rejected } }

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

    var query by mutableStateOf("")
    var hits by mutableStateOf(emptyList<Hit>()); private set
    private var searchJob: Job? = null

    /** A conflicted commit being compared against what is on GitHub now. */
    var comparing by mutableStateOf<Pair<Pending, List<DiffLine>>?>(null); private set

    var pendingDraft by mutableStateOf<String?>(null); private set
    var conflict by mutableStateOf<String?>(null); private set

    // ---- adding a repo: pick a sign-in, then pick from what it can reach ----
    var addToken by mutableStateOf<String?>(null); private set
    var addLogin by mutableStateOf(""); private set
    var candidates by mutableStateOf(emptyList<Repo>()); private set

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
            // called on a binder thread; hop to main before touching the stack
            viewModelScope.launch {
                val was = offline
                offline = false
                // signal came back in the woods - land whatever was written without it
                if (queue.isNotEmpty()) job { drain(announce = true) }
                // and swap the stale listing for the real one
                if (was && screen is Screen.Browse) refresh()
            }
        }

        override fun onLost(network: Network) {
            offline = !hasNetwork()
        }
    }

    init {
        refreshQueue()
        runCatching { conn?.registerDefaultNetworkCallback(onNetwork) }
        offline = !hasNetwork()
        migrateSingleSignIn()
    }

    /** 0.1 kept one token and a starred home repo; that becomes the first linked repo. */
    private fun migrateSingleSignIn() {
        if (linked.isNotEmpty()) return
        val token = prefs.getString("token", null) ?: return
        val home = prefs.getString("home", null) ?: return
        val repo = cache.get("repos", "", "", "")
            ?.let { runCatching { parseRepos(it) }.getOrNull() }
            ?.firstOrNull { it.full_name == home } ?: return
        saveLinks(listOf(Linked(repo, token)))
    }

    private fun job(block: suspend () -> Unit): Job = viewModelScope.launch {
        busy = true
        error = null
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (t.httpStatus == 401) openRepoName()?.let(::markRejected)
            error = t.readable()
        } finally {
            busy = false
        }
    }

    private fun client(token: String): Gh = clients.getOrPut(token) { Gh(token) }

    private fun api(repo: String): Gh {
        val l = linked.firstOrNull { it.repo.full_name == repo }
            ?: error("$repo is not on your list - add it again to reach it")
        return client(l.token)
    }

    private fun openRepoName(): String? = when (val s = screen) {
        is Screen.Browse -> s.repo.full_name
        is Screen.Outline -> s.repo.full_name
        is Screen.Edit -> s.repo.full_name
        is Screen.Search -> s.repo.full_name
        else -> null
    }

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
    ): String {
        if (offline) cache.get(kind, repo, branch, path)?.let { hit ->
            // Known dead spot: answer from disk now instead of eating another timeout, and
            // check in the background whether signal is back. Cell signal can return without
            // onAvailable firing, so this is what clears `offline` on a weak-bars day.
            // ponytail: one background fetch per navigation, no dedupe - cheap at walking pace
            viewModelScope.launch {
                runCatching { cache.put(kind, repo, branch, path, fetch()); offline = false }
            }
            return hit
        }
        return network(kind, repo, branch, path, fetch)
    }

    private suspend fun network(
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

    // ---- linked repos -----------------------------------------------------

    private fun loadLinks(): List<Linked> = prefs.getString("linked", null)
        ?.let { runCatching { parseLinks(it) }.getOrNull() } ?: emptyList()

    private fun saveLinks(l: List<Linked>) {
        linked = l
        prefs.edit().putString("linked", encodeLinks(l)).apply()
    }

    private fun markRejected(repo: String) {
        saveLinks(linked.map { if (it.repo.full_name == repo) it.copy(rejected = true) else it })
    }

    /** Sign-ins already on the phone, offered when adding another repo: token to a label. */
    val savedSignIns: List<Pair<String, String>>
        get() = (linked.filter { !it.rejected }.map { it.token to it.repo.full_name } +
            listOfNotNull(prefs.getString("token", null)?.let { it to "your earlier sign-in" }))
            .distinctBy { it.first }

    fun startAdd() {
        addToken = null
        candidates = emptyList()
        stack += Screen.Add
    }

    fun useToken(token: String) {
        job { loadCandidates(token.trim()) }
    }

    private suspend fun loadCandidates(token: String) {
        val g = client(token)
        try {
            addLogin = g.me().login
            candidates = g.repos()
            offline = false
        } catch (t: Throwable) {
            if (linked.none { it.token == token }) clients.remove(token)?.close()
            throw t
        }
        addToken = token
    }

    /** Put [r] on the home screen and go straight into it. */
    fun link(r: Repo) {
        val token = addToken ?: return
        saveLinks(linked.filterNot { it.repo.full_name == r.full_name } + Linked(r, token))
        stack.clear()
        stack += Screen.Home
        openRepo(r)
        // anything written for this repo while it was unreachable can go now
        if (queue.any { it.repo == r.full_name }) job { drain(announce = true) }
    }

    /** Off the list. Its queued commits and drafts stay on disk until it is added back. */
    fun unlink(l: Linked) {
        saveLinks(linked - l)
        if (linked.none { it.token == l.token }) clients.remove(l.token)?.close()
    }

    fun deviceSignIn() {
        deviceJob = job {
            val id = BuildConfig.GITHUB_CLIENT_ID
            check(id.isNotBlank()) { "No OAuth client ID compiled in - set octoquill.clientId" }
            val dc = DeviceFlow.start(id)
            device = dc
            try {
                loadCandidates(DeviceFlow.await(id, dc))
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

    // ---- navigation -------------------------------------------------------

    private suspend fun loadDir(repo: Repo, branch: String, path: String) {
        entries = parseList(cached("dir", repo.full_name, branch, path) {
            api(repo.full_name).contentsJson(repo.full_name, path, branch)
        })
    }

    /**
     * Paint from disk first, then refresh. Bad signal means a timeout before the network
     * answers, and an empty screen that long is worse than slightly stale files.
     */
    fun openRepo(r: Repo) {
        val saved = cache.get("dir", r.full_name, r.default_branch, "")
            ?.let { runCatching { parseList(it) }.getOrNull() }
        if (saved == null) {
            job {
                loadDir(r, r.default_branch, "")
                branches = runCatching { api(r.full_name).branches(r.full_name) }
                    .getOrDefault(listOf(r.default_branch))
                stack += Screen.Browse(r, r.default_branch, "")
            }
            return
        }
        entries = saved
        branches = listOf(r.default_branch)
        stack += Screen.Browse(r, r.default_branch, "")
        val shown = screen
        job {
            val fresh = parseList(cached("dir", r.full_name, r.default_branch, "") {
                api(r.full_name).contentsJson(r.full_name, "", r.default_branch)
            })
            // never overwrite a folder someone already tapped into
            if (screen == shown) entries = fresh
            branches = runCatching { api(r.full_name).branches(r.full_name) }.getOrDefault(branches)
        }
    }

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
        val json = api(repo.full_name).contentsJson(repo.full_name, path, branch)
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
                        api(repo.full_name).contentsJson(repo.full_name, e.path, branch),
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
            } else {
                openFile(b.repo, b.branch, e)
            }
        }
    }

    private suspend fun openFile(repo: Repo, branch: String, e: Entry) {
        val (remote, sha) = parseFile(cached("file", repo.full_name, branch, e.path) {
            api(repo.full_name).contentsJson(repo.full_name, e.path, branch)
        })
        loadFile(repo, branch, e.path, remote, sha)

        if (sections.size >= 2 && (e.size > BIG_FILE_BYTES || sections.size >= 12)) {
            stack += Screen.Outline(repo, branch, e.path)
        } else {
            selectSection(null)
            stack += Screen.Edit(repo, branch, e.path)
        }
    }

    // ---- search -----------------------------------------------------------

    fun openSearch() {
        val b = screen as? Screen.Browse ?: return
        query = ""
        hits = emptyList()
        stack += Screen.Search(b.repo, b.branch)
    }

    /**
     * Searches what is saved on the phone, so it works with no signal - which is when you
     * most need to find "that scene with the ferry". "Save for offline" makes it the whole repo.
     */
    fun search(q: String) {
        query = q
        val s = screen as? Screen.Search ?: return
        searchJob?.cancel()
        if (q.isBlank()) {
            hits = emptyList(); return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            hits = withContext(Dispatchers.Default) {
                mutableListOf<Hit>().also { searchCache(s.repo.full_name, s.branch, "", q.trim(), it) }
            }
        }
    }

    private fun searchCache(repo: String, branch: String, path: String, q: String, out: MutableList<Hit>) {
        val listing = cache.get("dir", repo, branch, path)
            ?.let { runCatching { parseList(it) }.getOrNull() } ?: return
        for (e in listing) {
            if (out.size >= MAX_HITS) return
            if (e.type == "dir") {
                searchCache(repo, branch, e.path, q, out)
                continue
            }
            if (e.name.contains(q, ignoreCase = true)) out += Hit(e, 0, "")
            val text = cache.get("file", repo, branch, e.path)
                ?.let { runCatching { parseFile(it).first }.getOrNull() } ?: continue
            text.lineSequence().forEachIndexed { i, line ->
                if (out.size < MAX_HITS && line.contains(q, ignoreCase = true)) {
                    out += Hit(e, i + 1, line.trim().take(160))
                }
            }
        }
    }

    fun openHit(h: Hit) {
        val s = screen as? Screen.Search ?: return
        job { openFile(s.repo, s.branch, h.entry) }
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
            is Screen.Home -> job { drain(announce = true) }
            is Screen.Add -> addToken?.let { t -> job { loadCandidates(t) } }
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
        job { history = api(repo).history(repo, path, branch) }
    }

    fun closeHistory() {
        historyOpen = false
        history = emptyList()
    }

    // ---- file operations --------------------------------------------------

    fun deleteFile(e: Entry) {
        val b = screen as? Screen.Browse ?: return
        job {
            api(b.repo.full_name).delete(b.repo.full_name, e.path, "Delete ${e.name}", b.branch, e.sha)
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
            val gh = api(b.repo.full_name)
            val (body, sha) = gh.read(b.repo.full_name, e.path, b.branch)
            gh.commit(b.repo.full_name, target, body, "Rename ${e.name} to $newName", b.branch, null)
            gh.delete(b.repo.full_name, e.path, "Rename ${e.name} to $newName (remove old)", b.branch, sha)
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
        var landed = 0
        for (p in outbox.all()) {
            if (p.conflicted) continue
            // not on the list, or its token was refused: it waits until the repo is added again
            if (linked.none { it.repo.full_name == p.repo && !it.rejected }) continue
            try {
                val c = api(p.repo).commit(p.repo, p.path, p.text, p.message, p.branch, p.baseSha)
                outbox.remove(p)
                landed++
                if (announce) notice = "Pushed ${c.sha.take(7)} to ${p.branch}"
                offline = false
            } catch (t: Throwable) {
                when (t.httpStatus) {
                    401 -> {
                        markRejected(p.repo)
                        if (announce) notice = "GitHub refused the token for ${p.repo} - add it again"
                    }

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
            val fresh = api(p.repo).shaOf(p.repo, p.path, p.branch)
            val c = api(p.repo).commit(p.repo, p.path, p.text, p.message, p.branch, fresh)
            outbox.remove(p)
            refreshQueue()
            notice = "Pushed ${c.sha.take(7)} to ${p.branch}"
        }
    }

    /** Show what GitHub has now against the queued version, before choosing a side. */
    fun compare(p: Pending) {
        job {
            val theirs = try {
                api(p.repo).read(p.repo, p.path, p.branch).first
            } catch (t: Throwable) {
                if (t.httpStatus == 404) "" else throw t // deleted on GitHub: all of yours is new
            }
            comparing = p to withContext(Dispatchers.Default) { folded(lineDiff(theirs, p.text)) }
        }
    }

    fun closeCompare() {
        comparing = null
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
        clients.values.forEach { it.close() }
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
