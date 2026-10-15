package com.autoclicker.data.repository

import com.autoclicker.domain.codec.DomainJson
import com.autoclicker.domain.model.ImageTemplateGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** 图像模板组仓库的文件实现。 */
class FileTemplateGroupRepository(baseDir: File) : TemplateGroupRepository {

    private val store = JsonDirStore(File(baseDir, "templategroups"), DomainJson.instance)

    override suspend fun list(): List<ImageTemplateGroup> = withContext(Dispatchers.IO) {
        store.listIds().mapNotNull { id ->
            store.read(id)?.let {
                runCatching {
                    DomainJson.instance.decodeFromString(ImageTemplateGroup.serializer(), it)
                }.getOrNull()
            }
        }.sortedBy { it.name }
    }

    override suspend fun get(id: String): ImageTemplateGroup? = withContext(Dispatchers.IO) {
        store.read(id)?.let {
            runCatching {
                DomainJson.instance.decodeFromString(ImageTemplateGroup.serializer(), it)
            }.getOrNull()
        }
    }

    override suspend fun save(group: ImageTemplateGroup) = withContext(Dispatchers.IO) {
        store.write(group.id, DomainJson.instance.encodeToString(ImageTemplateGroup.serializer(), group))
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete(id)
    }
}
