package com.autoclicker.domain.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 统一的 JSON 编解码配置：宽容未知字段、保留默认值、多态判别名固定为 kind。 */
object DomainJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        prettyPrint = true
        classDiscriminator = "kind"
    }
}

/**
 * 历史数据迁移：早期「点击图片 / 点击颜色」用 randomOffset（像素随机半径）表达偏移，
 * 现已统一为 offset（固定平移 + 随机抖动）。这里把旧字段折叠进 offset.randomRadius，
 * 避免老脚本已配置的随机偏移在升级后静默丢失。
 */
fun migrateLegacyOffset(json: String): String {
    val root = runCatching { DomainJson.instance.parseToJsonElement(json) }.getOrNull() ?: return json
    return DomainJson.instance.encodeToString(JsonElement.serializer(), rewriteLegacyOffset(root))
}

private fun rewriteLegacyOffset(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> {
        val fields = LinkedHashMap<String, JsonElement>()
        element.forEach { (key, value) -> fields[key] = rewriteLegacyOffset(value) }
        val legacy = (fields["randomOffset"] as? JsonPrimitive)?.intOrNull
        if (legacy != null && legacy > 0) {
            val existing = fields["offset"] as? JsonObject
            val currentRadius = (existing?.get("randomRadius") as? JsonPrimitive)?.intOrNull ?: 0
            if (currentRadius == 0) {
                val merged = (existing ?: JsonObject(emptyMap())).toMutableMap()
                    .apply { put("randomRadius", JsonPrimitive(legacy)) }
                fields["offset"] = JsonObject(merged)
            }
        }
        JsonObject(fields)
    }

    is JsonArray -> JsonArray(element.map { rewriteLegacyOffset(it) })
    else -> element
}

/** 脚本/函数包数据读写异常。 */
class ScriptDataException(message: String, cause: Throwable? = null) : Exception(message, cause)