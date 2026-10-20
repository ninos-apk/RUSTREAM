package de.iptv.tv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

data class UpdateResponse(val code: Int, val bytes: ByteArray, val etag: String? = null,
                          val retryAt: Long = 0, val hasNext: Boolean = false)

/** Dedicated transport: IPTV's permissive HTTP policy must never reach the updater. */
class UpdateHttp(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build()) {
    fun cancel() = client.dispatcher.cancelAll()

    private fun response(url: String, etag: String? = null): okhttp3.Response {
        var target = url
        repeat(6) {
            if (!allowed(target)) throw UpdateException(UpdateProblem.INVALID)
            val request = Request.Builder().url(target).header("User-Agent", "RuStream-AndroidTV")
                .header("Accept", "application/vnd.github+json")
            if (etag != null && target == url) request.header("If-None-Match", etag)
            val response = client.newCall(request.build()).execute()
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                response.close()
                target = next ?: throw UpdateException(UpdateProblem.INVALID)
            } else return response
        }
        throw UpdateException(UpdateProblem.INVALID)
    }

    fun get(url: String, etag: String?, limit: Int): UpdateResponse = response(url, etag).use { response ->
        val retry = response.header("Retry-After")?.toLongOrNull()?.let { System.currentTimeMillis() + it.coerceIn(1, 86400) * 1000 }
            ?: response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000) ?: 0
        val bytes = if (response.code == 200) {
            val body = response.body ?: throw UpdateException(UpdateProblem.NETWORK)
            if (body.contentLength() > limit) throw UpdateException(UpdateProblem.INVALID)
            body.byteStream().use { it.readBytesBounded(limit) }
        } else byteArrayOf()
        UpdateResponse(response.code, bytes, response.header("ETag"), retry,
            response.header("Link")?.contains("rel=\"next\"") == true)
    }

    fun download(release: UpdateRelease, destination: File, progress: (Long) -> Unit, active: () -> Unit) {
        try {
            response(UpdateRepository.assetUrl(release.tag, release.asset)).use { response ->
                if (response.code != 200) throw UpdateException(UpdateProblem.DOWNLOAD)
                val body = response.body ?: throw UpdateException(UpdateProblem.DOWNLOAD)
                if (body.contentLength() >= 0 && body.contentLength() != release.size) throw UpdateException(UpdateProblem.INVALID)
                val hash = MessageDigest.getInstance("SHA-256")
                var count = 0L
                body.byteStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        active()
                        val n = input.read(buffer); if (n < 0) break
                        count += n
                        if (count > release.size) throw UpdateException(UpdateProblem.INVALID)
                        hash.update(buffer, 0, n); output.write(buffer, 0, n); progress(count)
                    }
                    output.fd.sync()
                } }
                val actual = hash.digest().joinToString("") { "%02x".format(it) }
                if (count != release.size || actual != release.sha256) throw UpdateException(UpdateProblem.INVALID)
            }
        } catch (error: Exception) { destination.delete(); throw error }
    }

    companion object {
        fun allowed(value: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return false
            if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
            return when (url.host) {
                "api.github.com" -> url.encodedPath == "/repos/ninos-apk/RUSTREAM/releases" || url.encodedPath.startsWith("/repos/ninos-apk/RUSTREAM/releases/")
                "github.com" -> url.encodedPath.startsWith("/ninos-apk/RUSTREAM/releases/download/")
                "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
                else -> false
            }
        }
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) return output.toByteArray()
        if (output.size() + n > limit) throw UpdateException(UpdateProblem.INVALID)
        output.write(buffer, 0, n)
    }
}

/** Cached API responses are discovery hints only. A manifest is always signature checked. */
class UpdateRepository(private val publicKey: PublicKey, private val certificate: String,
    private val fetch: (String, String?, Int) -> UpdateResponse,
    private val readCache: (String) -> Pair<String?, String>?,
    private val writeCache: (String, String?, String) -> Unit,
    private val onSelected: (ByteArray, ByteArray) -> Unit = { _, _ -> }) {
    fun check(installedName: String, installedCode: Long, sdk: Int, abis: List<String>): UpdateRelease? {
        val installed = UpdateVersion.parse(installedName) ?: throw UpdateException(UpdateProblem.INVALID)
        val candidates = mutableListOf<JSONObject>()
        for (page in 1..10) {
            val url = "$API?per_page=100&page=$page"
            val cached = readCache(url)
            val reply = fetch(url, cached?.first, 1024 * 1024)
            val raw = when (reply.code) {
                200 -> reply.bytes.toString(Charsets.UTF_8)
                304 -> cached?.second ?: throw UpdateException(UpdateProblem.NETWORK)
                403, 429 -> throw UpdateException(UpdateProblem.RATE_LIMIT, reply.retryAt)
                else -> throw UpdateException(UpdateProblem.NETWORK)
            }
            val array = JSONArray(raw)
            if (reply.code == 200) writeCache(url, reply.etag, raw)
            for (index in 0 until array.length()) {
                val release = array.getJSONObject(index)
                val tag = release.optString("tag_name")
                val version = if (tag.startsWith("v")) UpdateVersion.parse(tag.drop(1)) else null
                if (!release.optBoolean("draft", true) && version != null && version > installed) candidates.add(release)
            }
            // A full cached page still needs the next page checked: Link is absent on some 304s.
            if (array.length() < 100 && !reply.hasNext) break
            if (page == 10) throw UpdateException(UpdateProblem.INVALID)
        }
        var invalid = false
        // CI enforces increasing version names and codes. Inspect newest first to keep discovery
        // to one API request plus two small assets in the normal case, even after many missed releases.
        for (candidate in candidates.sortedByDescending { UpdateVersion.parse(it.getString("tag_name").drop(1)) }) {
            val assets = candidate.optJSONArray("assets") ?: continue
            val tag = candidate.getString("tag_name")
            fun asset(name: String): JSONObject? = (0 until assets.length()).map { assets.getJSONObject(it) }
                .singleOrNull { it.optString("name") == name && it.optString("state") == "uploaded" }
            if (asset("update.json") == null || asset("update.json.sig") == null) continue // Older releases need no retrofit.
            try {
                fun read(name: String, limit: Int): ByteArray {
                    val reply = fetch(assetUrl(tag, name), null, limit)
                    if (reply.code == 403 || reply.code == 429) throw UpdateException(UpdateProblem.RATE_LIMIT, reply.retryAt)
                    if (reply.code != 200) throw UpdateException(UpdateProblem.NETWORK)
                    return reply.bytes
                }
                val bytes = read("update.json", 64 * 1024)
                val signature = Base64.getDecoder().decode(read("update.json.sig", 8192).toString(Charsets.US_ASCII).trim())
                if (!UpdateTrust.verify(bytes, signature, publicKey)) throw UpdateException(UpdateProblem.INVALID)
                val release = UpdateRelease.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
                val apk = asset(release.asset) ?: throw UpdateException(UpdateProblem.INVALID)
                if (release.tag != tag || release.certificate != certificate || apk.getLong("size") != release.size)
                    throw UpdateException(UpdateProblem.INVALID)
                if (release.versionCode > installedCode && release.minSdk <= sdk && release.abis.any { it in abis }) {
                    onSelected(bytes, signature)
                    return release
                }
            } catch (error: UpdateException) {
                if (error.problem != UpdateProblem.INVALID) throw error
                invalid = true
            } catch (_: IllegalArgumentException) { invalid = true }
              catch (_: org.json.JSONException) { invalid = true }
        }
        if (invalid) throw UpdateException(UpdateProblem.INVALID)
        return null
    }
    companion object {
        const val API = "https://api.github.com/repos/ninos-apk/RUSTREAM/releases"
        fun assetUrl(tag: String, name: String): String {
            require(tag.startsWith("v") && UpdateVersion.parse(tag.drop(1)) != null)
            require(name in listOf("update.json", "update.json.sig", "RUSTREAM-IPTV-PLAYER-${tag.drop(1)}.apk"))
            return "https://github.com/ninos-apk/RUSTREAM/releases/download/$tag/$name"
        }
    }
}
