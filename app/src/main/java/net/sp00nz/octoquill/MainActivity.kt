package net.sp00nz.octoquill

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Login : Screen
    data object Repos : Screen
    data class Browse(val repo: Repo, val branch: String, val path: String) : Screen
    data class Edit(val repo: Repo, val branch: String, val path: String) : Screen
}

class Vm(app: Application) : AndroidViewModel(app) {

    // ponytail: token lives in app-private prefs with allowBackup=false. Android's
    // file-based encryption already covers it at rest; reach for EncryptedSharedPreferences
    // only if this ever ships somewhere that isn't true.
    private val prefs = app.getSharedPreferences("octoquill", Context.MODE_PRIVATE)
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

    /** Editor buffer. */
    var text by mutableStateOf("")
    private var saved by mutableStateOf("")
    private var blobSha by mutableStateOf<String?>(null)
    val dirty: Boolean get() = text != saved
    val isNewFile: Boolean get() = blobSha == null

    var device by mutableStateOf<DeviceCode?>(null); private set
    private var deviceJob: Job? = null

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
    }

    fun openRepo(r: Repo) {
        job {
            val g = api()
            val list = g.list(r.full_name, "", r.default_branch)
            branches = runCatching { g.branches(r.full_name) }.getOrDefault(listOf(r.default_branch))
            entries = list
            stack += Screen.Browse(r, r.default_branch, "")
        }
    }

    fun open(e: Entry) {
        val b = screen as? Screen.Browse ?: return
        job {
            if (e.type == "dir") {
                entries = api().list(b.repo.full_name, e.path, b.branch)
                stack += b.copy(path = e.path)
            } else {
                val (body, sha) = api().read(b.repo.full_name, e.path, b.branch)
                text = body; saved = body; blobSha = sha
                stack += Screen.Edit(b.repo, b.branch, e.path)
            }
        }
    }

    fun newFile(name: String) {
        val b = screen as? Screen.Browse ?: return
        text = ""; saved = ""; blobSha = null
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
            // keep the whole trail on one branch, so going back doesn't silently jump back
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
        stack.removeAt(stack.lastIndex)
        (screen as? Screen.Browse)?.let { s ->
            job { entries = api().list(s.repo.full_name, s.path, s.branch) }
        }
    }

    /** Commit *and* push, in one API call. */
    fun commit(message: String) {
        val s = screen as? Screen.Edit ?: return
        val body = text
        job {
            val c = api().commit(
                repo = s.repo.full_name,
                path = s.path,
                text = body,
                message = message.ifBlank { "Update ${s.path.substringAfterLast('/')}" },
                branch = s.branch,
                sha = blobSha,
            )
            saved = body
            notice = "Pushed ${c.sha.take(7)} to ${s.branch}"
            back()
        }
    }

    override fun onCleared() = gh?.close() ?: Unit
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OctoquillTheme {
                App(viewModel<Vm>())
            }
        }
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
