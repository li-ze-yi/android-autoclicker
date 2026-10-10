package com.autoclicker.domain.codec

import com.autoclicker.domain.model.FunctionPackage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** FunctionPackage <-> JSON 编解码。 */
object FunctionPackageCodec {
    fun encode(pkg: FunctionPackage): String =
        DomainJson.instance.encodeToString(pkg)

    fun decode(json: String): FunctionPackage = try {
        DomainJson.instance.decodeFromString(FunctionPackage.serializer(), json)
    } catch (e: Exception) {
        throw ScriptDataException("函数包解析失败：${e.message}", e)
    }
}