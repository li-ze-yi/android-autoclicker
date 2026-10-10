package com.autoclicker.data.repository

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 目录级 JSON 文件存储：每个实体存为一个 <id>.json，写入采用临时文件 + 原子替换。
 */
internal class JsonDirStore(
    private val dir: File,
    private val json: Json,
) {
    fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
    }

    fun fileFor(id: String): File = File(dir, "$id.json")

    fun read(id: String): String? = fileFor(id).takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)

    fun listIds(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?: emptyList()

    fun write(id: String, content: String) {
        ensureDir()
        val target = fileFor(id)
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(content, StandardCharsets.UTF_8)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(content, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }
}