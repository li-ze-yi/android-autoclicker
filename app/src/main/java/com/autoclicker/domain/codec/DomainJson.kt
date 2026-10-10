package com.autoclicker.domain.codec

import kotlinx.serialization.json.Json

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

/** 脚本/函数包数据读写异常。 */
class ScriptDataException(message: String, cause: Throwable? = null) : Exception(message, cause)