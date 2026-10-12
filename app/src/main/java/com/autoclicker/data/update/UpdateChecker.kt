package com.autoclicker.data.update

import com.autoclicker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** 云端最新 Release 信息。 */
data class LatestRelease(
    /** 形如 v1.0.78。 */
    val tagName: String,
    /** 从 tag 解析出的构建号；解析失败为 0。 */
    val buildNumber: Int,
    /** Release 页面地址，供浏览器打开下载。 */
    val pageUrl: String,
) {
    /** 云端构建号是否比本机安装的更新。 */
    val isNewerThanCurrent: Boolean get() = buildNumber > BuildConfig.BUILD_NUMBER
}

/**
 * 通过 GitHub Releases API 查询最新版本。
 *
 * 仓库固定为本项目地址；仅读取 tag_name / html_url，不需要 token。
 * 网络与 HTTP 异常直接抛出，由调用方决定提示文案。
 */
object UpdateChecker {

    private const val REPO = "li-ze-yi/android-autoclicker"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** 下载页：始终指向最新 Release，可作为检查失败时的兜底入口。 */
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /**
     * 最新版 APK 直链：GitHub 的 `releases/latest/download/<资源名>` 会重定向到最新 Release 的该文件，
     * 用浏览器打开即直接开始下载，无需先查 API。
     */
    const val LATEST_APK_URL = "https://github.com/$REPO/releases/latest/download/app-debug.apk"

    private const val TIMEOUT_MS = 8000

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )

    /** 查询最新 Release。 */
    suspend fun fetchLatest(): LatestRelease = withContext(Dispatchers.IO) {
        val body = httpGet(LATEST_API)
        val release = json.decodeFromString(GhRelease.serializer(), body)
        LatestRelease(
            tagName = release.tagName,
            buildNumber = parseBuildNumber(release.tagName),
            pageUrl = release.htmlUrl.ifBlank { RELEASES_PAGE },
        )
    }

    /** 从 tag（如 v1.0.78）取最后一段数字作为构建号。 */
    private fun parseBuildNumber(tag: String): Int =
        tag.substringAfterLast('.').filter { it.isDigit() }.toIntOrNull() ?: 0

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AutoClicker-Android")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
