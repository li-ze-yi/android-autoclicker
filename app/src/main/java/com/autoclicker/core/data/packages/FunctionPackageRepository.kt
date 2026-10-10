package com.autoclicker.core.data.packages

import android.content.Context
import android.util.Log
import com.autoclicker.domain.codec.FunctionPackageCodec
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.validate.StructureValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 全局函数包仓库（FR-6B / Task 12A）。
 *
 * 存储位置：应用内部存储 filesDir/function_packages/<id>.json，
 * 读写一律走 [FunctionPackageCodec]（带 schemaVersion 的信封格式），不手写 JSON。
 * 全部文件操作均在 [Dispatchers.IO] 执行。
 *
 * @param context 任意 Context，构造时统一取 applicationContext，避免 Activity 泄漏
 */
class FunctionPackageRepository(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 函数包存放目录（懒加载并按需创建） */
    private val packageDir: File by lazy {
        File(appContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /**
     * 列出全部函数包，按名称升序排序。
     *
     * 文件损坏 / 解析失败时跳过该文件（仅记录警告日志），
     * 不影响其他函数包，保证列表不崩溃（TR-12A.3）。
     */
    suspend fun list(): List<FunctionPackage> = withContext(Dispatchers.IO) {
        val files = packageDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching { FunctionPackageCodec.decode(file.readText(Charsets.UTF_8)) }
                .onFailure { e ->
                    Log.w(TAG, "函数包文件损坏，已跳过：${file.name}（${e.message}")
                }
                .getOrNull()
        }.sortedBy { it.name }
    }

    /**
     * 按 ID 获取函数包。
     * @return 函数包；文件不存在或内容损坏时返回 null（损坏会记录警告日志）
     */
    suspend fun get(id: String): FunctionPackage? = withContext(Dispatchers.IO) {
        val file = fileOf(id)
        if (!file.isFile) return@withContext null
        runCatching { FunctionPackageCodec.decode(file.readText(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "函数包读取失败：$id（${it.message}") }
            .getOrNull()
    }

    /**
     * 按 ID 批量获取函数包。
     * @return id -> 函数包；缺失 / 损坏的 ID 不会出现在结果中，输入重复 ID 自动去重
     */
    suspend fun getMany(ids: Collection<String>): Map<String, FunctionPackage> = withContext(Dispatchers.IO) {
        ids.distinct().mapNotNull { id ->
            val file = fileOf(id)
            if (!file.isFile) return@mapNotNull null
            val pkg = runCatching { FunctionPackageCodec.decode(file.readText(Charsets.UTF_8)) }
                .onFailure { Log.w(TAG, "函数包读取失败：$id（${it.message}") }
                .getOrNull()
            pkg?.let { id to it }
        }.toMap()
    }

    /**
     * 新增或更新函数包（按 id 覆盖写）。
     *
     * 写入前先经 [StructureValidator.validateFunctionPackage] 校验基础不变量；
     * 校验失败抛 [IllegalArgumentException]，消息为拼接后的中文原因，UI 可直接展示。
     * 写入采用「临时文件 + 重命名」，尽量避免半写损坏。
     */
    suspend fun upsert(pkg: FunctionPackage): Unit = withContext(Dispatchers.IO) {
        when (val result = StructureValidator.validateFunctionPackage(pkg)) {
            is StructureValidator.Result.Valid -> Unit
            is StructureValidator.Result.Invalid ->
                throw IllegalArgumentException(
                    "函数包保存失败：\n" + result.reasons.joinToString(separator = "\n") { "· $it" }
                )
        }
        packageDir.mkdirs()
        val target = fileOf(pkg.id)
        val tmp = File(packageDir, "${pkg.id}.${UUID.randomUUID()}.tmp")
        try {
            tmp.writeText(FunctionPackageCodec.encode(pkg), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                // 个别 ROM 上 rename 可能失败，退化为直接覆盖写
                target.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            }
        } finally {
            tmp.delete()
        }
    }

    /** 删除指定函数包；文件不存在时视为已删除，不抛异常 */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        fileOf(id).delete()
        Unit
    }

    /** 判断指定 id 的函数包文件是否存在 */
    suspend fun exists(id: String): Boolean = withContext(Dispatchers.IO) {
        fileOf(id).isFile
    }

    /** 生成新函数包 ID："fp-" + UUID */
    fun newId(): String = "fp-${UUID.randomUUID()}"

    /** 取该 id 对应的 JSON 文件 */
    private fun fileOf(id: String): File = File(packageDir, "$id.json")

    private companion object {
        const val DIR_NAME = "function_packages"
        const val TAG = "FunctionPackageRepo"
    }
}

/**
 * 函数包引用检查器（删除保护，FR-6B / AC-16）。
 *
 * Task 12 接入约定：由脚本管理侧提供真实实现——扫描全部脚本的步骤树
 * （含循环段嵌套）中的 PackageCall.packageId，判断该函数包是否被任意脚本引用；
 * 后续还应扩展为能返回引用它的脚本名称列表，供删除确认弹窗展示引用来源。
 *
 * 注意：UI 侧在 [kotlinx.coroutines.Dispatchers.IO] 上调用本接口，
 * 真实实现若需要读脚本文件，可直接执行阻塞 IO，无需自行切线程。
 */
fun interface ReferenceChecker {
    /** 返回 true 表示该函数包正被至少一个脚本引用，默认应阻止删除 */
    fun isReferenced(packageId: String): Boolean
}

/**
 * 默认引用检查实现：脚本仓库尚未接入（Task 12），恒返回 false（未被引用）。
 * Task 12 接入真实实现后，本对象可移除或仅保留作占位。
 */
object AlwaysFalseReferenceChecker : ReferenceChecker {
    override fun isReferenced(packageId: String): Boolean = false
}
