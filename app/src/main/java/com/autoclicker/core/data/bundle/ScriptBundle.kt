package com.autoclicker.core.data.bundle

import android.content.Context
import android.graphics.BitmapFactory
import com.autoclicker.core.data.packages.FunctionPackageRepository
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.core.data.templates.ImageTemplateRepository
import com.autoclicker.domain.codec.FunctionPackageCodec
import com.autoclicker.domain.codec.ScriptCodec
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep
import com.autoclicker.domain.validate.StructureValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromString
import kotlinx.serialization.json.encodeToString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 脚本完整包（FR-6 / FR-6B / FR-7 导入导出，评审问题 I7 修复）。
 *
 * 一个自包含 zip：脚本本体 + 递归收集到的全部函数包 + 识图模板图片，
 * 并带清单 [BundleManifest]。纯脚本（无任何依赖）同样走本格式。
 *
 * zip 结构：
 * ```
 * manifest.json                 清单（格式版本、导出时间、脚本 ID、依赖 ID 列表、模板名称）
 * script.json                   脚本 JSON（ScriptCodec.encode 文本）
 * packages/<packageId>.json     函数包（含依赖包的依赖，递归收集）
 * templates/<templateId>.png    模板图片原始字节
 * ```
 *
 * 全部阻塞 IO，调用方需在 [kotlinx.coroutines.Dispatchers.IO] 上调用。
 *
 * @param context 任意 Context，构造时统一取 applicationContext，避免 Activity 泄漏
 */
class ScriptBundle(context: Context) {

    private val appContext: Context = context.applicationContext

    private val scriptRepo = ScriptFileRepository(appContext)
    private val packageRepo = FunctionPackageRepository(appContext)
    private val templateRepo = ImageTemplateRepository(appContext)

    // =================================================================================
    // 导出
    // =================================================================================

    /**
     * 把 [script] 连同其全部依赖打包为 zip，写入 [output]（本方法会关闭 [output]）。
     *
     * @throws ScriptBundleException 依赖的函数包 / 模板图片在本地缺失等原因（消息为中文）
     */
    suspend fun exportBundle(script: Script, output: OutputStream) {
        // 1) 从脚本步骤树收集第一层依赖
        val deps = DependencySet()
        collectFromSteps(script.steps, deps)

        // 2) 递归收集函数包：包内步骤中的 PackageCall 继续下钻；环用已访问集合防护
        val packages = LinkedHashMap<String, FunctionPackage>()
        val visitedPackages = HashSet<String>()
        val packageQueue = ArrayDeque(deps.packageIds)
        while (packageQueue.isNotEmpty()) {
            val packageId = packageQueue.removeFirst()
            if (!visitedPackages.add(packageId)) continue
            val pkg = packageRepo.get(packageId)
                ?: fail("导出失败：脚本引用的函数包缺失（$packageId），请先补全函数包或解除引用")
            packages[packageId] = pkg

            // 包内步骤同样可能引用模板与其他函数包
            val childDeps = DependencySet()
            collectFromSteps(pkg.steps, childDeps)
            childDeps.templateIds.forEach { deps.templateIds.add(it) }
            childDeps.packageIds.forEach { callee ->
                if (callee !in visitedPackages) packageQueue.add(callee)
            }
        }

        // 3) 读取模板图片原始字节（同时记录模板名称，供导入侧恢复显示名）
        val templateBytes = LinkedHashMap<String, ByteArray>()
        val templateNames = LinkedHashMap<String, String>()
        deps.templateIds.forEach { templateId ->
            val png = templatePngFile(templateId)
            if (!png.isFile) fail("导出失败：脚本引用的识图模板图片缺失（$templateId）")
            templateBytes[templateId] = png.readBytes()
            templateRepo.get(templateId)?.name?.let { templateNames[templateId] = it }
        }

        val manifest = BundleManifest(
            exportedAt = Instant.now().toString(),
            scriptId = script.id,
            packageIds = packages.keys.toList(),
            templateIds = templateBytes.keys.toList(),
            templateNames = templateNames,
        )

        // 4) 写 zip
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            writeEntry(zip, "manifest.json", manifestJson.encodeToString(manifest).toByteArray(UTF_8))
            writeEntry(zip, "script.json", ScriptCodec.encode(script).toByteArray(UTF_8))
            packages.forEach { (id, pkg) ->
                writeEntry(zip, "packages/$id.json", FunctionPackageCodec.encode(pkg).toByteArray(UTF_8))
            }
            templateBytes.forEach { (id, bytes) ->
                writeEntry(zip, "templates/$id.png", bytes)
            }
        }
    }

    // =================================================================================
    // 导入
    // =================================================================================

    /**
     * 从 zip 字节流 [input] 解析并落库（本方法会关闭 [input]）。
     *
     * 处理顺序：先读全部条目并解析、做冲突决策、构建最终写入内容并预校验，
     * 全部通过后再统一落库（脚本最后写入）；任一环节失败都不产生半截数据
     * （已新建的依赖会尽力回滚删除）。
     *
     * ID 冲突规则：
     * - 函数包：同 id 且内容一致 → 跳过复用；不一致 → 新 id 导入，脚本及包内
     *   对应 PackageCall 重映射；
     * - 模板：同 id 且图片字节一致 → 跳过复用；不一致 → 新 id 导入，
     *   WaitImage.templateId 重映射；
     * - 脚本本身：一律分配新 script id，不覆盖现有脚本；
     * - 脚本步骤 id 全部重新生成，避免跨脚本重复。
     *
     * @throws ScriptBundleException 坏 zip / 缺文件 / 解析失败 / 结构不合法（消息为中文原因）
     */
    suspend fun importBundle(input: InputStream): BundleImportResult {
        // ---------- 阶段 A：读取并解析全部内容（不写库） ----------

        val entries = try {
            readAllEntries(input)
        } catch (e: ZipException) {
            fail("压缩包已损坏或不是有效的 zip 文件：${e.message ?: "未知错误"}")
        } catch (e: IOException) {
            fail("读取压缩包失败：${e.message ?: "未知错误"}")
        }

        // 清单
        val manifestBytes = entries["manifest.json"] ?: fail("缺少清单文件 manifest.json")
        val manifest = try {
            manifestJson.decodeFromString<BundleManifest>(manifestBytes.toString(UTF_8))
        } catch (e: Exception) {
            fail("清单文件解析失败：${e.message ?: "未知错误"}")
        }
        if (manifest.formatVersion != BUNDLE_FORMAT_VERSION) {
            fail("不支持的脚本包格式版本：${manifest.formatVersion}（仅支持版本 $BUNDLE_FORMAT_VERSION）")
        }

        // 脚本本体（ScriptCodec.decode 失败会抛中文异常，直接透传）
        val scriptBytes = entries["script.json"] ?: fail("缺少脚本文件 script.json")
        val script = ScriptCodec.decode(scriptBytes.toString(UTF_8))
        if (manifest.scriptId != script.id) {
            fail("清单中的脚本 ID（${manifest.scriptId}）与脚本内容（${script.id}）不一致")
        }

        // 函数包
        val incomingPackages = LinkedHashMap<String, FunctionPackage>()
        manifest.packageIds.forEach { packageId ->
            val bytes = entries["packages/$packageId.json"]
                ?: fail("缺少函数包文件：packages/$packageId.json")
            incomingPackages[packageId] = decodePackage(bytes, packageId)
        }

        // 模板图片（提前验证可解码，避免写到一半才发现坏图）
        val incomingTemplateBytes = LinkedHashMap<String, ByteArray>()
        manifest.templateIds.forEach { templateId ->
            val bytes = entries["templates/$templateId.png"]
                ?: fail("缺少模板图片：templates/$templateId.png")
            if (!isDecodableImage(bytes)) fail("模板图片无法识别为有效图片：$templateId")
            incomingTemplateBytes[templateId] = bytes
        }

        // ---------- 阶段 B：冲突决策（仅内存） ----------

        val packagePlans = mutableListOf<PackagePlan>()
        val packageIdRemap = HashMap<String, String>()
        incomingPackages.forEach { (oldId, incoming) ->
            val existing = packageRepo.get(oldId)
            when {
                // 目标仓库无此 id：以原 id 导入
                existing == null ->
                    packagePlans += PackagePlan(oldId, finalId = oldId, save = true, conflict = false)

                // 同 id 且内容一致：跳过，直接复用现有包
                existing == incoming ->
                    packagePlans += PackagePlan(oldId, finalId = oldId, save = false, conflict = false)

                // 同 id 但内容不一致：新 id 导入并记录重映射
                else -> {
                    val newId = packageRepo.newId()
                    packageIdRemap[oldId] = newId
                    packagePlans += PackagePlan(oldId, finalId = newId, save = true, conflict = true)
                }
            }
        }

        val templatePlans = mutableListOf<TemplatePlan>()
        val templateIdRemap = HashMap<String, String>()
        incomingTemplateBytes.forEach { (oldId, incomingBytes) ->
            val exists = templateRepo.exists(oldId)
            val sameBytes = exists && templatePngFile(oldId).isFile &&
                templatePngFile(oldId).readBytes().contentEquals(incomingBytes)
            val fallbackName = manifest.templateNames[oldId] ?: "导入模板"
            when {
                !exists ->
                    templatePlans += TemplatePlan(
                        oldId, finalId = oldId, save = true, conflict = false, name = fallbackName,
                    )

                // 同 id 且图片字节一致：跳过复用
                sameBytes ->
                    templatePlans += TemplatePlan(
                        oldId, finalId = oldId, save = false, conflict = false, name = "",
                    )

                // 同 id 但图片不一致：新 id 导入并记录重映射
                else -> {
                    val newId = templateRepo.newId()
                    templateIdRemap[oldId] = newId
                    templatePlans += TemplatePlan(
                        oldId, finalId = newId, save = true, conflict = true, name = fallbackName,
                    )
                }
            }
        }

        // ---------- 阶段 C：构建最终内容（重映射 + 新 id）并预校验 ----------

        // 待写入函数包：替换自身 id，并重映射其步骤树中的 PackageCall / WaitImage
        val finalPackages: Map<String, FunctionPackage> =
            packagePlans.filter { it.save }.associate { plan ->
                val incoming = incomingPackages.getValue(plan.oldId)
                val finalPkg = incoming.copy(
                    id = plan.finalId,
                    steps = remapSteps(incoming.steps, packageIdRemap, templateIdRemap),
                )
                plan.finalId to finalPkg
            }

        // 脚本：一律新 script id，步骤 id 全部重新生成，同时做依赖重映射
        val finalScript = script.copy(
            id = scriptRepo.newId(),
            steps = rebuildScriptSteps(script.steps, packageIdRemap, templateIdRemap),
        )

        // 落库前预校验：任一不合法则整体放弃
        validateScriptOrFail(finalScript)
        finalPackages.values.forEach { validatePackageOrFail(it) }

        // ---------- 阶段 D：统一落库（脚本最后写），失败尽力回滚新建实体 ----------

        val createdPackageIds = mutableListOf<String>()
        val createdTemplateIds = mutableListOf<String>()
        try {
            finalPackages.values.forEach { pkg ->
                packageRepo.upsert(pkg)
                createdPackageIds += pkg.id
            }
            templatePlans.filter { it.save }.forEach { plan ->
                val bytes = incomingTemplateBytes.getValue(plan.oldId)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: fail("模板图片无法解码：${plan.oldId}")
                try {
                    templateRepo.save(plan.finalId, plan.name, bitmap)
                    createdTemplateIds += plan.finalId
                } finally {
                    bitmap.recycle()
                }
            }
            scriptRepo.upsert(finalScript)
        } catch (e: Exception) {
            // 回滚本次导入新建的模板与函数包（复用的既有实体绝不动）
            createdTemplateIds.reversed().forEach { id -> runCatching { templateRepo.delete(id) } }
            createdPackageIds.reversed().forEach { id -> runCatching { packageRepo.delete(id) } }
            throw e
        }

        // ---------- 汇总 ----------

        val packagesAdded = packagePlans.count { it.save }
        val packagesReused = packagePlans.count { !it.save }
        val templatesAdded = templatePlans.count { it.save }
        val templatesReused = templatePlans.count { !it.save }
        val packageConflicts = packagePlans.count { it.conflict }
        val templateConflicts = templatePlans.count { it.conflict }

        return BundleImportResult(
            scriptId = finalScript.id,
            scriptName = finalScript.name,
            packagesAdded = packagesAdded,
            packagesReused = packagesReused,
            templatesAdded = templatesAdded,
            templatesReused = templatesReused,
            packageConflicts = packageConflicts,
            templateConflicts = templateConflicts,
            summaryZh = buildSummaryZh(
                scriptName = finalScript.name,
                packagesAdded = packagesAdded,
                packagesReused = packagesReused,
                templatesAdded = templatesAdded,
                templatesReused = templatesReused,
                packageConflicts = packageConflicts,
                templateConflicts = templateConflicts,
            ),
        )
    }

    // =================================================================================    // 内部工具
    // =================================================================================

    /**
     * 递归遍历步骤树，收集 [ScriptStep.PackageCall.packageId] 与
     * [Action.WaitImage.templateId]（循环段递归下钻）。
     */
    private fun collectFromSteps(steps: List<ScriptStep>, deps: DependencySet) {
        steps.forEach { step ->
            when (step) {
                is ScriptStep.BasicStep -> {
                    val action = step.action
                    if (action is Action.WaitImage) deps.templateIds.add(action.templateId)
                }

                is ScriptStep.LoopGroup ->
                    collectFromSteps(step.steps, deps)

                is ScriptStep.PackageCall ->
                    deps.packageIds.add(step.packageId)
            }
        }
    }

    /** 解析函数包 JSON，并校验内容 ID 与文件名一致 */
    private fun decodePackage(bytes: ByteArray, packageId: String): FunctionPackage {
        val pkg = try {
            FunctionPackageCodec.decode(bytes.toString(UTF_8))
        } catch (e: Exception) {
            fail("函数包内容解析失败（$packageId）：${e.message ?: "未知错误"}")
        }
        if (pkg.id != packageId) {
            fail("函数包内容 ID 与文件名不一致：文件名为 $packageId，内容为 ${pkg.id}")
        }
        return pkg
    }

    /**
     * 重映射步骤树中的依赖引用（不重新生成步骤 id，函数包内部步骤 id 保持不变）：
     * PackageCall.packageId 按 [packageIdRemap] 替换，
     * WaitImage.templateId 按 [templateIdRemap] 替换。
     */
    private fun remapSteps(
        steps: List<ScriptStep>,
        packageIdRemap: Map<String, String>,
        templateIdRemap: Map<String, String>,
    ): List<ScriptStep> = steps.map { step ->
        when (step) {
            is ScriptStep.BasicStep ->
                step.copy(action = remapAction(step.action, templateIdRemap))

            is ScriptStep.PackageCall ->
                step.copy(packageId = packageIdRemap[step.packageId] ?: step.packageId)

            is ScriptStep.LoopGroup ->
                step.copy(steps = remapSteps(step.steps, packageIdRemap, templateIdRemap))
        }
    }

    /**
     * 为导入脚本重建步骤树：每个节点重新生成步骤 id（避免跨脚本重复），
     * 同时重映射 PackageCall.packageId 与 WaitImage.templateId。
     */
    private fun rebuildScriptSteps(
        steps: List<ScriptStep>,
        packageIdRemap: Map<String, String>,
        templateIdRemap: Map<String, String>,
    ): List<ScriptStep> = steps.map { step ->
        when (step) {
            is ScriptStep.BasicStep ->
                step.copy(
                    id = newStepId(),
                    action = remapAction(step.action, templateIdRemap),
                )

            is ScriptStep.PackageCall ->
                step.copy(
                    id = newStepId(),
                    packageId = packageIdRemap[step.packageId] ?: step.packageId,
                )

            is ScriptStep.LoopGroup ->
                step.copy(
                    id = newStepId(),
                    steps = rebuildScriptSteps(step.steps, packageIdRemap, templateIdRemap),
                )
        }
    }

    /** 重映射动作中的模板 id（仅 WaitImage 需要） */
    private fun remapAction(action: Action, templateIdRemap: Map<String, String>): Action =
        if (action is Action.WaitImage) {
            action.copy(templateId = templateIdRemap[action.templateId] ?: action.templateId)
        } else {
            action
        }

    /** 读 zip 全部条目到内存（不解压到文件，天然规避 zip-slip）；目录条目忽略，重复条目报错 */
    private fun readAllEntries(input: InputStream): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                try {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        if (entries.put(name, readCurrentEntry(zis)) != null) {
                            fail("压缩包包含重复条目：$name")
                        }
                    }
                } finally {
                    zis.closeEntry()
                }
                entry = zis.nextEntry
            }
        }
        return entries
    }

    /** 读取当前 zip 条目的全部字节（读到本条目录结束为止） */
    private fun readCurrentEntry(zis: ZipInputStream): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val read = zis.read(buffer)
            if (read == -1) break
            if (read > 0) out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** 写单个 zip 条目 */
    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** 判断字节数组能否被解码为图片 */
    private fun isDecodableImage(bytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    /** 落库前校验脚本，不合法则抛中文原因 */
    private fun validateScriptOrFail(script: Script) {
        when (val result = StructureValidator.validateScript(script)) {
            is StructureValidator.Result.Valid -> Unit
            is StructureValidator.Result.Invalid ->
                fail("脚本结构不合法，已取消导入：${result.reasons.joinToString("；")}")
        }
    }

    /** 落库前校验函数包自身基础不变量，不合法则抛中文原因 */
    private fun validatePackageOrFail(pkg: FunctionPackage) {
        when (val result = StructureValidator.validateFunctionPackage(pkg)) {
            is StructureValidator.Result.Valid -> Unit
            is StructureValidator.Result.Invalid ->
                fail("函数包「${pkg.name}」结构不合法，已取消导入：${result.reasons.joinToString("；")}")
        }
    }

    /**
     * 取模板 PNG 文件。
     * 注意：路径常量需与 ImageTemplateRepository 的存储约定（filesDir/templates/<id>.png）保持一致。
     */
    private fun templatePngFile(templateId: String): File =
        File(appContext.filesDir, TEMPLATE_DIR_NAME, "$templateId.png")

    /** 导入结果的中文汇总文案 */
    private fun buildSummaryZh(
        scriptName: String,
        packagesAdded: Int,
        packagesReused: Int,
        templatesAdded: Int,
        templatesReused: Int,
        packageConflicts: Int,
        templateConflicts: Int,
    ): String = buildString {
        append("已导入脚本「").append(scriptName).append("」")

        val clauses = mutableListOf<String>()
        if (packagesAdded > 0) clauses += "新增函数包 $packagesAdded 个"
        if (packagesReused > 0) clauses += "复用函数包 $packagesReused 个"
        if (templatesAdded > 0) clauses += "新增模板 $templatesAdded 个"
        if (templatesReused > 0) clauses += "复用模板 $templatesReused 个"
        if (clauses.isNotEmpty()) append("，").append(clauses.joinToString("，"))

        if (packageConflicts > 0 || templateConflicts > 0) {
            val conflictParts = buildList {
                if (packageConflicts > 0) add("$packageConflicts 个函数包")
                if (templateConflicts > 0) add("$templateConflicts 个模板")
            }
            append("；").append(conflictParts.joinToString("、"))
                .append("因内容冲突已以新 ID 导入")
        }
    }

    /** 函数包导入计划：原 id → 最终 id，是否需要写库，是否为内容冲突 */
    private class PackagePlan(
        val oldId: String,
        val finalId: String,
        val save: Boolean,
        val conflict: Boolean,
    )

    /** 模板导入计划：原 id → 最终 id，是否需要写库，是否为内容冲突，导入后显示名称 */
    private class TemplatePlan(
        val oldId: String,
        val finalId: String,
        val save: Boolean,
        val conflict: Boolean,
        val name: String,
    )

    /** 一次依赖收集的结果（有序去重） */
    private class DependencySet {
        val packageIds = LinkedHashSet<String>()
        val templateIds = LinkedHashSet<String>()
    }

    private companion object {
        /** 脚本包格式版本（manifest.formatVersion） */
        const val BUNDLE_FORMAT_VERSION: Int = 1

        /** 模板目录名，需与 ImageTemplateRepository 的 DIR_NAME 保持一致 */
        const val TEMPLATE_DIR_NAME: String = "templates"

        /** 清单 JSON 解析器：容忍未知字段以便向前兼容 */
        val manifestJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * 脚本包清单（manifest.json）。
 *
 * @property formatVersion 包格式版本（当前为 1）
 * @property exportedAt 导出时间（ISO-8601，UTC）
 * @property scriptId 被导出脚本的 ID
 * @property packageIds 递归收集到的函数包 ID（有序去重）
 * @property templateIds 收集到的识图模板 ID（有序去重）
 * @property templateNames 模板 ID → 原始名称（附加信息，缺失时导入侧用「导入模板」兜底）
 */
@Serializable
internal data class BundleManifest(
    val formatVersion: Int = 1,
    val exportedAt: String = "",
    val scriptId: String = "",
    val packageIds: List<String> = emptyList(),
    val templateIds: List<String> = emptyList(),
    val templateNames: Map<String, String> = emptyMap(),
)

/**
 * 脚本包导入结果。
 *
 * @property scriptId 导入后分配的新脚本 ID
 * @property packagesAdded 实际写入仓库的函数包数量（原 id 或新 id 导入均计入）
 * @property packagesReused 因同 id 且内容一致而跳过复用的函数包数量
 * @property templatesAdded 实际写入仓库的模板数量
 * @property templatesReused 因字节一致而跳过复用的模板数量
 * @property packageConflicts 因内容冲突以新 ID 导入的函数包数量
 * @property templateConflicts 因内容冲突以新 ID 导入的模板数量
 * @property summaryZh 面向用户的中文汇总（Snackbar 直接展示）
 */
data class BundleImportResult(
    val scriptId: String,
    val scriptName: String,
    val packagesAdded: Int,
    val packagesReused: Int,
    val templatesAdded: Int,
    val templatesReused: Int,
    val packageConflicts: Int,
    val templateConflicts: Int,
    val summaryZh: String,
)

/** 脚本包异常：消息一律为面向用户的中文原因 */
internal class ScriptBundleException(message: String) : Exception(message)

/** 抛出中文原因的脚本包异常 */
private fun fail(message: String): Nothing = throw ScriptBundleException(message)

/** 生成新步骤节点 ID（与 ui.scripts 包中的规则保持一致） */
private fun newStepId(): String = "step-${UUID.randomUUID()}"
