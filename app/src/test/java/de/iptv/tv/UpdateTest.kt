package de.iptv.tv

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val cert = "a".repeat(64)
    private val payload = "signed APK fixture".toByteArray()
    private fun release(version: String = "0.5.9", code: Long = 20) = UpdateRelease("v$version", version, code,
        23, listOf("arm64-v8a", "armeabi-v7a", "x86_64"), "RUSTREAM-IPTV-PLAYER-$version.apk",
        payload.size.toLong(), UpdateTrust.digest(payload), cert, listOf("Bessere Bedienung"), listOf("Improved navigation"))
    private fun signed(bytes: ByteArray) = Signature.getInstance("SHA256withRSA").run {
        initSign(keys.private); update(bytes); sign()
    }
    private fun discovery(releases: List<UpdateRelease>) = JSONArray(releases.map { release ->
        JSONObject().put("tag_name", release.tag).put("draft", false).put("prerelease", true)
            .put("assets", JSONArray(listOf("update.json", "update.json.sig", release.asset).map { name ->
                JSONObject().put("name", name).put("state", "uploaded").put("size", if (name == release.asset) release.size else 1)
            }))
    }).toString()
    private fun repository(releases: List<UpdateRelease>, transform: (String, UpdateResponse) -> UpdateResponse = { _, r -> r }): UpdateRepository {
        val responses = mutableMapOf<String, UpdateResponse>()
        responses["${UpdateRepository.API}?per_page=100&page=1"] = UpdateResponse(200, discovery(releases).toByteArray())
        releases.forEach {
            val bytes = it.toJson().toString().toByteArray()
            responses[UpdateRepository.assetUrl(it.tag, "update.json")] = UpdateResponse(200, bytes)
            responses[UpdateRepository.assetUrl(it.tag, "update.json.sig")] = UpdateResponse(200, Base64.getEncoder().encode(signed(bytes)))
        }
        return UpdateRepository(keys.public, cert, { url, _, _ -> transform(url, checkNotNull(responses[url])) }, { null }, { _, _, _ -> })
    }
    private fun check(repo: UpdateRepository) = repo.check("0.5.8", 19, 36, listOf("arm64-v8a"))

    @Test fun semanticOrderingIncludesNumericPrereleases() {
        val versions = listOf("0.5.9-alpha.1", "0.5.9-alpha.10", "0.5.9-beta.2", "0.5.9-beta.10", "0.5.9-rc.1", "0.5.9", "0.5.10", "0.6.0", "1.0.0")
        assertEquals(versions, versions.reversed().sortedBy { UpdateVersion.parse(it) })
        listOf("01.2.3", "v1.2.3", "1.2", "1.2.3-beta.01", "1.2.3-unknown.1", "1.2.3+build").forEach { assertNull(it, UpdateVersion.parse(it)) }
    }
    @Test fun developmentReleaseWithoutBetaSuffixIsOffered() {
        assertEquals("0.5.9", check(repository(listOf(release())))!!.versionName)
    }
    @Test fun scheduleThrottlesForegroundReentryButAllowsManualRetryAfterNetworkFailure() {
        val now = 100_000_000L
        assertFalse(UpdateSchedule.due(now, now - 1000, 0, 0, false))
        assertTrue(UpdateSchedule.due(now, now - UpdateController.CHECK_INTERVAL, 0, 0, false))
        assertTrue(UpdateSchedule.due(now, now + 1000, 0, 0, false)) // Clock moved back.
        assertFalse(UpdateSchedule.due(now, 0, now + 900_000, 0, false))
        assertTrue(UpdateSchedule.due(now, 0, now + 900_000, 0, true))
        assertFalse(UpdateSchedule.due(now, 0, 0, now + 60_000, true))
    }
    @Test fun orderingUsesVersionCodeAndNeverPublicationOrder() {
        assertEquals(22L, check(repository(listOf(release("0.5.11", 22), release("0.5.9", 20), release("0.5.10", 21))))!!.versionCode)
    }
    @Test fun equalOrLowerCodesAndIncompatibleDevicesAreSkipped() {
        assertNull(check(repository(listOf(release(code = 19)))))
        assertNull(repository(listOf(release())).check("0.5.8", 19, 22, listOf("arm64-v8a")))
        assertNull(repository(listOf(release())).check("0.5.8", 19, 36, listOf("x86")))
        assertNull(repository(listOf(release("0.5.8", 30))).check("0.5.8", 19, 36, listOf("arm64-v8a")))
    }
    @Test fun signatureDetectsModifiedNotesAndForeignPublisher() {
        val bytes = release().toJson().toString().toByteArray()
        assertTrue(UpdateTrust.verify(bytes, signed(bytes), keys.public))
        assertFalse(UpdateTrust.verify(bytes + 32.toByte(), signed(bytes), keys.public))
        val foreign = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(UpdateTrust.verify(bytes, signed(bytes), foreign.public))
        try {
            check(repository(listOf(release())) { url, response ->
                if (url.endsWith("update.json")) response.copy(bytes = bytes + 32.toByte()) else response
            }); fail("Tampering must be rejected")
        } catch (error: UpdateException) { assertEquals(UpdateProblem.INVALID, error.problem) }
    }
    @Test fun cachedDiscoveryStillChecksSignedManifestAndRateLimitSurvives() {
        val raw = discovery(listOf(release()))
        val normal = repository(listOf(release()))
        var manifestRequested = false
        val bytes = release().toJson().toString().toByteArray()
        val repo = UpdateRepository(keys.public, cert, { url, etag, _ ->
            if (url.startsWith(UpdateRepository.API)) { assertEquals("etag", etag); UpdateResponse(304, byteArrayOf()) }
            else { manifestRequested = true; UpdateResponse(200, if (url.endsWith(".sig")) Base64.getEncoder().encode(signed(bytes)) else bytes) }
        }, { "etag" to raw }, { _, _, _ -> fail("304 must not replace discovery cache") })
        assertEquals(check(normal), check(repo)); assertTrue(manifestRequested)
        try { check(repository(listOf(release())) { _, _ -> UpdateResponse(429, byteArrayOf(), retryAt = 1234) }); fail() }
        catch (error: UpdateException) { assertEquals(UpdateProblem.RATE_LIMIT, error.problem); assertEquals(1234, error.retryAt) }
    }
    @Test fun manifestRejectsUnboundedOrUnsafeInputs() {
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("packageId", "foreign.app") }, { it.put("channel", "stable") }, { it.put("schema", 2) },
            { it.put("versionCode", 0) }, { it.put("tag", "v0.5.10") },
            { it.getJSONObject("apk").put("size", UpdateRelease.MAX_APK + 1) },
            { it.getJSONObject("apk").put("name", "../other.apk") },
            { it.getJSONObject("notes").put("de", JSONArray(listOf("x".repeat(501)))) })
        for (change in changes) {
            try { UpdateRelease.parse(release().toJson().also(change)); fail("Unsafe manifest accepted") }
            catch (error: UpdateException) { assertEquals(UpdateProblem.INVALID, error.problem) }
        }
    }
    @Test fun transportRejectsHttpCredentialsPortsAndLookalikeHosts() {
        assertTrue(UpdateHttp.allowed(UpdateRepository.assetUrl("v0.5.9", "update.json")))
        assertTrue(UpdateHttp.allowed("https://release-assets.githubusercontent.com/signed-download?token=redacted"))
        listOf("http://github.com/ninos-apk/RUSTREAM/releases/download/v0.5.9/update.json",
            "https://github.com.evil.invalid/ninos-apk/RUSTREAM/releases/download/v0.5.9/update.json",
            "https://github.com/other/repo/releases/download/v1.0.0/update.json",
            "https://user@github.com/ninos-apk/RUSTREAM/releases/download/v0.5.9/update.json",
            "https://github.com:8443/ninos-apk/RUSTREAM/releases/download/v0.5.9/update.json").forEach { assertFalse(it, UpdateHttp.allowed(it)) }
    }
    @Test fun downloadChecksLengthHashAndDeletesTruncatedFiles() {
        val destination = File.createTempFile("update", ".apk")
        try {
            fun transport(bytes: ByteArray) = UpdateHttp(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bytes.toResponseBody()).build()
            }).build())
            transport(payload).download(release(), destination, {}, {})
            assertArrayEquals(payload, destination.readBytes())
            try { transport(payload.copyOf(payload.size - 1)).download(release(), destination, {}, {}); fail() }
            catch (error: UpdateException) { assertEquals(UpdateProblem.INVALID, error.problem) }
            assertFalse(destination.exists())
            val corrupt = payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            try { transport(corrupt).download(release(), destination, {}, {}); fail("Same-size corruption must fail hash verification") }
            catch (error: UpdateException) { assertEquals(UpdateProblem.INVALID, error.problem) }
            assertFalse(destination.exists())
        } finally { destination.delete() }
    }
}
