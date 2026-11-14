package dev.zakocoder.app.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal enum class OnlineSkillProblem { InvalidUrl, NotFound, RateLimited, Network, TooLarge, InvalidRepository, UnsafeResource, InvalidSkill, Selection, Install }
internal class OnlineSkillException(val problem: OnlineSkillProblem, val status: Int? = null) : Exception(problem.name)

internal data class OnlineSkillSource(val owner: String, val repository: String, val ref: String?, val directory: String) {
    val repositoryUrl: String get() = "https://github.com/$owner/$repository"
    companion object {
        fun parse(value: String, explicitRef: String = ""): OnlineSkillSource {
            try {
                require(value.length <= 1200 && value.none(Char::isISOControl))
                val uri = URI(value.trim())
                require(uri.scheme == "https" && uri.host.equals("github.com", true) && uri.rawUserInfo == null && uri.port == -1)
                require(uri.rawQuery == null && uri.rawFragment == null)
                // Encoded separators make a tree URL's branch/path boundary ambiguous.
                require(!Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath.orEmpty()))
                val parts = uri.path.removePrefix("/").removeSuffix("/").split('/')
                require(parts.size >= 2 && parts.none { it.isEmpty() || it == "." || it == ".." || '\\' in it })
                val owner = parts[0]
                val repository = parts[1].removeSuffix(".git")
                require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")))
                require(repository.length in 1..100 && repository.matches(Regex("[A-Za-z0-9_.-]+")) && repository.any { it != '.' })
                require(explicitRef.length <= 240 && explicitRef.none { it.isISOControl() || it.isWhitespace() || it in "?#\\" })
                val ref: String?
                val directory: String
                if (parts.size == 2) { ref = explicitRef.trim().ifEmpty { null }; directory = "" }
                else {
                    require(parts.size >= 4 && parts[2] == "tree" && explicitRef.isBlank())
                    ref = parts[3]
                    directory = parts.drop(4).joinToString("/")
                }
                if (directory.isNotEmpty()) validatePath(directory, sensitive = false)
                return OnlineSkillSource(owner, repository, ref, directory)
            } catch (_: Exception) { throw OnlineSkillException(OnlineSkillProblem.InvalidUrl) }
        }
    }
}

internal data class OnlineSkillFile(val path: String, val sha: String, val size: Long, val regular: Boolean)
internal data class OnlineSkillCandidate(val directory: String, val document: String, val fileCount: Int, val bytes: Long) {
    val label: String get() = directory.substringAfterLast('/').ifEmpty { "SKILL.md" }
}
internal data class OnlineSkillCatalog(val source: OnlineSkillSource, val commit: String, val candidates: List<OnlineSkillCandidate>,
    val files: List<OnlineSkillFile>, val license: String?, val licenseFile: String?, val licenseBytes: ByteArray?)
internal data class PreparedOnlineSkills(val bytes: ByteArray, val names: List<String>, val source: OnlineSkillCatalog)

/** Only fixed GitHub endpoints, no credentials, redirects or arbitrary URL fetching. */
internal fun interface OnlineSkillTransport { suspend fun fetch(url: String, limit: Int): ByteArray }

internal class GithubSkillTransport(client: OkHttpClient = OkHttpClient()) : OnlineSkillTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun fetch(url: String, limit: Int): ByteArray {
        val target = url.toHttpUrl()
        if (!target.isHttps || target.host !in setOf("api.github.com", "raw.githubusercontent.com") || target.port != 443 ||
            target.username.isNotEmpty() || target.password.isNotEmpty()) throw OnlineSkillException(OnlineSkillProblem.InvalidUrl)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(target).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "ZakoCoder-Skill-Import").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(OnlineSkillException(OnlineSkillProblem.Network))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!continuation.isActive) return
                            if (!response.isSuccessful) throw OnlineSkillException(when {
                                response.code == 404 -> OnlineSkillProblem.NotFound
                                response.code == 429 || response.code == 403 && response.header("X-RateLimit-Remaining") == "0" -> OnlineSkillProblem.RateLimited
                                else -> OnlineSkillProblem.Network
                            }, response.code)
                            val body = response.body ?: throw OnlineSkillException(OnlineSkillProblem.Network)
                            if (body.contentLength() > limit) throw OnlineSkillException(OnlineSkillProblem.TooLarge)
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (continuation.isActive) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > limit) throw OnlineSkillException(OnlineSkillProblem.TooLarge)
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (continuation.isActive) continuation.resume(output.toByteArray())
                        }
                    } catch (cancelled: CancellationException) { if (continuation.isActive) continuation.cancel(cancelled) }
                    catch (failure: OnlineSkillException) { if (continuation.isActive) continuation.resumeWithException(failure) }
                    catch (_: IOException) { if (continuation.isActive) continuation.resumeWithException(OnlineSkillException(OnlineSkillProblem.Network)) }
                    catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(OnlineSkillException(OnlineSkillProblem.InvalidRepository)) }
                }
            })
        }
    }
}

internal class AiOnlineSkills(private val transport: OnlineSkillTransport = GithubSkillTransport()) {
    suspend fun discover(url: String, ref: String = ""): OnlineSkillCatalog = withContext(Dispatchers.IO) {
        withTimeoutOrNull(120000) { checked {
            val source = OnlineSkillSource.parse(url, ref)
            val repository = json(api(source), 256 * 1024)
            val branch = source.ref ?: repository.getString("default_branch")
            require(branch.isNotBlank() && branch.length <= 240)
            val commitInfo = json(api(source, "commits", branch), 512 * 1024)
            val commit = commitInfo.getString("sha").also(::validateSha)
            var treeSha = commitInfo.getJSONObject("commit").getJSONObject("tree").getString("sha").also(::validateSha)
            // Resolve a selected subtree before requesting recursively; large unrelated repository trees stay out.
            source.directory.split('/').filter(String::isNotEmpty).forEach { segment ->
                val entries = tree(source, treeSha, false)
                val directory = entries.singleOrNull { it.getString("path") == segment && it.optString("type") == "tree" && it.optString("mode") == "040000" }
                    ?: throw OnlineSkillException(OnlineSkillProblem.NotFound)
                treeSha = directory.getString("sha").also(::validateSha)
            }
            val prefix = source.directory.takeIf(String::isNotEmpty)?.plus('/').orEmpty()
            val files = tree(source, treeSha, true).filter { it.optString("type") != "tree" }.map { node ->
                val path = prefix + node.getString("path")
                validatePath(path, sensitive = false)
                val sha = node.getString("sha").also(::validateSha)
                val regular = node.optString("type") == "blob" && node.optString("mode") in setOf("100644", "100755")
                OnlineSkillFile(path, sha, node.optLong("size", -1), regular)
            }
            require(files.map { it.path }.distinct().size == files.size)
            val documents = files.filter { it.path.substringAfterLast('/').equals("SKILL.md", true) }
            if (documents.any { !it.regular }) throw OnlineSkillException(OnlineSkillProblem.UnsafeResource)
            if (documents.isEmpty()) throw OnlineSkillException(OnlineSkillProblem.InvalidSkill)
            if (documents.size > MAX_CANDIDATES) throw OnlineSkillException(OnlineSkillProblem.TooLarge)
            val candidates = documents.map { document ->
                val directory = document.path.substringBeforeLast('/', "")
                val owned = files.filter { belongs(it.path, directory) }
                OnlineSkillCandidate(directory, document.path, owned.size, owned.sumOf { it.size.coerceAtLeast(0) })
            }.sortedBy { it.directory }
            require(candidates.map { it.directory }.distinct().size == candidates.size)
            val license = try { json(api(source, "license").newBuilder().addQueryParameter("ref", commit).build(), 512 * 1024) }
                catch (failure: OnlineSkillException) { if (failure.problem == OnlineSkillProblem.NotFound) null else throw failure }
            val licensePath = license?.getString("path")?.also { validatePath(it) }
            val licenseBytes = license?.let {
                require(it.optString("encoding") == "base64" && it.optLong("size") in 1..AiSkillArchive.MAX_FILE_BYTES.toLong())
                Base64.getMimeDecoder().decode(it.getString("content")).also { bytes ->
                    require(bytes.size == it.getLong("size").toInt())
                    verifyBlob(bytes, it.getString("sha"))
                }
            }
            val licenseName = license?.optJSONObject("license")?.optString("spdx_id")?.takeUnless { it.isBlank() || it == "NOASSERTION" }
            OnlineSkillCatalog(source, commit, candidates, files, licenseName, licensePath, licenseBytes)
        } } ?: throw OnlineSkillException(OnlineSkillProblem.Network)
    }

    suspend fun prepare(catalog: OnlineSkillCatalog, directories: Set<String>, progress: (Int, Int) -> Unit = { _, _ -> }): PreparedOnlineSkills =
        withContext(Dispatchers.IO) { withTimeoutOrNull(180000) { checked {
            validateSha(catalog.commit)
            if (directories.isEmpty() || directories.size > 32 || !catalog.candidates.map { it.directory }.containsAll(directories))
                throw OnlineSkillException(OnlineSkillProblem.Selection)
            if (directories.any { parent -> directories.any { it != parent && (parent.isEmpty() || it.startsWith("$parent/")) } })
                throw OnlineSkillException(OnlineSkillProblem.Selection)
            val selected = catalog.candidates.filter { it.directory in directories }
            val groups = selected.map { candidate -> catalog.files.filter { belongs(it.path, candidate.directory) } }
            val files = groups.flatten()
            if (files.any { !it.regular }) throw OnlineSkillException(OnlineSkillProblem.UnsafeResource)
            groups.forEachIndexed { index, resources ->
                val prefix = selected[index].directory.takeIf(String::isNotEmpty)?.plus('/').orEmpty()
                resources.forEach { validatePath(it.path.removePrefix(prefix)) }
            }
            if (files.size + selected.size * 2 > 256 || files.any { it.size !in 0..AiSkillArchive.MAX_FILE_BYTES.toLong() } ||
                files.sumOf { it.size } + selected.size * (catalog.licenseBytes?.size ?: 0) > AiSkillArchive.MAX_TOTAL_BYTES)
                throw OnlineSkillException(OnlineSkillProblem.TooLarge)
            val output = ByteArrayOutputStream()
            var completed = 0
            var totalBytes = 0
            ZipOutputStream(output).use { zip ->
                selected.forEachIndexed { index, candidate ->
                    val prefix = candidate.directory.takeIf(String::isNotEmpty)?.plus('/').orEmpty()
                    fun put(relative: String, bytes: ByteArray) {
                        validatePath(relative)
                        totalBytes += bytes.size
                        if (totalBytes > AiSkillArchive.MAX_TOTAL_BYTES) throw OnlineSkillException(OnlineSkillProblem.TooLarge)
                        zip.putNextEntry(ZipEntry("skill-$index/$relative").apply { time = 0 })
                        zip.write(bytes); zip.closeEntry()
                        if (output.size() > AiSkillArchive.MAX_ZIP_BYTES) throw OnlineSkillException(OnlineSkillProblem.TooLarge)
                    }
                    groups[index].forEach { file ->
                        currentCoroutineContext().ensureActive()
                        val relative = file.path.removePrefix(prefix)
                        if (relative == ORIGIN_FILE || relative == LICENSE_FILE) throw OnlineSkillException(OnlineSkillProblem.UnsafeResource)
                        val bytes = transport.fetch(raw(catalog.source, catalog.commit, file.path), AiSkillArchive.MAX_FILE_BYTES)
                        if (bytes.size.toLong() != file.size) throw OnlineSkillException(OnlineSkillProblem.InvalidRepository)
                        verifyBlob(bytes, file.sha)
                        put(relative, bytes)
                        progress(++completed, files.size)
                    }
                    catalog.licenseBytes?.let { put(LICENSE_FILE, it) }
                    put(ORIGIN_FILE, JSONObject().put("version", 1).put("repository", catalog.source.repositoryUrl)
                        .put("commit", catalog.commit).put("directory", candidate.directory)
                        .put("license", catalog.license ?: JSONObject.NULL).put("licensePath", catalog.licenseFile ?: JSONObject.NULL)
                        .toString(2).toByteArray(Charsets.UTF_8))
                }
            }
            val bytes = output.toByteArray()
            val parsed = try { AiSkillArchive.parse(bytes) } catch (_: IllegalArgumentException) {
                throw OnlineSkillException(OnlineSkillProblem.InvalidSkill)
            }
            val validator = AiSkills(AiProjectFiles { error("Online skill validation must not access workspace files") })
            val names = parsed.map { validator.validateMarkdown(it.document).first }
            PreparedOnlineSkills(bytes, names, catalog)
        } } ?: throw OnlineSkillException(OnlineSkillProblem.Network) }

    private suspend fun tree(source: OnlineSkillSource, sha: String, recursive: Boolean): List<JSONObject> {
        val url = api(source, "git", "trees", sha).newBuilder().apply { if (recursive) addQueryParameter("recursive", "1") }.build()
        val result = json(url, 2 * 1024 * 1024)
        if (result.optBoolean("truncated")) throw OnlineSkillException(OnlineSkillProblem.TooLarge)
        val entries = result.getJSONArray("tree")
        if (entries.length() > MAX_TREE_ENTRIES) throw OnlineSkillException(OnlineSkillProblem.TooLarge)
        return List(entries.length()) { entries.getJSONObject(it) }
    }
    private suspend fun json(url: HttpUrl, limit: Int) = JSONObject(AiSkillArchive.decode(transport.fetch(url.toString(), limit)))

    private suspend fun <T> checked(block: suspend () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: OnlineSkillException) { throw failure }
        catch (_: org.json.JSONException) { throw OnlineSkillException(OnlineSkillProblem.InvalidRepository) }
        catch (_: java.nio.charset.CharacterCodingException) { throw OnlineSkillException(OnlineSkillProblem.InvalidRepository) }
        catch (_: IllegalArgumentException) { throw OnlineSkillException(OnlineSkillProblem.InvalidSkill) }
        catch (_: IOException) { throw OnlineSkillException(OnlineSkillProblem.Network) }
        catch (_: Exception) { throw OnlineSkillException(OnlineSkillProblem.InvalidRepository) }

    private fun api(source: OnlineSkillSource, vararg segments: String): HttpUrl = "https://api.github.com".toHttpUrl().newBuilder()
        .addPathSegment("repos").addPathSegment(source.owner).addPathSegment(source.repository).apply { segments.forEach(::addPathSegment) }.build()
    private fun raw(source: OnlineSkillSource, sha: String, path: String): String = "https://raw.githubusercontent.com".toHttpUrl().newBuilder()
        .addPathSegment(source.owner).addPathSegment(source.repository).addPathSegment(sha)
        .apply { path.split('/').forEach(::addPathSegment) }.build().toString()

    private fun verifyBlob(bytes: ByteArray, sha: String) {
        validateSha(sha)
        val digest = MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)); update(bytes) }
            .digest().joinToString("") { "%02x".format(it) }
        if (digest != sha) throw OnlineSkillException(OnlineSkillProblem.InvalidRepository)
    }
    private companion object { const val MAX_CANDIDATES = 128; const val MAX_TREE_ENTRIES = 5000
        const val ORIGIN_FILE = "ZAKOCODER_ORIGIN.json"; const val LICENSE_FILE = "UPSTREAM_LICENSE.txt" }
}

internal data class OnlineSkillState(val loading: Boolean = false, val preparing: Boolean = false, val installing: Boolean = false,
    val catalog: OnlineSkillCatalog? = null, val selected: Set<String> = emptySet(), val prepared: PreparedOnlineSkills? = null,
    val completed: Int = 0, val total: Int = 0, val problem: OnlineSkillProblem? = null, val installed: Boolean = false) {
    val busy: Boolean get() = loading || preparing || installing
}

/** Owns asynchronous discovery/download/install. Composables only observe state and dispatch actions. */
internal class AiOnlineSkillController(private val service: AiOnlineSkills, private val scope: CoroutineScope,
    private val saveArchive: suspend (ByteArray, String) -> Boolean) {
    private val mutable = MutableStateFlow(OnlineSkillState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    @Volatile private var revision = 0
    private var operationId = UUID.randomUUID().toString()
    fun discover(url: String, ref: String = "") {
        if (mutable.value.busy) return
        val generation = ++revision
        operationId = UUID.randomUUID().toString()
        mutable.value = OnlineSkillState(loading = true)
        operation = scope.launch {
            try { val catalog = service.discover(url, ref); update(generation) { it.copy(catalog = catalog) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: OnlineSkillException) { update(generation) { it.copy(problem = failure.problem) } }
            finally { update(generation) { it.copy(loading = false) } }
        }
    }
    fun select(directory: String, selected: Boolean) {
        if (mutable.value.busy || mutable.value.catalog?.candidates?.none { it.directory == directory } != false) return
        if (selected && mutable.value.selected.size >= 32 && directory !in mutable.value.selected) {
            mutable.update { it.copy(problem = OnlineSkillProblem.Selection) }; return
        }
        mutable.update { state -> state.copy(selected = if (selected) state.selected + directory else state.selected - directory,
            prepared = null, installed = false, problem = null) }
    }
    fun prepare() {
        val current = mutable.value
        val catalog = current.catalog ?: return
        if (current.busy) return
        val generation = ++revision
        mutable.value = current.copy(preparing = true, problem = null, prepared = null, completed = 0, total = 0)
        operation = scope.launch {
            try {
                val prepared = service.prepare(catalog, current.selected) { done, total -> update(generation) { it.copy(completed = done, total = total) } }
                operationId = UUID.randomUUID().toString()
                update(generation) { it.copy(prepared = prepared) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: OnlineSkillException) { update(generation) { it.copy(problem = failure.problem) } }
            finally { update(generation) { it.copy(preparing = false) } }
        }
    }
    fun install() {
        val current = mutable.value
        val archive = current.prepared ?: return
        if (current.busy || current.installed) return
        val generation = ++revision
        mutable.value = current.copy(installing = true, problem = null)
        operation = scope.launch {
            try {
                if (!saveArchive(archive.bytes, operationId)) throw OnlineSkillException(OnlineSkillProblem.Install)
                update(generation) { it.copy(installed = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update(generation) { it.copy(problem = OnlineSkillProblem.Install) } }
            finally { update(generation) { it.copy(installing = false) } }
        }
    }
    fun cancel() { ++revision; operation?.cancel(); mutable.update { it.copy(loading = false, preparing = false, installing = false) } }
    fun reset() { cancel(); mutable.value = OnlineSkillState() }
    private fun update(generation: Int, transform: (OnlineSkillState) -> OnlineSkillState) { mutable.update { if (revision == generation) transform(it) else it } }
}

private fun belongs(path: String, directory: String) = directory.isEmpty() || path.startsWith("$directory/")
private fun validateSha(value: String) { require(value.matches(Regex("[0-9a-f]{40}"))) }
private fun validatePath(path: String, sensitive: Boolean = true) {
    if (path.isEmpty() || path.length > 480 || path.startsWith('/') || '\\' in path || ':' in path ||
        path.split('/').let { parts -> parts.size > 10 || parts.any { it.isEmpty() || it in setOf(".", "..") || it.any(Char::isISOControl) || sensitive && isSensitiveProjectName(it) } })
        throw OnlineSkillException(OnlineSkillProblem.UnsafeResource)
}
