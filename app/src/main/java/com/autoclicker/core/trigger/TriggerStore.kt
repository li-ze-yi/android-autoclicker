package com.autoclicker.core.trigger

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 定时条目持久化仓库（FR-9）。
 *
 * 每个条目以一个 JSON 文件存于内部存储 filesDir/triggers/<id>.json，
 * 与脚本仓库（filesDir/scripts）相互独立。写入采用「临时文件 + rename」，
 * 避免进程被杀时留下半截文件。全部文件 IO 均在 [Dispatchers.IO] 执行（NFR-1）。
 *
 * 序列化使用本地 [Json]（宽松读取：忽略未知字段，便于后续升级格式），
 * 不与领域脚本格式混用。
 */
class TriggerStore(context: Context) {

    private val appContext = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val triggerDir: File by lazy {
        File(appContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /** 列出全部定时条目（坏文件跳过不崩溃，按触发时间升序） */
    suspend fun list(): List<Trigger> = withContext(Dispatchers.IO) {
        val files = triggerDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching { json.decodeFromString<Trigger>(file.readText(Charsets.UTF_8)) }
                .onFailure { Log.w(TAG, "定时条目文件损坏，已跳过：${file.name}") }
                .getOrNull()
        }.sortedBy { it.triggerAtMs }
    }

    /** 按 ID 获取单个条目，不存在或损坏返回 null */
    suspend fun get(id: String): Trigger? = withContext(Dispatchers.IO) {
        val file = fileOf(id)
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<Trigger>(file.readText(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "定时条目读取失败：$id") }
            .getOrNull()
    }

    /** 新增或更新条目（按 [Trigger.id] 覆盖） */
    suspend fun upsert(trigger: Trigger): Unit = withContext(Dispatchers.IO) {
        triggerDir.mkdirs()
        val target = fileOf(trigger.id)
        val tmp = File(triggerDir, "${trigger.id}.${UUID.randomUUID()}.tmp")
        try {
            tmp.writeText(json.encodeToString(trigger), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                // 个别机型 rename 失败时退回直接覆盖写
                target.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            }
        } finally {
            tmp.delete()
        }
    }

    /** 删除条目（文件不存在也视为成功） */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        fileOf(id).delete(); Unit
    }

    /** 生成新条目 ID */
    fun newId(): String = "trigger-${UUID.randomUUID()}"

    private fun fileOf(id: String): File = File(triggerDir, "$id.json")

    private companion object {
        const val DIR_NAME = "triggers"
        const val TAG = "TriggerStore"
    }
}
