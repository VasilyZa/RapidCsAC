package com.xiaobai.rapidcsac.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.xiaobai.rapidcsac.BuildConfig
import com.xiaobai.rapidcsac.R
import com.xiaobai.rapidcsac.core.network.objects
import com.xiaobai.rapidcsac.platform.copyText
import com.xiaobai.rapidcsac.ui.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class OpenSourceLicense(val name: String, val text: String)

private data class OpenSourceLibrary(
    val id: String,
    val name: String,
    val version: String,
    val authors: String,
    val website: String,
    val licenses: List<String>,
)

private data class LicenseCatalog(
    val libraries: List<OpenSourceLibrary>,
    val licenses: Map<String, OpenSourceLicense>,
)

private suspend fun loadLicenses(context: Context): LicenseCatalog =
    withContext(Dispatchers.IO) {
        val document =
            context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use {
                JSONObject(it.readText())
            }
        val rawLicenses = document.getJSONObject("licenses")
        val licenses =
            rawLicenses.keys().asSequence().associateWith { key ->
                val license = rawLicenses.getJSONObject(key)
                val content = license.optString("content")
                OpenSourceLicense(
                    license.optString("name", key),
                    if (content.contains("<br", ignoreCase = true))
                        HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY)
                            .toString()
                            .trim()
                    else content.trim(),
                )
            }
        val libraries =
            document
                .objects("libraries")
                .map { library ->
                    val licenseIds = library.optJSONArray("licenses")
                    OpenSourceLibrary(
                        id = library.getString("uniqueId"),
                        name = library.getString("name"),
                        version = library.optString("artifactVersion"),
                        authors =
                            library
                                .objects("developers")
                                .map { it.optString("name") }
                                .filter { it.isNotBlank() }
                                .joinToString("、")
                                .ifEmpty {
                                    library
                                        .optJSONObject("organization")
                                        ?.optString("name")
                                        .orEmpty()
                                },
                        website = library.optString("website"),
                        licenses =
                            licenseIds
                                ?.let { values ->
                                    List(values.length()) { values.getString(it) }
                                }
                                .orEmpty(),
                    )
                }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        LicenseCatalog(libraries, licenses)
    }

@Composable
fun LicensesScreen(nav: Navigation) {
    val context = LocalContext.current
    val data = rememberRemote(context) { loadLicenses(context) }
    var query by rememberSaveable { mutableStateOf("") }
    val catalog = data.value
    val libraries =
        remember(catalog, query) {
            catalog?.libraries.orEmpty().filter { library ->
                listOf(library.name, library.id, library.licenses.joinToString(" ")).any {
                    it.contains(query.trim(), ignoreCase = true)
                }
            }
        }
    Page("开源许可", nav::back) { padding ->
        PageList(padding) {
            item {
                Card {
                    MenuRow("RapidCsAC", "AGPL-3.0-only", "file") { nav.open("licenses/project") }
                }
            }
            if (catalog == null || data.error.isNotEmpty()) item { RemoteStatus(data) }
            if (catalog != null) {
                item {
                    SectionLabel("第三方库 · ${catalog.libraries.size}")
                    RapidField(query, { query = it }, "搜索库或许可证")
                }
                items(libraries, key = { it.id }) { library ->
                    Card {
                        MenuRow(
                            library.name,
                            listOf(library.version, library.licenses.joinToString(" / "))
                                .filter { it.isNotEmpty() }
                                .joinToString(" · "),
                        ) {
                            nav.open("licenses/${Uri.encode(library.id)}")
                        }
                    }
                }
                if (libraries.isEmpty()) item { Text("没有匹配的库", Modifier.padding(12.dp)) }
            }
        }
    }
}

@Composable
fun LicenseDetailScreen(id: String, ui: UiController, nav: Navigation) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val data = rememberRemote(context) { loadLicenses(context) }
    val catalog = data.value
    val library =
        if (id == "project")
            OpenSourceLibrary(
                id = "com.xiaobai.rapidcsac",
                name = "RapidCsAC",
                version = BuildConfig.VERSION_NAME,
                authors = "RapidCsAC contributors",
                website = "https://github.com/VasilyZa/RapidCsAC",
                licenses = listOf("AGPL-3.0-only"),
            )
        else catalog?.libraries?.firstOrNull { it.id == id }
    Page("许可详情", nav::back) { padding ->
        PageList(padding) {
            item { RemoteStatus(data) }
            if (catalog != null && library != null) {
                item {
                    Card {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(library.name, fontSize = 20.sp)
                            Text(library.version, color = MiuixTheme.colorScheme.primary)
                            Text(library.id, fontSize = 13.sp)
                            if (library.authors.isNotEmpty())
                                Text(library.authors, fontSize = 14.sp)
                        }
                        if (library.website.toUri().scheme in setOf("https", "http"))
                            MenuRow("项目主页", icon = "discover") {
                                runCatching { uriHandler.openUri(library.website) }
                                    .onFailure { ui.inform("无法打开项目主页") }
                            }
                    }
                }
                library.licenses.forEach { licenseId ->
                    val license = catalog.licenses[licenseId]
                    item(key = "title-$licenseId") { SectionLabel(license?.name ?: licenseId) }
                    val text = license?.text.orEmpty()
                    if (text.isNotBlank()) {
                        item(key = "copy-$licenseId") {
                            Button({
                                copyText(context, text)
                                ui.inform("许可已复制")
                            }) {
                                Text("复制许可全文")
                            }
                        }
                        items(text.split(Regex("\\n\\s*\\n"))) { paragraph ->
                            SelectionContainer {
                                Text(paragraph, fontSize = 14.sp, lineHeight = 22.sp)
                            }
                        }
                    } else item { Text("未提供许可全文，请查看项目主页。") }
                }
            } else if (catalog != null) item { Text("找不到此库的许可信息。") }
        }
    }
}
