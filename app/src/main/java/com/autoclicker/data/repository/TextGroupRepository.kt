package com.autoclicker.data.repository

import com.autoclicker.domain.model.TextGroup

/** 文本组仓库。 */
interface TextGroupRepository {
    suspend fun list(): List<TextGroup>

    suspend fun get(id: String): TextGroup?

    suspend fun save(group: TextGroup)

    suspend fun delete(id: String)
}