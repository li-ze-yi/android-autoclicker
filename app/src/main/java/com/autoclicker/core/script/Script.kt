package com.autoclicker.core.script

import kotlinx.serialization.Serializable

@Serializable
data class Script(
    val id: String,
    val name: String,
    val steps: List<Step> = emptyList(),
    val stopOnError: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun create(name: String): Script = Script(id = newId(), name = name)
    }
}