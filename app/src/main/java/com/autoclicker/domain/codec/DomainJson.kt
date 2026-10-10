package com.autoclicker.domain.codec

import kotlinx.serialization.json.Json

/**
 * 领域层统一使用的 JSON 配置（脚本与函数包共用，保证导入导出格式一致）：
 *
 * - ignoreUnknownKeys = false：严格模式，出现未知字段直接判为损坏数据，
 *   避免静默吞掉拼错/多写的字段；
 * - encodeDefaults = true：默认值也写入 JSON，导出文件字段完整、可读、跨实现稳定；
 * - 其余保持默认（如显式输出 null、多态 discriminator 为 "type"）。
 */
internal val domainJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

/** 当前数据格式版本（V2 重构后的脚本格式，对应 FR-6） */
internal const val SCHEMA_VERSION: Int = 2
