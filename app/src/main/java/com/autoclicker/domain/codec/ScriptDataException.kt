package com.autoclicker.domain.codec

/**
 * 脚本 / 函数包数据异常。
 *
 * 在 JSON 损坏、字段缺失、schemaVersion 不支持等情况下抛出，
 * [message] 一律为面向用户的中文提示，UI 层可直接展示（对应 FR-6“解析失败有明确报错而非崩溃”）。
 */
class ScriptDataException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
