package net.sp00nz.octoquill

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val JSON = Json { ignoreUnknownKeys = true }

@Serializable
data class User(val login: String, val avatar_url: String = "")

@Serializable
data class Repo(
    val full_name: String,
    val name: String,
    val default_branch: String = "main",
    val private: Boolean = false,
    val description: String? = null,
)

@Serializable
data class Entry(
    val name: String,
    val path: String,
    val type: String,
    val sha: String = "",
    val size: Long = 0,
    val content: String? = null,
)

@Serializable
data class Branch(val name: String)

@Serializable
data class Commit(val sha: String, val html_url: String = "")

@Serializable
private data class PutBody(
    val message: String,
    val content: String,
    val sha: String? = null,
    val branch: String? = null,
)

@Serializable
private data class PutResp(val commit: Commit)

@Serializable
private data class DeleteBody(val message: String, val sha: String, val branch: String)

@Serializable
data class CommitAuthor(val name: String = "", val date: String = "")

@Serializable
data class CommitMeta(val message: String = "", val author: CommitAuthor? = null)

@Serializable
data class LogEntry(val sha: String, val commit: CommitMeta)

/** File contents big enough that GitHub's contents API refuses to inline them. */
const val MAX_EDITABLE_BYTES = 1_000_000L

class Gh(token: String) {

    private val http = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(JSON) }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    fun close() = http.close()

    private fun url(path: String) = "https://api.github.com/$path"

    suspend fun me(): User = http.get(url("user")).body()

    suspend fun repos(): List<Repo> = buildList {
        // ponytail: stops at 1000 repos; add real paging when someone actually has more
        for (page in 1..10) {
            val batch: List<Repo> = http.get(url("user/repos")) {
                parameter("per_page", 100)
                parameter("page", page)
                parameter("sort", "pushed")
            }.body()
            addAll(batch)
            if (batch.size < 100) break
        }
    }

    suspend fun branches(repo: String): List<String> =
        http.get(url("repos/$repo/branches")) { parameter("per_page", 100) }
            .body<List<Branch>>().map { it.name }

    /** Raw JSON, so callers can hand it straight to the offline cache. */
    suspend fun contentsJson(repo: String, path: String, ref: String): String =
        http.get(url("repos/$repo/contents/${path.trim('/')}")) { parameter("ref", ref) }
            .bodyAsText()

    /** Directory listing, dirs first then files, case-insensitive by name. */
    suspend fun list(repo: String, path: String, ref: String): List<Entry> =
        parseList(contentsJson(repo, path, ref))

    /** Decoded text plus the blob sha the next commit must reference. */
    suspend fun read(repo: String, path: String, ref: String): Pair<String, String> =
        parseFile(contentsJson(repo, path, ref))

    /** Current blob sha for a path, or null if it isn't there any more. */
    suspend fun shaOf(repo: String, path: String, ref: String): String? = runCatching {
        parseFile(contentsJson(repo, path, ref)).second
    }.getOrNull()

    /** Commits that touched one path, newest first. */
    suspend fun history(repo: String, path: String, ref: String, limit: Int = 30): List<LogEntry> =
        http.get(url("repos/$repo/commits")) {
            parameter("path", path)
            parameter("sha", ref)
            parameter("per_page", limit)
        }.body()

    /** Delete a file. Also one commit, also pushed by GitHub. */
    suspend fun delete(
        repo: String,
        path: String,
        message: String,
        branch: String,
        sha: String,
    ): Commit = http.delete(url("repos/$repo/contents/${path.trim('/')}")) {
        contentType(ContentType.Application.Json)
        setBody(DeleteBody(message, sha, branch))
    }.body<PutResp>().commit

    /**
     * Create or update a file on [branch]. This one call *is* the commit and the push —
     * GitHub writes the blob, the tree, the commit and moves the ref server-side.
     * [sha] is the blob sha being replaced, or null when creating a new file.
     */
    suspend fun commit(
        repo: String,
        path: String,
        text: String,
        message: String,
        branch: String,
        sha: String?,
    ): Commit = http.put(url("repos/$repo/contents/${path.trim('/')}")) {
        contentType(ContentType.Application.Json)
        setBody(
            PutBody(
                message = message,
                content = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                sha = sha,
                branch = branch,
            )
        )
    }.body<PutResp>().commit
}

/** Decode a cached or live directory listing. */
fun parseList(json: String): List<Entry> =
    JSON.decodeFromString<List<Entry>>(json)
        .sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))

/** Decode a cached or live single-file response into text + blob sha. */
fun parseFile(json: String): Pair<String, String> {
    val e = JSON.decodeFromString<Entry>(json)
    if (e.size > MAX_EDITABLE_BYTES) error("File is ${e.size / 1024}KB - too big to edit here")
    val bytes = Base64.decode((e.content ?: "").replace("\n", ""), Base64.NO_WRAP)
    if (bytes.any { it == 0.toByte() }) error("Binary file - nothing to edit")
    return String(bytes, Charsets.UTF_8) to e.sha
}

/** HTTP status of a failed call, or null if it never got that far. */
val Throwable.httpStatus: Int?
    get() = (this as? ResponseException)?.response?.status?.value

/** Turns a ktor/HTTP failure into something worth putting on screen. */
suspend fun Throwable.readable(): String = when (this) {
    is ResponseException -> {
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        val msg = runCatching {
            JSON.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()
        "${response.status.value}: ${msg ?: response.status.description}"
    }

    else -> message ?: toString()
}

@Serializable
data class DeviceCode(
    val device_code: String,
    val user_code: String,
    val verification_uri: String,
    val interval: Int = 5,
)

@Serializable
private data class TokenResp(val access_token: String? = null, val error: String? = null)

/**
 * GitHub's OAuth device flow: no redirect URI, no client secret, no backend.
 * The phone shows a code, the user types it at github.com/login/device, we poll.
 */
object DeviceFlow {

    private val http = HttpClient(OkHttp) { install(ContentNegotiation) { json(JSON) } }

    suspend fun start(clientId: String): DeviceCode =
        http.post("https://github.com/login/device/code") {
            header(HttpHeaders.Accept, "application/json")
            parameter("client_id", clientId)
            parameter("scope", "repo read:user")
        }.body()

    /** Suspends until the user approves (or the code dies), then returns the access token. */
    suspend fun await(clientId: String, dc: DeviceCode): String {
        var wait = dc.interval.coerceAtLeast(5)
        while (true) {
            delay(wait * 1000L)
            val r: TokenResp = http.post("https://github.com/login/oauth/access_token") {
                header(HttpHeaders.Accept, "application/json")
                parameter("client_id", clientId)
                parameter("device_code", dc.device_code)
                parameter("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            }.body()

            r.access_token?.let { return it }
            when (r.error) {
                "authorization_pending" -> Unit
                "slow_down" -> wait += 5
                "expired_token" -> error("Code expired — start again")
                "access_denied" -> error("Sign-in was denied")
                else -> error(r.error ?: "Device flow failed")
            }
        }
    }
}

/** Repo lists are cached whole, so a cold start with no signal still shows your repos. */
fun encodeRepos(repos: List<Repo>): String = JSON.encodeToString(REPO_LIST, repos)

fun parseRepos(json: String): List<Repo> = JSON.decodeFromString(REPO_LIST, json)

private val REPO_LIST = kotlinx.serialization.builtins.ListSerializer(Repo.serializer())
