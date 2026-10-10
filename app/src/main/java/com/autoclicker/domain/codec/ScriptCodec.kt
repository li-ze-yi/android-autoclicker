package com.autoclicker.domain.codec

import com.autoclicker.domain.model.Script
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Script <-> JSON 编解码。 */
object ScriptCodec {
    fun encode(script: Script): String =
        DomainJson.instance.encodeToString(script)

    fun decode(json: String): Script = try {
        DomainJson.instance.decodeFromString(Script.serializer(), json)
    } catch (e: Exception) {
        throw ScriptDataException("脚本解析失败：${e.message}", e)
    }
}