package com.xiaobai.rapidcsac.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.xiaobai.rapidcsac.BuildConfig

/** A per-run snapshot without device identifiers, SSIDs, addresses or credentials. */
fun networkEnvironment(context: Context): List<Pair<String, String>> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork
    val capabilities = network?.let(manager::getNetworkCapabilities)
    val properties = network?.let(manager::getLinkProperties)
    val transports =
        listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
                NetworkCapabilities.TRANSPORT_CELLULAR to "移动网络",
                NetworkCapabilities.TRANSPORT_ETHERNET to "以太网",
                NetworkCapabilities.TRANSPORT_VPN to "VPN",
                NetworkCapabilities.TRANSPORT_BLUETOOTH to "蓝牙网络",
            )
            .filter { capabilities?.hasTransport(it.first) == true }
            .map { it.second }
    val connectivity =
        when {
            network == null -> "无可用网络"
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ==
                true -> "需要网络登录认证"
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ->
                "系统已验证联网"
            else -> "系统尚未验证联网"
        }
    return buildList {
        add(
            "网络类型" to
                transports.joinToString(" · ").ifEmpty { if (network == null) "未连接" else "其他网络" }
        )
        add("系统网络状态" to connectivity)
        add(
            "流量计费" to
                when {
                    network == null -> "未知"
                    manager.isActiveNetworkMetered -> "按流量计费"
                    else -> "不限流量计费"
                }
        )
        add("系统代理" to if (properties?.httpProxy != null) "已配置" else "未配置")
        if (Build.VERSION.SDK_INT >= 28)
            add("私人 DNS" to if (properties?.isPrivateDnsActive == true) "已启用" else "未启用")
        add("客户端" to "RapidCsAC ${BuildConfig.VERSION_NAME}")
        add("系统" to "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
        add("设备" to "${Build.MANUFACTURER} ${Build.MODEL}")
    }
}
