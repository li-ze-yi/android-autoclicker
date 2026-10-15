package com.autoclicker.data.repository

import com.autoclicker.domain.model.ImageTemplateGroup

/** 图像模板组仓库。 */
interface TemplateGroupRepository {
    suspend fun list(): List<ImageTemplateGroup>

    suspend fun get(id: String): ImageTemplateGroup?

    suspend fun save(group: ImageTemplateGroup)

    suspend fun delete(id: String)
}
