package com.autoclicker.data.repository

import com.autoclicker.domain.codec.DomainJson
import com.autoclicker.domain.codec.ScriptCodec
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.Script
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64

/** 脚本仓库的文件实现。 */
class FileScriptRepository(baseDir: File) : ScriptRepository {

    private val store = JsonDirStore(File(baseDir, "scripts"), DomainJson.instance)

    @Serializable
    private data class ShareBundle(
        val name: String,
        val versionName: String = "1.0",
        val recommended: com.autoclicker.domain.model.ScreenProfile? = null,
        val nodes: List<com.autoclicker.domain.model.ScriptNode>,
        val launchType: com.autoclicker.domain.model.LaunchType =
            com.autoclicker.domain.model.LaunchType.MANUAL,
        val launchPackage: String? = null,
    )

    override suspend fun list(): List<Script> = withContext(Dispatchers.IO) {
        store.listIds().mapNotNull { id ->
            store.read(id)?.let { runCatching { ScriptCodec.decode(it) }.getOrNull() }
        }.sortedByDescending { it.updatedAt }
    }

    override suspend fun get(id: String): Script? = withContext(Dispatchers.IO) {
        store.read(id)?.let { runCatching { ScriptCodec.decode(it) }.getOrNull() }
    }

    override suspend fun save(script: Script) = withContext(Dispatchers.IO) {
        val updated = script.copy(updatedAt = System.currentTimeMillis())
        store.write(updated.id, ScriptCodec.encode(updated))
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete(id)
    }

    override suspend fun exportShareCode(id: String): String? = withContext(Dispatchers.IO) {
        val script = get(id) ?: return@withContext null
        val bundle = ShareBundle(
            name = script.name,
            versionName = script.versionName,
            recommended = script.recommended,
            nodes = script.nodes,
            launchType = script.launchType,
            launchPackage = script.launchPackage,
        )
        val json = DomainJson.instance.encodeToString(bundle)
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    override suspend fun importShareCode(code: String): Script = withContext(Dispatchers.IO) {
        val json = String(
            Base64.getUrlDecoder().decode(code.trim()),
            Charsets.UTF_8,
        )
        val bundle = DomainJson.instance.decodeFromString(ShareBundle.serializer(), json)
        val script = Script(
            id = Ids.newId(),
            name = bundle.name,
            versionName = bundle.versionName,
            recommended = bundle.recommended,
            nodes = bundle.nodes,
            launchType = bundle.launchType,
            launchPackage = bundle.launchPackage,
        )
        save(script)
        script
    }

    override suspend fun exportJson(id: String): String? = withContext(Dispatchers.IO) {
        get(id)?.let { ScriptCodec.encode(it) }
    }

    override suspend fun importJson(json: String): Script = withContext(Dispatchers.IO) {
        val script = ScriptCodec.decode(json).copy(id = Ids.newId())
        save(script)
        script
    }
}