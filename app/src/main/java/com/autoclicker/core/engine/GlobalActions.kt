package com.autoclicker.core.engine

/**
 * 系统全局按键执行器：Home / 返回。
 *
 * 引擎只依赖接口；真机实现经无障碍服务 performGlobalAction。
 */
interface GlobalActions {
    /** 执行 Home，成功返回 true（无障碍未连接等情况下 false） */
    suspend fun goHome(): Boolean

    /** 执行返回 */
    suspend fun goBack(): Boolean
}
