package com.rokidlab.phone.model

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.R
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class BrewArtifact(
    val target: String,
    val url: String,
    val sha256: String?,
    val sizeBytes: Long?,
    val packageName: String?,
    val versionCode: Long?,
)

data class BrewScreenshot(
    val assetName: String?,
    val url: String?,
)

data class BrewListing(
    val about: String?,
    val descriptionMarkdown: String?,
)

data class BrewRelease(
    val version: String?,
    val date: String?,
    val sourceReleaseUrl: String?,
    val notes: String?,
    val changes: List<String>,
)

data class BrewSelfUpdateState(
    val currentVersion: String = "",
    val currentVersionCode: Long = 0L,
    val latestVersion: String = "",
    val latestVersionCode: Long? = null,
    val apkUrl: String = "",
    val releaseUrl: String = "",
    val notes: String = "",
    val changes: List<String> = emptyList(),
    val available: Boolean = false,
    val downloading: Boolean = false,
    val downloadPercent: Int = 0,
)

/**
 * RokidLab 自身更新信息
 */
data class BrewSelfUpdate(
    val version: String,
    val versionCode: Long,
    val apkUrl: String,
    val releaseUrl: String,
    val notes: String,
    val changes: List<String>,
)

data class BrewApp(
    val id: String,
    val name: String,
    val category: String,
    val type: String,
    val version: String,
    val summary: String,
    val description: String,
    val author: String,
    val sourceUrl: String?,
    val iconAsset: String?,
    val iconUrl: String?,
    val screenshotAssets: List<String>,
    val screenshotUrls: List<String>,
    val featured: Boolean,
    val featuredRank: Int?,
    val publishedAt: String?,
    val newUntil: String?,
    val isNew: Boolean,
    val phoneRequired: Boolean,
    val artifacts: List<BrewArtifact>,
    val listing: BrewListing?,
    val releases: List<BrewRelease>,
) {
    val screenshotAsset: String?
        get() = screenshotAssets.firstOrNull()
    val screenshotUrl: String?
        get() = screenshotUrls.firstOrNull()
    val screenshotCount: Int
        get() = maxOf(screenshotAssets.size, screenshotUrls.size)

    fun artifactFor(target: String): BrewArtifact? = artifacts.firstOrNull { it.target == target }
    fun hasTarget(target: String): Boolean = artifactFor(target) != null
    fun isPhoneSection(): Boolean = type == "combo" || type == "phone" || hasTarget("phone") || phoneRequired
    fun isFeatured(): Boolean = featured || featuredRank != null
    fun aboutText(): String = listing?.about?.takeIf { it.isNotBlank() }
        ?: listing?.descriptionMarkdown?.takeIf { it.isNotBlank() }
        ?: description
    fun screenshotAt(index: Int): BrewScreenshot = BrewScreenshot(
        assetName = screenshotAssets.getOrNull(index),
        url = screenshotUrls.getOrNull(index),
    )
}

data class BrewIndexRefresh(
    val apps: List<BrewApp>,
    val sourceUrl: String,
    val brewVersion: String?,
    val brewVersionCode: Long?,
    val brewApkUrl: String?,
    val brewReleaseUrl: String?,
    val brewNotes: String?,
    val brewChanges: List<String>,
)

private data class BrewIndexRaw(
    val json: JSONObject,
    val apps: List<BrewApp>,
    val brewVersion: String?,
    val brewVersionCode: Long?,
    val brewApkUrl: String?,
    val brewReleaseUrl: String?,
    val brewNotes: String?,
    val brewChanges: List<String>,
)

data class MirrorSource(
    val id: String,
    val name: String,
    val url: String,
    val descriptionRes: Int,
    val iconRes: Int? = null,
    val iconUrl: String? = null,
)

object BrewIndex {
    // RokidLab 自身更新固定使用 Gitee 地址
    const val SELF_UPDATE_URL = "https://gitee.com/dlover1314/RokidBrew-Registry/raw/main/dist/apps.v1.json"
    
    val MIRRORS = listOf(
        MirrorSource(
            id = "gitee",
            name = "Gitee",
            url = "https://gitee.com/dlover1314/RokidBrew-Registry/raw/main/dist/apps.v1.json",
            descriptionRes = R.string.mirror_gitee_desc,
            iconRes = R.drawable.ic_gitee_mark,
            iconUrl = "https://gitee.com/static/images/logo.svg",
        ),
        MirrorSource(
            id = "github",
            name = "GitHub",
            url = "https://raw.githubusercontent.com/Anezium/RokidBrew-Registry/main/dist/apps.v1.json",
            descriptionRes = R.string.mirror_github_desc,
            iconRes = R.drawable.ic_github_mark,
        ),
    )

    private const val MIRROR_PREF = "brew_mirror_index"
    private const val PREFS_NAME = "brew_prefs"

    @Volatile
    private var currentMirrorIndex = 0

    fun getCurrentMirror(): MirrorSource = MIRRORS[currentMirrorIndex]

    fun getMirrorIndex(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(MIRROR_PREF, 0)
    }

    fun setMirror(context: Context, index: Int) {
        if (index in MIRRORS.indices) {
            currentMirrorIndex = index
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(MIRROR_PREF, index).apply()
        }
    }

    fun initMirror(context: Context) {
        currentMirrorIndex = getMirrorIndex(context)
    }

    /**
     * 单独检查 RokidLab 自身更新，固定从 Gitee 获取
     * 不受用户镜像源选择的影响
     */
    suspend fun checkSelfUpdate(): BrewSelfUpdate? = withContext(Dispatchers.IO) {
        return@withContext try {
            val raw = fetch(SELF_UPDATE_URL)
            val parsed = parse(raw)
            BrewSelfUpdate(
                version = parsed.brewVersion.orEmpty(),
                versionCode = parsed.brewVersionCode ?: 0L,
                apkUrl = parsed.brewApkUrl.orEmpty(),
                releaseUrl = parsed.brewReleaseUrl.orEmpty(),
                notes = parsed.brewNotes.orEmpty(),
                changes = parsed.brewChanges ?: emptyList(),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun loadInitial(context: Context): List<BrewApp> {
        initMirror(context)
        val bundled = loadBundled(context)
        val cached = loadCached(context)
        return mergeBundledMedia(cached, bundled).ifEmpty { bundled }
    }

    fun loadBundled(context: Context): List<BrewApp> {
        val raw = context.assets.open("apps.json").bufferedReader().use { it.readText() }
        return parse(raw).apps
    }

    fun loadCached(context: Context): List<BrewApp> {
        val file = cacheFile(context)
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()).apps }.getOrDefault(emptyList())
    }

    suspend fun refresh(context: Context): BrewIndexRefresh = withContext(Dispatchers.IO) {
        val primaryUrl = MIRRORS[currentMirrorIndex].url
        val fallbackUrls = MIRRORS.filterIndexed { i, _ -> i != currentMirrorIndex }.map { it.url }
        val urls = listOf(primaryUrl) + fallbackUrls
        var lastError: Throwable? = null
        for (url in urls) {
            // 每个 URL 最多重试 3 次，间隔递增
            // 解决网络切换后 DNS 缓存未刷新等问题
            for (attempt in 0 until 3) {
                val result = runCatching {
                    val raw = fetch(url)
                    val parsed = parse(raw)
                    val apps = mergeBundledMedia(parsed.apps, loadBundled(context))
                    require(apps.isNotEmpty()) { "远程注册表为空" }
                    cacheFile(context).writeText(raw)
                    return@withContext BrewIndexRefresh(
                        apps = apps,
                        sourceUrl = url,
                        brewVersion = parsed.brewVersion,
                        brewVersionCode = parsed.brewVersionCode,
                        // App 自身更新固定使用 Gitee 地址
                        brewApkUrl = parsed.brewApkUrl?.replace("https://github.com", "https://gitee.com")
                            ?: parsed.brewApkUrl,
                        brewReleaseUrl = parsed.brewReleaseUrl?.replace("github.com", "gitee.com")
                            ?: parsed.brewReleaseUrl,
                        brewNotes = parsed.brewNotes,
                        brewChanges = parsed.brewChanges,
                    )
                }
                if (result.isSuccess) break
                lastError = result.exceptionOrNull()
                if (attempt < 2) delay((attempt + 1) * 1000L) // 1s, 2s
            }
            if (lastError == null) break // 当前 URL 重试成功，不再试后面的 URL
        }
        throw lastError ?: IllegalStateException("No available registry endpoint")
    }

    private fun cacheFile(context: Context): File {
        val suffix = Integer.toHexString(MIRRORS[currentMirrorIndex].url.hashCode())
        return File(context.filesDir, "apps.$suffix.json")
    }

    private fun fetch(url: String): String {
        return HttpClient.getString(
            url = url,
            headers = mapOf(
                "Accept" to "application/json",
                "Cache-Control" to "no-cache",
            ),
        )
    }

    private fun mergeBundledMedia(apps: List<BrewApp>, bundled: List<BrewApp>): List<BrewApp> {
        if (apps.isEmpty()) return emptyList()
        val bundledById = bundled.associateBy { it.id }
        return apps.map { app ->
            val bundledApp = bundledById[app.id] ?: return@map app
            app.copy(
                author = app.author.takeUnless { it == "Unknown" } ?: bundledApp.author,
                sourceUrl = app.sourceUrl ?: bundledApp.sourceUrl,
                iconAsset = app.iconAsset ?: bundledApp.iconAsset,
                iconUrl = app.iconUrl ?: bundledApp.iconUrl,
                screenshotAssets = app.screenshotAssets.ifEmpty { bundledApp.screenshotAssets },
                screenshotUrls = app.screenshotUrls.ifEmpty { bundledApp.screenshotUrls },
                listing = app.listing ?: bundledApp.listing,
                releases = app.releases.ifEmpty { bundledApp.releases },
            )
        }
    }

    private fun parse(raw: String): BrewIndexRaw {
        val root = JSONObject(raw)
        val appsArray = root.getJSONArray("apps")
        val apps = buildList {
            for (i in 0 until appsArray.length()) {
                val app = appsArray.getJSONObject(i)
                val artifactsJson = app.getJSONArray("artifacts")
                val artifacts = buildList {
                    for (j in 0 until artifactsJson.length()) {
                        val artifact = artifactsJson.getJSONObject(j)
                        add(
                            BrewArtifact(
                                target = artifact.getString("target"),
                                url = artifact.getString("url"),
                                sha256 = artifact.optString("sha256").takeIf { it.isNotBlank() },
                                sizeBytes = artifact.optLong("sizeBytes").takeIf { it > 0L },
                                packageName = artifact.optString("packageName").takeIf { it.isNotBlank() },
                                versionCode = artifact.optLong("versionCode").takeIf { it > 0L },
                            ),
                        )
                    }
                }
                val sourceUrl = app.optString("sourceUrl").takeIf { it.isNotBlank() } ?: artifacts.inferredSourceUrl()
                val publishedAt = app.optString("publishedAt").takeIf { it.isNotBlank() }
                val newUntil = app.optString("newUntil").takeIf { it.isNotBlank() }
                val listing = app.listing()
                add(
                    BrewApp(
                        id = app.getString("id"),
                        name = app.getString("name"),
                        category = app.getString("category"),
                        type = app.getString("type"),
                        version = app.getString("version"),
                        summary = app.getString("summary"),
                        description = app.optString("description", app.getString("summary")),
                        author = app.optString("author").takeIf { it.isNotBlank() } ?: sourceUrl.inferredAuthor(),
                        sourceUrl = sourceUrl,
                        iconAsset = app.optString("iconAsset").takeIf { it.isNotBlank() },
                        iconUrl = app.optString("iconUrl").takeIf { it.isNotBlank() },
                        screenshotAssets = app.screenshotAssets(),
                        screenshotUrls = app.screenshotUrls(),
                        featured = app.optBoolean("featured", false),
                        featuredRank = app.optionalInt("featuredRank"),
                        publishedAt = publishedAt,
                        newUntil = newUntil,
                        isNew = isNewApp(publishedAt, newUntil),
                        phoneRequired = app.optBoolean("phoneRequired", false),
                        artifacts = artifacts,
                        listing = listing,
                        releases = app.releases(),
                    ),
                )
            }
        }
        return BrewIndexRaw(
            json = root,
            apps = apps,
            brewVersion = root.optString("brewVersion").takeIf { it.isNotBlank() },
            brewVersionCode = root.optLong("brewVersionCode").takeIf { it > 0L },
            brewApkUrl = root.optString("brewApkUrl").takeIf { it.isNotBlank() },
            brewReleaseUrl = root.optString("brewReleaseUrl").takeIf { it.isNotBlank() },
            brewNotes = root.optString("brewNotes").takeIf { it.isNotBlank() },
            brewChanges = root.stringList("brewChanges"),
        )
    }

    private fun List<BrewArtifact>.inferredSourceUrl(): String? {
        val url = firstOrNull()?.url ?: return null
        val rawGithub = Regex("""https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)/[^/]+""").find(url)
        if (rawGithub != null) {
            val branch = rawGithub.groupValues[3].takeUnless { it.equals("HEAD", ignoreCase = true) } ?: "main"
            return "https://github.com/${rawGithub.groupValues[1]}/${rawGithub.groupValues[2]}/tree/$branch/${rawGithub.groupValues[4]}"
        }

        val github = Regex("""https://github\.com/([^/]+)/([^/]+)""").find(url) ?: return url
        return "https://github.com/${github.groupValues[1]}/${github.groupValues[2]}"
    }

    private fun String?.inferredAuthor(): String {
        if (this.isNullOrBlank()) return ""
        val owner = Regex("""https://github\.com/([^/]+)""").find(this)?.groupValues?.getOrNull(1)
        return owner ?: URL(this).host.removePrefix("www.")
    }

    private fun JSONObject.screenshotAssets(): List<String> {
        val assets = optJSONArray("screenshotAssets")
        if (assets != null) {
            return buildList {
                for (i in 0 until assets.length()) {
                    assets.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        return optString("screenshotAsset").takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
    }

    private fun JSONObject.screenshotUrls(): List<String> {
        val urls = optJSONArray("screenshotUrls") ?: return emptyList()
        return buildList {
            for (i in 0 until urls.length()) {
                urls.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.listing(): BrewListing? {
        val listing = optJSONObject("listing") ?: return null
        val about = listing.optString("about").takeIf { it.isNotBlank() }
        val descriptionMarkdown = listing.optString("descriptionMarkdown").takeIf { it.isNotBlank() }
        if (about == null && descriptionMarkdown == null) return null
        return BrewListing(
            about = about,
            descriptionMarkdown = descriptionMarkdown,
        )
    }

    private fun JSONObject.releases(): List<BrewRelease> {
        val releases = optJSONArray("releases") ?: return emptyList()
        return buildList {
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                add(
                    BrewRelease(
                        version = release.optString("version").takeIf { it.isNotBlank() },
                        date = release.optString("date").takeIf { it.isNotBlank() },
                        sourceReleaseUrl = release.optString("sourceReleaseUrl").takeIf { it.isNotBlank() },
                        notes = release.optString("notes").takeIf { it.isNotBlank() }
                            ?: release.optString("notesMarkdown").takeIf { it.isNotBlank() },
                        changes = release.stringList("changes"),
                    ),
                )
            }
        }
    }

    private fun JSONObject.stringList(name: String): List<String> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (i in 0 until values.length()) {
                values.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optionalInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return optInt(name).takeIf { it >= 0 }
    }

    private fun isNewApp(publishedAt: String?, newUntil: String?): Boolean {
        val now = System.currentTimeMillis()
        parseRegistryTime(newUntil)?.let { return now <= it }
        val published = parseRegistryTime(publishedAt) ?: return false
        val newWindowMillis = 2L * 24L * 60L * 60L * 1000L
        return now >= published && now - published <= newWindowMillis
    }

    private fun parseRegistryTime(value: String?): Long? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}

// ===== 引导步骤 =====
enum class GuideStep {
    SELECT_HOST_APP,
    SELECT_MIRROR_SOURCE,
    AUTHORIZE,
    READY
}

// ===== 前置条件状态 =====
data class PrerequisitesState(
    val hostApp: RokidHostApp? = null,
    val mirrorSourceSelected: Boolean = false,
    val authorized: Boolean = false,
    val rokidLinkInstalled: Boolean = false,
) {
    val currentGuideStep: GuideStep
        get() = when {
            hostApp == null -> GuideStep.SELECT_HOST_APP
            !mirrorSourceSelected -> GuideStep.SELECT_MIRROR_SOURCE
            !authorized -> GuideStep.AUTHORIZE
            else -> GuideStep.READY
        }
    
    val canInstallApps: Boolean
        get() = currentGuideStep == GuideStep.READY
}

// ===== 屏幕镜像状态 =====
data class ScreenMirrorState(
    val rokidLinkInstalled: Boolean? = null,
    val isInstallingRokidLink: Boolean = false,
    val rokidLinkRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val isStreaming: Boolean = false,
    val connectionStatus: String = "",
    val connectionFailed: Boolean = false,
    val ipAddress: String = "192.168.1.168",
)

// ===== 手机投屏状态 =====
data class PhoneMirrorState(
    val rokidLinkInstalled: Boolean? = null,
    val isInstallingRokidLink: Boolean = false,
    val rokidLinkRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val isStreaming: Boolean = false,
    val connectionStatus: String = "",
    val connectionFailed: Boolean = false,
    val ipAddress: String = "192.168.1.168",
    val port: String = "7654",
    val isMirroring: Boolean = false,
)

// ===== 文件管理状态 =====
data class FileManagerState(
    val rokidLinkInstalled: Boolean? = null,
    val isInstallingRokidLink: Boolean = false,
    val rokidLinkRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val connectionError: String? = null,
    val ipAddress: String = "192.168.1.168",
    val currentPath: String = "/",
)
