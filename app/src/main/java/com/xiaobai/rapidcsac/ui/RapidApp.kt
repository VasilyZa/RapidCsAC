package com.xiaobai.rapidcsac.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.xiaobai.rapidcsac.AppGraph
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.screens.*
import top.yukonga.miuix.kmp.basic.*

class Navigation(private val controller: NavHostController) {
    fun open(route: String) {
        controller.navigate(route) { launchSingleTop = true }
    }

    fun back() {
        controller.popBackStack()
    }

    fun home() {
        controller.popBackStack("home", false)
    }

    fun chat(key: String, name: String = "聊天", target: Long = 0) =
        open("chat/${Uri.encode(key)}?name=${Uri.encode(name)}&target=$target")

    fun detail(group: Boolean, id: Long) = open("detail/${if (group) "group" else "user"}/$id")

    fun records(kind: String, id: Long = 0, key: String = "", manage: Boolean = false) {
        // Record categories share a destination, so reuse only an entry with matching arguments.
        val samePage =
            controller.currentBackStackEntry?.arguments?.let { arguments ->
                arguments.getString("kind") == kind &&
                    arguments.getLong("id") == id &&
                    arguments.getString("key") == key &&
                    arguments.getBoolean("manage") == manage
            } ?: false
        controller.navigate("records/$kind?id=$id&key=${Uri.encode(key)}&manage=$manage") {
            launchSingleTop = samePage
        }
    }

    fun search(key: String = "") = open("search?key=${Uri.encode(key)}")

    fun image(url: String, tag: String = "") =
        open("image?url=${Uri.encode(url)}&tag=${Uri.encode(tag)}")

    fun qr(value: String = "") = open("qr?value=${Uri.encode(value)}")

    fun miniApp(id: String) = open("miniapp/${Uri.encode(id)}")

    fun link(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("rapidcsac", "csacflutterleon")) return false
        val parts = listOfNotNull(uri.host) + uri.pathSegments
        val id = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return false
        when (parts.firstOrNull()) {
            "group",
            "groupChat" -> detail(true, id)
            "user",
            "private",
            "privateChat" -> detail(false, id)
            else -> return false
        }
        return true
    }
}

@Composable
fun RapidApp(
    graph: AppGraph,
    unlocked: Boolean,
    unlock: () -> Unit,
    link: Intent?,
    consumeLink: () -> Unit,
) {
    val repo by graph.repository.collectAsStateWithLifecycle()
    val loading by graph.loading.collectAsStateWithLifecycle()
    val lock by graph.lockEnabled.collectAsStateWithLifecycle()
    key(repo?.account?.key) {
        val scope = rememberCoroutineScope()
        val ui = remember { UiController(scope) }
        RapidTheme(graph) {
            val view = LocalView.current
            val dark = LocalDarkTheme.current
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                if (lock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                snackbarHost = {
                    SnackbarHost(ui.snackbar, Modifier.navigationBarsPadding().imePadding())
                },
            ) {
                when {
                    loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("RapidCsAC")
                        }
                    repo == null -> AuthScreen(graph, ui)
                    lock && !unlocked ->
                        Box(
                            Modifier.fillMaxSize().safeDrawingPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                EmptyContent("RapidCsAC 已锁定", "使用设备凭据查看聊天", icon = "lock")
                                PrimaryButton("解锁", onClick = unlock)
                            }
                        }
                    else ->
                        key(repo!!.account.key) {
                            AccountNavigation(graph, repo!!, ui, link, consumeLink)
                        }
                }
                key(ui) { UiOverlays(ui) }
            }
        }
    }
}

@Composable
private fun AccountNavigation(
    graph: AppGraph,
    repo: ChatRepository,
    ui: UiController,
    link: Intent?,
    consumeLink: () -> Unit,
) {
    val needsEmail by repo.needsEmail.collectAsStateWithLifecycle()
    val controller = rememberNavController()
    val nav = remember(controller) { Navigation(controller) }
    val reduce = LocalReducedMotion.current
    LaunchedEffect(link, needsEmail) {
        if (link != null && !needsEmail) {
            val conversation = link.getStringExtra("conversation")
            if (conversation?.matches(Regex("[gp]:[1-9][0-9]*")) == true)
                nav.chat(conversation, link.getStringExtra("name") ?: "聊天")
            else link.data?.let { nav.link(it.toString()) }
            consumeLink()
        }
    }
    if (needsEmail) {
        EmailScreen(repo, ui) { ui.confirm("退出账户", "退出当前账户？") { graph.logout() } }
        return
    }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedMediaScope provides this) {
            NavHost(
                navController = controller,
                startDestination = "home",
                enterTransition = { pageEnterTransition(reduce) },
                // Keep the covered page in place until the incoming page fully covers it.
                exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { pagePopExitTransition(reduce) },
            ) {
                composable("home") { HomeScreen(graph, repo, ui, nav) }
                composable("moderation") { ModerationScreen(repo, ui, nav) }
                composable("diagnostics") { DiagnosticsScreen(repo, ui, nav) }
                composable(
                    "chat/{key}?name={name}&target={target}",
                    arguments =
                        listOf(
                            navArgument("name") { defaultValue = "聊天" },
                            navArgument("target") {
                                type = NavType.LongType
                                defaultValue = 0L
                            },
                        ),
                ) { entry ->
                    CompositionLocalProvider(LocalMediaVisibilityScope provides this) {
                        ChatScreen(
                            graph,
                            repo,
                            ui,
                            nav,
                            entry.arguments!!.getString("key")!!,
                            entry.arguments!!.getString("name")!!,
                            entry.arguments!!.getLong("target"),
                        )
                    }
                }
                composable(
                    "detail/{kind}/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    DetailScreen(
                        repo,
                        ui,
                        nav,
                        entry.arguments!!.getString("kind") == "group",
                        entry.arguments!!.getLong("id"),
                    )
                }
                composable(
                    "records/{kind}?id={id}&key={key}&manage={manage}",
                    arguments =
                        listOf(
                            navArgument("id") {
                                type = NavType.LongType
                                defaultValue = 0L
                            },
                            navArgument("key") { defaultValue = "" },
                            navArgument("manage") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                ) { entry ->
                    RecordsScreen(
                        repo,
                        ui,
                        nav,
                        entry.arguments!!.getString("kind")!!,
                        entry.arguments!!.getLong("id"),
                        entry.arguments!!.getString("key")!!,
                        entry.arguments!!.getBoolean("manage"),
                    )
                }
                composable(
                    "search?key={key}",
                    arguments = listOf(navArgument("key") { defaultValue = "" }),
                ) { entry ->
                    SearchScreen(repo, ui, nav, entry.arguments!!.getString("key")!!)
                }
                composable("settings") { SettingsScreen(graph, repo, ui, nav) }
                composable("about") { AboutScreen(ui, nav, repo.account.origin) }
                composable("personalization") { PersonalizationScreen(graph, ui, nav) }
                composable("message-display") { MessageDisplayScreen(graph, ui, nav) }
                composable("licenses") { LicensesScreen(nav) }
                composable("licenses/{id}") { entry ->
                    LicenseDetailScreen(entry.arguments!!.getString("id")!!, ui, nav)
                }
                composable("profile") { ProfileScreen(repo, ui, nav) }
                composable("email") { EmailScreen(repo, ui, nav::back) }
                composable("feed") { FeedScreen(repo, ui, nav) }
                composable("apps") { MiniAppsScreen(repo, ui, nav) }
                composable("miniapp/{id}") { entry ->
                    MiniAppScreen(repo, ui, nav, entry.arguments!!.getString("id")!!)
                }
                composable(
                    "qr?value={value}",
                    arguments = listOf(navArgument("value") { defaultValue = "" }),
                ) { entry ->
                    QrScreen(repo, ui, nav, entry.arguments!!.getString("value")!!)
                }
                composable(
                    "image?url={url}&tag={tag}",
                    arguments =
                        listOf(
                            navArgument("url") { defaultValue = "" },
                            navArgument("tag") { defaultValue = "" },
                        ),
                ) { entry ->
                    CompositionLocalProvider(LocalMediaVisibilityScope provides this) {
                        ImageScreen(
                            entry.arguments!!.getString("url")!!,
                            nav,
                            repo,
                            ui,
                            entry.arguments!!.getString("tag").orEmpty(),
                        )
                    }
                }
            }
        }
    }
}
