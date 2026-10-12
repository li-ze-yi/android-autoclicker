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
        // 临时名必须唯一：同一 id 被编辑器与录制自动保存并发写入时，固定名会互相覆盖导致内容截断。
        val tmp = File(dir, "$id.${System.nanoTime()}.tmp")
        tmp.writeText(content, StandardCharsets.UTF_8)
        // Linux 上 rename 会原子替换目标，不能先 delete——那会留下「文件不存在」的窗口。
        if (!tmp.renameTo(target)) {
            target.writeText(content, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }
}