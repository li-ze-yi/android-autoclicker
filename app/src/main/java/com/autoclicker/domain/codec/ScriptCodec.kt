package com.autoclicker.domain.codec

import com.autoclicker.domain.model.Script
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 脚本 JSON 编解码器（对应 FR-6 / AC-9）。
 *
 * 外层信封格式：
 * `{ "schemaVersion": 2, "script": { ... } }`
 *
 * 版本不匹配或 JSON 损坏时抛 [ScriptDataException]（中文消息），不返回空数据、不崩溃。
 */
object ScriptCodec {

    /** 脚本外层信封（仅编解码内部使用） */
    @Serializable
    private data class Envelope(
        val schemaVersion: Int,
        val script: Script,
    )

    /** 把 [script] 编码为带 schemaVersion 的 JSON 字符串 */
    fun encode(script: Script): String = try {
        domainJson.encodeToString(Envelope(SCHEMA_VERSION, script))
    } catch (e: SerializationException) {
        throw ScriptDataException("脚本序列化失败：${e.message ?: "未知错误"}", e)
    }

    /**
     * 解析 JSON 字符串为 [Script]。
     *
     * @throws ScriptDataException JSON 损坏、缺少/非法 schemaVersion、版本不支持或内容结构非法时
     */
    fun decode(text: String): Script {
        val root = parseRootObject(text, "脚本")
        val version = readSchemaVersion(root, "脚本")
        if (version != SCHEMA_VERSION) {
            throw ScriptDataException(
                "脚本数据版本不受支持：当前版本号为 $version，本版本仅支持版本 $SCHEMA_VERSION"
            )
        }
        val envelope = try {
            domainJson.decodeFromJsonElement<Envelope>(root)
        } catch (e: SerializationException) {
            throw ScriptDataException("脚本数据内容已损坏或结构不正确，无法解析：${e.message ?: "未知错误"}", e)
        }
        return envelope.script
    }
}

/** 把文本解析为顶层 JSON 对象，失败统一转成带中文消息的 [ScriptDataException] */
internal fun parseRootObject(text: String, dataLabel: String): JsonObject {
    val element = try {
        domainJson.parseToJsonElement(text)
    } catch (e: Exception) {
        // 兜底：除 SerializationException 外，部分非法输入（如 "<<<"）可能抛出
        // IllegalArgumentException 等，一律视为数据损坏。
        throw ScriptDataException("${dataLabel}数据已损坏，不是有效的 JSON：${e.message ?: "未知错误"}", e)
    }
    return element as? JsonObject
        ?: throw ScriptDataException("${dataLabel}数据已损坏：顶层必须是 JSON 对象")
}

/** 从顶层对象读取 schemaVersion；缺失或类型非法时抛 [ScriptDataException] */
internal fun readSchemaVersion(root: JsonObject, dataLabel: String): Int {
    val primitive = root["schemaVersion"] as? JsonPrimitive
    val version = primitive?.takeIf { it.isString.not() }?.content?.toIntOrNull()
    return version
        ?: throw ScriptDataException("${dataLabel}数据缺少或包含非法的 schemaVersion 字段，无法识别版本")
}
