package com.autoclicker.data.repository

import com.autoclicker.domain.model.Script

/** 任务（脚本）仓库。 */
interface ScriptRepository {
    suspend fun list(): List<Script>

    suspend fun get(id: String): Script?

    suspend fun save(script: Script)

    suspend fun delete(id: String)

    /** 生成分享码（Base64 的 JSON 摘要）。 */
    suspend fun exportShareCode(id: String): String?

    /** 由分享码导入任务，返回新任务。 */
    suspend fun importShareCode(code: String): Script

    suspend fun exportJson(id: String): String?

    suspend fun importJson(json: String): Script
}