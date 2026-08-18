package net.sp00nz.octoquill

import android.app.Application
import android.content.Context
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
    data class Edit(val repo: Repo, val branch: String, val path: String) : Screen
}

/** Files worth warning about before opening them in a phone text field. */
const val BIG_FILE_BYTES = 64_000L

class Vm(app: Application) : AndroidViewModel(app) {

    // ponytail: token lives in app-private prefs with allowBackup=false. Android's
    // file-based encryption already covers it at rest; reach for EncryptedSharedPreferences
    // only if this ever ships somewhere that isn't true.
    private val prefs = app.getSharedPreferences("octoquill", Context.MODE_PRIVATE)
    private val drafts = Drafts(app)
    private var gh: Gh? = null

    val stack = mutableStateListOf<Screen>(Screen.Login)
    val screen: Screen get() = stack.last()

    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    var user by mutableStateOf<User?>(null); private set
    var repos by mutableStateOf(emptyList<Repo>()); private set
    var entries by mutableStateOf(emptyList<Entry>()); private set
    var branches by mutableStateOf(emptyList<String>()); private set

    /** The repo you actually live in — opened straight after sign-in. */
    var homeRepo by mutableStateOf(prefs.getString("home", null)); private set

    /** Media and binaries are hidden by default; a writing repo is mostly prose. */
    var showAll by mutableStateOf(false)

    /** Editor buffer. */
    var text by mutableStateOf("")
    private var saved by mutableStateOf("")
    private var blobSha by mutableStateOf<String?>(null)
    val dirty: Boolean get() = text != saved
    val isNewFile: Boolean get() = blobSha == null

    /** A draft recovered from disk that differs from what GitHub has. */
    var pendingDraft by mutableStateOf<String?>(null); private set

    /** Set when a commit was rejected because the file moved under us; holds the message. */
    var conflict by mutableStateOf<String?>(null); private set

    var device by mutableStateOf<DeviceCode?>(null); private set
    private var deviceJob: Job? = null
    private var draftJob: Job? = null

    val hasOAuthApp = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()

    init {
        prefs.getString("token", null)?.let { t -> job { applyToken(t, persist = false) } }
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

    // ---- auth -------------------------------------------------------------

    private suspend fun applyToken(token: String, persist: Boolean) {
        val g = Gh(token.trim())
        // me() is the cheapest way to find out the token is junk before we store it
        val who = try {
            g.me()
        } catch (t: Throwable) {
            g.close(); throw t
        }
        gh?.close()
        gh = g
        user = who
        if (persist) prefs.edit().putString("token", token.trim()).apply()
        repos = g.repos()
        stack.clear()
        stack += Screen.Repos
        repos.firstOrNull { it.full_name == homeRepo }?.let { enterRepo(it) }
    }

    fun signInWithToken(token: String) {
        job { applyToken(token, persist = true) }
    }

    fun deviceSignIn() {
        deviceJob = job {
            val id = BuildConfig.GITHUB_CLIENT_ID
            check(id.isNotBlank()) { "No OAuth client ID compiled in — set octoquill.clientId" }
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
        // ponytail: drafts deliberately survive sign-out — signing out should not eat writing
    }

    // ---- navigation -------------------------------------------------------

    fun setHome(fullName: String) {
        homeRepo = if (homeRepo == fullName) null else fullName
        prefs.edit().putString("home", homeRepo).apply()
    }

    private suspend fun enterRepo(r: Repo) {
        val g = api()
        val list = g.list(r.full_name, "", r.default_branch)
        branches = runCatching { g.branches(r.full_name) }.getOrDefault(listOf(r.default_branch))
        entries = list
        stack += Screen.Browse(r, r.default_branch, "")
    }

    fun openRepo(r: Repo) = job { enterRepo(r) }

    fun open(e: Entry) {
        val b = screen as? Screen.Browse ?: return
        job {
            if (e.type == "dir") {
                entries = api().list(b.repo.full_name, e.path, b.branch)
                stack += b.copy(path = e.path)
            } else {
                val (body, sha) = api().read(b.repo.full_name, e.path, b.branch)
                text = body; saved = body; blobSha = sha
                // an interrupted session usually beats what GitHub has, but the writer decides
                pendingDraft = drafts.load(b.repo.full_name, b.branch, e.path)?.takeIf { it != body }
                if (e.size > BIG_FILE_BYTES) notice = "${e.size / 1024}KB — the editor may lag"
                stack += Screen.Edit(b.repo, b.branch, e.path)
            }
        }
    }

    fun newFile(name: String) {
        val b = screen as? Screen.Browse ?: return
        text = ""; saved = ""; blobSha = null; pendingDraft = null
        stack += Screen.Edit(
            b.repo, b.branch,
            if (b.path.isEmpty()) name.trim('/') else "${b.path}/${name.trim('/')}"
        )
    }

    fun switchBranch(name: String) {
        val b = screen as? Screen.Browse ?: return
        if (b.branch == name) return
        job {
            entries = api().list(b.repo.full_name, b.path, name)
            // keep the whole trail on one branch, so going back does not silently jump back
            for (i in stack.indices) (stack[i] as? Screen.Browse)?.let { stack[i] = it.copy(branch = name) }
        }
    }

    fun refresh() {
        when (val s = screen) {
            is Screen.Repos -> job { repos = api().repos() }
            is Screen.Browse -> job { entries = api().list(s.repo.full_name, s.path, s.branch) }
            else -> Unit
        }
    }

    fun back() {
        if (stack.size <= 1) return
        flushDraft()
        stack.removeAt(stack.lastIndex)
        (screen as? Screen.Browse)?.let { s ->
            job { entries = api().list(s.repo.full_name, s.path, s.branch) }
        }
    }

    // ---- editing ----------------------------------------------------------

    /** Every keystroke updates the buffer; a debounced write mirrors it to disk. */
    fun onTextChange(v: String) {
        text = v
        val s = screen as? Screen.Edit ?: return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(600)
            drafts.save(s.repo.full_name, s.branch, s.path, v)
        }
    }

    /** Called when the app is backgrounded — no debounce, write it now. */
    fun flushDraft() {
        val s = screen as? Screen.Edit ?: return
        draftJob?.cancel()
        if (dirty || isNewFile) drafts.save(s.repo.full_name, s.branch, s.path, text)
    }

    fun restoreDraft() {
        pendingDraft?.let { text = it }
        pendingDraft = null
    }

    fun discardDraft() {
        val s = screen as? Screen.Edit
        pendingDraft = null
        if (s != null) drafts.clear(s.repo.full_name, s.branch, s.path)
    }

    // ---- committing -------------------------------------------------------

    /** Commit *and* push, in one API call. */
    fun commit(message: String) {
        val s = screen as? Screen.Edit ?: return
        job { push(s, message, force = false) }
    }

    /** After a conflict: take whatever sha is on the branch now and write over it. */
    fun overwrite() {
        val s = screen as? Screen.Edit ?: return
        val message = conflict ?: return
        conflict = null
        job {
            blobSha = api().shaOf(s.repo.full_name, s.path, s.branch)
            push(s, message, force = true)
        }
    }

    fun dismissConflict() {
        conflict = null
    }

    private suspend fun push(s: Screen.Edit, message: String, force: Boolean) {
        val body = text
        val c = try {
            api().commit(
                repo = s.repo.full_name,
                path = s.path,
                text = body,
                message = message.ifBlank { "Update ${s.path.substringAfterLast('/')}" },
                branch = s.branch,
                sha = blobSha,
            )
        } catch (t: Throwable) {
            // 409/422 means the file changed on GitHub since we opened it — a commit from
            // the web editor or a laptop. The draft is already on disk, so nothing is lost
            // whichever way this goes; let the writer decide.
            if (!force && t.httpStatus in setOf(409, 422)) {
                conflict = message
                return
            }
            throw t
        }
        saved = body
        drafts.clear(s.repo.full_name, s.branch, s.path)
        notice = "Pushed ${c.sha.take(7)} to ${s.branch}"
        back()
    }

    override fun onCleared() {
        flushDraft()
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
