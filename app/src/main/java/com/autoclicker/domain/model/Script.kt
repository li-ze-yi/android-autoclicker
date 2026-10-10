package com.autoclicker.domain.model

import kotlinx.serialization.Serializable

/** 启动类型：运行前是否需要先执行启动准备动作。 */
enum class LaunchType {
    /** 自行选择：直接运行。 */
    MANUAL,

    /** 从 Home 启动：先回桌面再运行。 */
    FROM_HOME,

    /** 从指定应用启动：先打开 launchPackage 再运行。 */
    FROM_APP,
}

/**
 * 任务（脚本）。动作以节点树存储，坐标为百分比。
 */
@Serializable
data class Script(
    val id: String,
    val name: String,
    val schemaVersion: Int = CURRENT_SCHEMA,
    val versionName: String = "1.0",
    /** 录制时的推荐分辨率。 */
    val recommended: ScreenProfile? = null,
    val nodes: List<ScriptNode> = emptyList(),
    val launchType: LaunchType = LaunchType.MANUAL,
    val launchPackage: String? = null,
    /** 关联的其它任务/函数包 id。 */
    val linkedPackageIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 动作数量（步骤总数，含组内）。 */
    val actionCount: Int get() = nodes.flattenSteps().size

    companion object {
        const val CURRENT_SCHEMA = 1
    }
}