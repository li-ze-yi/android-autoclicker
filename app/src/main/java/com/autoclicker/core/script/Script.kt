package com.autoclicker.core.script

import kotlinx.serialization.Serializable

@Serializable
data class Script(
    val id: String,
    val name: String,
    val steps: List<Step> = emptyList(),
    val stopOnError: Boolean = true,
    val loopCount: Int = 1,
    val loopInfinite: Boolean = false,
    val loopIntervalMs: Long = 0L,
    val jitterRadiusPx: Int = 0,
    val jitterDelayPercent: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun create(name: String): Script = Script(id = newId(), name = name)
    }
}