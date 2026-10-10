package com.autoclicker.data.repository

import android.graphics.Bitmap
import com.autoclicker.domain.model.ImageTemplate

/** 图像模板仓库：负责 PNG 素材与索引的读写。 */
interface TemplateRepository {
    suspend fun list(): List<ImageTemplate>

    suspend fun get(id: String): ImageTemplate?

    /** 加载模板位图。 */
    suspend fun loadBitmap(id: String): Bitmap?

    /** 保存一张模板位图，返回模板元数据。 */
    suspend fun save(name: String, bitmap: Bitmap): ImageTemplate

    suspend fun rename(id: String, name: String)

    suspend fun delete(id: String)
}