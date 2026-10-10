package com.autoclicker.core.engine

import com.autoclicker.domain.model.FunctionPackage

/**
 * 函数包解析器：引擎执行 PackageCall 时按 ID 实时获取函数包最新内容。
 *
 * 真实实现由数据层提供（读取函数包仓库）；单测用假实现。
 */
fun interface PackageResolver {
    /** 返回函数包；不存在时返回 null（引擎据此报中文错误） */
    suspend fun resolve(packageId: String): FunctionPackage?
}
