package com.autoclicker.data.repository

import com.autoclicker.domain.codec.FunctionPackageCodec
import com.autoclicker.domain.codec.DomainJson
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 函数包仓库的文件实现。 */
class FileFunctionPackageRepository(baseDir: File) : FunctionPackageRepository {

    private val store = JsonDirStore(File(baseDir, "packages"), DomainJson.instance)

    override suspend fun list(): List<FunctionPackage> = withContext(Dispatchers.IO) {
        store.listIds().mapNotNull { id ->
            store.read(id)?.let { runCatching { FunctionPackageCodec.decode(it) }.getOrNull() }
        }.sortedBy { it.name }
    }

    override suspend fun get(id: String): FunctionPackage? = withContext(Dispatchers.IO) {
        store.read(id)?.let { runCatching { FunctionPackageCodec.decode(it) }.getOrNull() }
    }

    override suspend fun save(pkg: FunctionPackage) = withContext(Dispatchers.IO) {
        val updated = pkg.copy(updatedAt = System.currentTimeMillis())
        store.write(updated.id, FunctionPackageCodec.encode(updated))
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete(id)
    }

    override suspend fun exportJson(id: String): String? = withContext(Dispatchers.IO) {
        get(id)?.let { FunctionPackageCodec.encode(it) }
    }

    override suspend fun importJson(json: String): FunctionPackage = withContext(Dispatchers.IO) {
        val pkg = FunctionPackageCodec.decode(json).copy(id = Ids.newId())
        save(pkg)
        pkg
    }
}