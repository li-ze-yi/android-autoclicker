package com.autoclicker.domain.model

import java.util.UUID

/** 统一 ID 生成。 */
object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}