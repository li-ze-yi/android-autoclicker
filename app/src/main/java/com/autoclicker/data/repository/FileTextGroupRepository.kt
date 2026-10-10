package com.autoclicker.data.repository

import com.autoclicker.domain.codec.DomainJson
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.TextGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** 文本组仓库的文件实现。 */
class FileTextGroupRepository(baseDir: File) : TextGroupRepository {

    private val store = JsonDirStore(File(baseDir, "textgroups"), DomainJson.instance)

    override suspend fun list(): List<TextGroup> = withContext(Dispatchers.IO) {
        store.listIds().mapNotNull { id ->
            store.read(id)?.let {
                runCatching { DomainJson.instance.decodeFromString(TextGroup.serializer(), it) }.getOrNull()
            }
        }.sortedBy { it.name }
    }

    override suspend fun get(id: String): TextGroup? = withContext(Dispatchers.IO) {
        store.read(id)?.let {
            runCatching { DomainJson.instance.decodeFromString(TextGroup.serializer(), it) }.getOrNull()
        }
    }

    override suspend fun save(group: TextGroup) = withContext(Dispatchers.IO) {
        store.write(group.id, DomainJson.instance.encodeToString(TextGroup.serializer(), group))
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete(id)
    }

    @Suppress("unused")
    private fun newId() = Ids.newId()
}