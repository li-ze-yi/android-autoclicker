package com.autoclicker.domain.codec

import com.autoclicker.domain.model.FunctionPackage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 函数包 JSON 编解码器（对应 FR-6B，独立于脚本存储）。
 *
 * 外层信封格式：
 * `{ "schemaVersion": 2, "functionPackage": { ... } }`
 *
 * 版本不匹配或 JSON 损坏时抛 [ScriptDataException]（中文消息）。
 */
object FunctionPackageCodec {

    /** 函数包外层信封（仅编解码内部使用） */
    @Serializable
    private data class Envelope(
        val schemaVersion: Int,
        val functionPackage: FunctionPackage,
    )

    /** 把 [functionPackage] 编码为带 schemaVersion 的 JSON 字符串 */
    fun encode(functionPackage: FunctionPackage): String = try {
        domainJson.encodeToString(Envelope(SCHEMA_VERSION, functionPackage))
    } catch (e: SerializationException) {
        throw ScriptDataException("函数包序列化失败：${e.message ?: "未知错误"}", e)
    }

    /**
     * 解析 JSON 字符串为 [FunctionPackage]。
     *
     * @throws ScriptDataException JSON 损坏、缺少/非法 schemaVersion、版本不支持或内容结构非法时
     */
    fun decode(text: String): FunctionPackage {
        val root = parseRootObject(text, "函数包")
        val version = readSchemaVersion(root, "函数包")
        if (version != SCHEMA_VERSION) {
            throw ScriptDataException(
                "函数包数据版本不受支持：当前版本号为 $version，本版本仅支持版本 $SCHEMA_VERSION"
            )
        }
        val envelope = try {
            domainJson.decodeFromJsonElement<Envelope>(root)
        } catch (e: SerializationException) {
            throw ScriptDataException("函数包数据内容已损坏或结构不正确，无法解析：${e.message ?: "未知错误"}", e)
        }
        return envelope.functionPackage
    }
}
