package de.iptv.tv

import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

/** The same deliberately small version grammar as check-release-tag.ps1. */
data class UpdateVersion(val major: BigInteger, val minor: BigInteger, val patch: BigInteger,
                         val stage: Int, val sequence: BigInteger) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int = compareValuesBy(this, other,
        { it.major }, { it.minor }, { it.patch }, { it.stage }, { it.sequence })
    companion object {
        private val grammar = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\\.(0|[1-9][0-9]*))?$")
        fun parse(text: String): UpdateVersion? {
            if (text.length > 100) return null
            val m = grammar.matchEntire(text) ?: return null
            return UpdateVersion(m.groupValues[1].toBigInteger(), m.groupValues[2].toBigInteger(),
                m.groupValues[3].toBigInteger(), listOf("alpha", "beta", "rc", "").indexOf(m.groupValues[4]),
                m.groupValues[5].ifEmpty { "0" }.toBigInteger())
        }
    }
}

enum class UpdateProblem { NETWORK, RATE_LIMIT, INVALID, STORAGE, DOWNLOAD, INSTALL, PERMISSION }
class UpdateException(val problem: UpdateProblem, val retryAt: Long = 0) : Exception(problem.name)

data class UpdateRelease(val tag: String, val versionName: String, val versionCode: Long,
    val minSdk: Int, val abis: List<String>, val asset: String, val size: Long, val sha256: String,
    val certificate: String, val notesDe: List<String>, val notesEn: List<String>) {
    fun notes(language: String) = if (language == "de") notesDe else notesEn
    fun toJson(): JSONObject = JSONObject().put("schema", 1).put("packageId", "de.iptv.tv")
        .put("channel", "development").put("tag", tag).put("versionName", versionName)
        .put("versionCode", versionCode).put("minSdk", minSdk).put("abis", org.json.JSONArray(abis))
        .put("apk", JSONObject().put("name", asset).put("size", size).put("sha256", sha256))
        .put("signingCertificateSha256", certificate)
        .put("notes", JSONObject().put("de", org.json.JSONArray(notesDe)).put("en", org.json.JSONArray(notesEn)))

    companion object {
        const val MAX_APK = 256L * 1024 * 1024
        fun parse(json: JSONObject): UpdateRelease {
            try {
                require(json.getInt("schema") == 1 && json.getString("packageId") == "de.iptv.tv")
                require(json.getString("channel") == "development")
                val version = json.getString("versionName")
                require(UpdateVersion.parse(version) != null && json.getString("tag") == "v$version")
                val code = json.getLong("versionCode"); require(code in 1..2100000000L)
                val sdk = json.getInt("minSdk"); require(sdk in 23..1000)
                val abi = json.getJSONArray("abis"); require(abi.length() in 1..3)
                val abis = (0 until abi.length()).map { abi.getString(it) }
                require(abis.all { it in listOf("arm64-v8a", "armeabi-v7a", "x86_64") })
                val apk = json.getJSONObject("apk")
                val asset = apk.getString("name"); require(asset == "RUSTREAM-IPTV-PLAYER-$version.apk")
                val size = apk.getLong("size"); require(size in 1..MAX_APK)
                val hash = apk.getString("sha256"); val cert = json.getString("signingCertificateSha256")
                require(hash.matches(Regex("[0-9a-f]{64}")) && cert.matches(Regex("[0-9a-f]{64}")))
                fun notes(language: String): List<String> {
                    val array = json.getJSONObject("notes").getJSONArray(language)
                    require(array.length() in 1..12)
                    return (0 until array.length()).map { array.getString(it).also { line ->
                        require(line.isNotBlank() && line.length <= 500 && line.none { it.isISOControl() })
                    } }
                }
                return UpdateRelease("v$version", version, code, sdk, abis, asset, size, hash, cert, notes("de"), notes("en"))
            } catch (_: Exception) { throw UpdateException(UpdateProblem.INVALID) }
        }
    }
}

object UpdateTrust {
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun verify(bytes: ByteArray, signature: ByteArray, key: PublicKey): Boolean = try {
        val algorithm = when (key.algorithm) { "RSA" -> "SHA256withRSA"; "EC" -> "SHA256withECDSA"; else -> error("Unsupported key") }
        Signature.getInstance(algorithm).run { initVerify(key); update(bytes); verify(signature) }
    } catch (_: Exception) { false }
}
