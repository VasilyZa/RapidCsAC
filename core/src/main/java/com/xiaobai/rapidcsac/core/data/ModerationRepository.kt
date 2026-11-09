package com.xiaobai.rapidcsac.core.data

import com.xiaobai.rapidcsac.core.network.ApiFailure
import com.xiaobai.rapidcsac.core.network.HttpApi
import com.xiaobai.rapidcsac.core.network.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Administration remains separately authorized by the server for every request. */
class ModerationRepository(private val http: HttpApi) {
    private val lock = Mutex()
    private val mutableAllowed = MutableStateFlow<Boolean?>(null)
    val allowed = mutableAllowed.asStateFlow()

    suspend fun probe() {
        try {
            freshToken()
            mutableAllowed.value = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ApiFailure) {
            if (failure.code == 403) mutableAllowed.value = false
        } catch (_: Exception) {
            // A network outage is not evidence that an administrator lost their role.
        }
    }

    private suspend fun freshToken(): String =
        http.postRoute("admin/generate_token", JSONObject()).getString("token")

    suspend fun bans(): JSONObject = lock.withLock {
        http.getRoute("admin/admin_ban", freshToken())
    }

    suspend fun ban(group: Boolean, id: Long, days: Int, reason: String): JSONObject =
        lock.withLock {
            require(id > 0 && days in 1..3650 && reason.isNotBlank()) { "请填写有效 ID、1–3650 天和封禁原因" }
            http.postRoute(
                "admin/admin_ban",
                json(
                    "action" to if (group) "ban_room" else "ban_user",
                    (if (group) "room_id" else "user_id") to id,
                    "ban_days" to days,
                    "ban_reason" to reason,
                    "token" to freshToken(),
                ),
            )
        }

    suspend fun unban(group: Boolean, id: Long): JSONObject = lock.withLock {
        require(id > 0)
        http.postRoute(
            "admin/admin_ban",
            json(
                "action" to if (group) "unban_room" else "unban_user",
                (if (group) "room_id" else "user_id") to id,
                "token" to freshToken(),
            ),
        )
    }

    suspend fun restore(id: Long): JSONObject {
        require(id > 0)
        return http.postRoute("admin/restore_account", json("uid" to id))
    }
}
