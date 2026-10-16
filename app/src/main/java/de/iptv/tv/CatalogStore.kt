package de.iptv.tv

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Manifest and per-source AES keys are themselves sealed by SourceStore's Keystore key. */
class CatalogStore(context: Context) {
    companion object { private val startupLock=Any(); private var startupCleaned=false }
    private val manifest = SourceStore(context, "catalog-manifest.enc")
    val directory = File(context.noBackupFilesDir,"catalogs").apply { mkdirs() }
    private val entries = manifest.load()
    init {
        synchronized(startupLock){
            if(!startupCleaned){
                startupCleaned=true
                val retained=entries.flatMap{listOf(it.optString("file"),it.optString("previous"))}.toSet()
                // Once per process, before any SourceLoader can create a staging generation.
                directory.listFiles()?.filter{it.name.endsWith(".db")&&it.name !in retained}?.forEach{file->
                    safeFile(file.name)?.delete();File(file.absolutePath+"-journal").delete()
                }
            }
        }
    }
    fun id(source: JSONObject): String {
        val text = when(source.getString("kind")) {
            "file" -> "file\n${source.getString("uri")}"
            "url" -> "url\n${source.getString("url")}"
            else -> "xtream\n${source.getString("server")}\n${source.getString("username")}\n${source.getString("password")}\n${source.optBoolean("vod",true)}"
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun entry(source: JSONObject): JSONObject? = entries.firstOrNull { it.optString("id") == id(source) }
        ?.takeIf { it.optInt("schema") == 1 && safeFile(it.optString("file"))?.isFile == true }
        ?.let { JSONObject(it.toString()) }
    fun safeFile(name: String): File? = File(directory,name).takeIf { name.matches(Regex("[a-f0-9-]+\\.db")) && it.canonicalFile.parentFile == directory.canonicalFile }
    fun newFile(): File = File(directory,"${UUID.randomUUID()}.db")
    fun newKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Synchronized fun commit(source: JSONObject, file: File, key: String, validators: JSONObject, partial: Boolean, warnings:org.json.JSONArray=org.json.JSONArray()): JSONObject {
        val id = id(source)
        val old=entries.firstOrNull { it.optString("id")==id }
        val record = JSONObject().put("id",id).put("schema",1).put("file",file.name).put("key",key)
            .put("updated",System.currentTimeMillis()).put("validators",validators).put("partial",partial).put("warnings",warnings)
        old?.let{entry->
            val previous=if(entry.optString("file")==file.name)entry.optString("previous") else entry.optString("file")
            if(previous.isNotEmpty())record.put("previous",previous)
        }
        val updated = entries.filter { it.optString("id") != id } + record
        manifest.save(updated)
        entries.clear(); entries.addAll(updated)
        cleanup(listOfNotNull(old?.optString("file"),old?.optString("previous")))
        return record
    }
    @Synchronized fun remove(source: JSONObject) {
        val removed=entries.firstOrNull{it.optString("id")==id(source)}
        val updated = entries.filter { it.optString("id") != id(source) }
        manifest.save(updated); entries.clear(); entries.addAll(updated); cleanup(listOfNotNull(removed?.optString("file"),removed?.optString("previous")))
    }
    @Synchronized private fun cleanup(obsolete:List<String>) {
        val retained = entries.flatMap { listOf(it.optString("file"),it.optString("previous")) }.toSet()
        // Never sweep an uncommitted generation belonging to another import worker.
        obsolete.filter{it !in retained}.forEach{safeFile(it)?.delete()}
    }
}
