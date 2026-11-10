package com.xiaobai.rapidcsac

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.core.security.Account
import com.xiaobai.rapidcsac.core.security.SessionVault
import com.xiaobai.rapidcsac.platform.BackgroundSync
import com.xiaobai.rapidcsac.platform.ChatWallpaperStore
import com.xiaobai.rapidcsac.platform.MessageDisplayPreferences
import com.xiaobai.rapidcsac.platform.Notifications
import com.xiaobai.rapidcsac.platform.PersonalizationPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject

class RapidApplication : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.applyTheme()
        ProcessLifecycleOwner.get().lifecycle.addObserver(graph)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(graph.http)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    add(coil.decode.ImageDecoderDecoder.Factory())
                else add(coil.decode.GifDecoder.Factory())
            }
            .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(.08).build() }
            .crossfade(false)
            .respectCacheHeaders(true)
            .build()
}

class AppGraph(val context: Context) : DefaultLifecycleObserver {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val messageDisplay = MessageDisplayPreferences(preferences)
    val personalization = PersonalizationPreferences(preferences)
    val wallpapers = ChatWallpaperStore(context, preferences)
    val vault = SessionVault(context)
    val http =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    private val accountLock = Mutex()
    private val mutableRepository = MutableStateFlow<ChatRepository?>(null)
    val repository = mutableRepository.asStateFlow()
    val loading = MutableStateFlow(true)
    val theme = MutableStateFlow(preferences.getString("theme", "system") ?: "system")
    val reducedMotion = MutableStateFlow(preferences.getBoolean("reduce_motion", false))
    val lockEnabled = MutableStateFlow(preferences.getBoolean("app_lock", false))

    fun setAppLock(enabled: Boolean) {
        preferences.edit().putBoolean("app_lock", enabled).apply()
        lockEnabled.value = enabled
    }

    var foreground = false
        private set

    var backgroundAt = 0L
    private var stopJob: Job? = null

    init {
        scope.launch {
            val account = withContext(Dispatchers.IO) { vault.active() }
            if (account != null) select(account)
            loading.value = false
        }
    }

    suspend fun select(account: Account) =
        withContext(NonCancellable) {
            accountLock.withLock {
                withContext(Dispatchers.IO) { vault.save(account) }
                context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                mutableRepository.value?.shutdown()
                val repo = ChatRepository(context, account, http)
                repo.onNewMessage = { conversation ->
                    Notifications.message(context, conversation, account.key)
                }
                repo.onExpired = {
                    scope.launch {
                        accountLock.withLock {
                            if (mutableRepository.value === repo) {
                                withContext(Dispatchers.IO) { vault.remove(account) }
                                mutableRepository.value = null
                                repo.shutdown()
                            }
                        }
                    }
                }
                mutableRepository.value = repo
                if (foreground) repo.start()
            }
        }

    suspend fun authenticate(origin: String, method: String, params: JSONObject): JSONObject {
        val address =
            origin.trim().trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalArgumentException("请输入有效的 HTTPS 服务器地址")
        require(
            address.isHttps &&
                address.encodedPath == "/" &&
                address.username.isEmpty() &&
                address.password.isEmpty() &&
                address.query == null
        ) {
            "服务器地址必须是 HTTPS 域名"
        }
        val transport = WsClient(http, address.toString().trimEnd('/'))
        return try {
            transport.connect("")
            val result = transport.call(method, params)
            result.optJSONObject("session")?.let { session ->
                val user = result.optJSONObject("data")?.optJSONObject("user")
                select(
                    Account(
                        address.toString().trimEnd('/'),
                        session.getLong("uid"),
                        user?.optString("nickname").orEmpty(),
                        session.getString("token"),
                    )
                )
            }
            result.optJSONObject("data") ?: result
        } finally {
            transport.disconnect()
        }
    }

    suspend fun logout() =
        withContext(NonCancellable) {
            accountLock.withLock {
                val repo = repository.value ?: return@withLock
                withTimeoutOrNull(3000) { runCatching { repo.execute("auth/logout") } }
                withContext(Dispatchers.IO) { vault.remove(repo.account) }
                mutableRepository.value = null
                repo.shutdown()
                BackgroundSync.schedule(context, false)
            }
        }

    suspend fun addAccount() =
        withContext(NonCancellable) {
            accountLock.withLock {
                withContext(Dispatchers.IO) { vault.deselect() }
                val repo = repository.value
                mutableRepository.value = null
                repo?.shutdown()
            }
        }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        stopJob?.cancel()
        repository.value?.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        backgroundAt = System.currentTimeMillis()
        stopJob = scope.launch {
            delay(20_000)
            repository.value?.pause()
        }
    }

    fun applyTheme() {
        theme.value = preferences.getString("theme", "system") ?: "system"
    }

    fun setReducedMotion(enabled: Boolean) {
        preferences.edit().putBoolean("reduce_motion", enabled).apply()
        reducedMotion.value = enabled
    }
}
