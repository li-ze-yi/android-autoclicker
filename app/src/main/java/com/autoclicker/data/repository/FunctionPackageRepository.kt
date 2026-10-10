package com.autoclicker.data.repository

import com.autoclicker.domain.model.FunctionPackage

/** 函数包仓库。 */
interface FunctionPackageRepository {
    suspend fun list(): List<FunctionPackage>

    suspend fun get(id: String): FunctionPackage?

    suspend fun save(pkg: FunctionPackage)

    suspend fun delete(id: String)

    suspend fun exportJson(id: String): String?

    suspend fun importJson(json: String): FunctionPackage
}