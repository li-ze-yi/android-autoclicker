package com.autoclicker.core.script

import android.content.Context
import java.io.File

class ScriptRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ScriptRepository? = null

        fun get(context: Context): ScriptRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScriptRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val scriptsDir: File = File(context.applicationContext.filesDir, "scripts").apply { mkdirs() }

    fun listScripts(): List<Script> {
        val files = scriptsDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                ScriptSerializer.decode(file.readText())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): Script? {
        return try {
            val file = File(scriptsDir, "$id.json")
            if (!file.exists()) return null
            ScriptSerializer.decode(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun save(script: Script): Script {
        val updated = script.copy(updatedAt = System.currentTimeMillis())
        try {
            File(scriptsDir, "${updated.id}.json").writeText(ScriptSerializer.encode(updated))
        } catch (e: Exception) {
            // 写入失败时仍返回对象
        }
        return updated
    }

    fun delete(id: String): Boolean {
        return try {
            val file = File(scriptsDir, "$id.json")
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            false
        }
    }

    fun rename(id: String, newName: String): Script? {
        val existing = load(id) ?: return null
        return save(existing.copy(name = newName))
    }

    fun createNew(name: String = "新脚本"): Script = save(Script.create(name))
}