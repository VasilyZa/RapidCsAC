package com.xiaobai.rapidcsac.screens

import android.content.Context
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.core.network.json
import com.xiaobai.rapidcsac.platform.copyText
import com.xiaobai.rapidcsac.platform.exportChat
import com.xiaobai.rapidcsac.ui.*

fun messageMenu(
    message: Message,
    repo: ChatRepository,
    ui: UiController,
    nav: Navigation,
    key: String,
    context: Context,
    select: (Message) -> Unit = {},
    reply: (Message) -> Unit,
) {
    if (message.clientKey.isNotEmpty()) {
        val actions = mutableListOf<ActionItem>()
        if (message.delivery != "sending")
            actions +=
                ActionItem("确认发送状态", "refresh") { ui.launch { repo.retry(message.clientKey) } }
        actions += ActionItem("复制内容", "copy") { copyText(context, message.text) }
        actions +=
            ActionItem("移除本地发送记录", "delete", true) { ui.launch { repo.discard(message.clientKey) } }
        ui.actions(
            when (message.delivery) {
                "failed" -> "发送失败"
                "uncertain" -> "发送状态待确认"
                else -> "待发送消息"
            },
            actions,
        )
        return
    }
    val group = key.startsWith("g:")
    val id = key.substringAfter(':').toLong()
    val actions = mutableListOf(ActionItem("查看发送者", "person") { nav.detail(false, message.sender) })
    actions += ActionItem("多选", "check") { select(message) }
    actions +=
        ActionItem("从本机删除", "delete", true) {
            ui.confirm("从本机删除", "删除这条消息的本机记录？", true) {
                repo.deleteLocally(key, listOf(message.id))
            }
        }
    if (!message.recalled) {
        actions.add(0, ActionItem("回复", "reply") { reply(message) })
        actions += ActionItem("复制", "copy") { copyText(context, message.text) }
        if (message.type in setOf(1, 2, 3, 5, 6))
            actions +=
                ActionItem("转发", "share") { forwardMessages(listOf(message), repo, ui, context) }
        if (message.type == 1) {
            actions += ActionItem("选择文字", "copy") { ui.textViewer = message.text }
            actions +=
                ActionItem("分享文字", "share") {
                    com.xiaobai.rapidcsac.platform.shareText(context, message.text)
                }
            actions += ActionItem("二维码分享", "scan") { nav.qr(message.text) }
            actions += ActionItem("复读 +1", "add") { ui.launch { repo.send(key, message.text) } }
        }
        if (message.canRecall)
            actions +=
                ActionItem("撤回", "delete", true) {
                    ui.confirm("撤回消息", "撤回这条消息？", true) {
                        repo.execute(
                            "message/recall_msg",
                            json(
                                "msg_id" to message.id,
                                "type" to if (group) "group" else "private",
                                "room_id" to if (group) id else 0,
                            ),
                        )
                        if (repo.activeConversation == key) repo.openConversation(key)
                    }
                }
        if (group)
            actions +=
                ActionItem(if (message.essence) "取消精华" else "设为精华", "heart") {
                    ui.launch {
                        repo.execute(
                            "essence/set_essence",
                            json("room_id" to id, "msg_id" to message.id),
                        )
                        if (repo.activeConversation == key) repo.openConversation(key)
                    }
                }
    }
    ui.actions("消息操作", actions)
}

fun chatActions(
    repo: ChatRepository,
    ui: UiController,
    nav: Navigation,
    key: String,
    name: String,
    context: Context,
): List<ActionItem> {
    val group = key.startsWith("g:")
    val id = key.substringAfter(':').toLong()
    val actions =
        mutableListOf(
            ActionItem("聊天资料", "person") { nav.detail(group, id) },
            ActionItem("搜索记录", "search") { nav.search(key) },
            ActionItem("共享媒体", "image") { nav.records("media", id, key) },
            ActionItem("导出本机聊天记录", "download") { ui.launch { exportChat(context, repo, key) } },
        )
    if (group) {
        actions += ActionItem("群精华", "heart") { nav.records("essence", id, key) }
        actions += ActionItem("群文件", "file") { nav.records("files", id, key) }
    }
    return actions
}

fun forwardMessages(
    messages: List<Message>,
    repo: ChatRepository,
    ui: UiController,
    context: Context,
    complete: () -> Unit = {},
) {
    val supported = messages.filter {
        !it.recalled && it.type in setOf(1, 2, 3, 5, 6) && it.clientKey.isEmpty()
    }
    if (supported.size != messages.size) {
        ui.inform("所选消息包含无法转发的内容")
        return
    }
    ui.actions(
        "转发到",
        repo.conversations.value
            .filter { !it.hidden }
            .map { conversation ->
                ActionItem(conversation.name, "chat") {
                    var forwarded = 0
                    var uncertain = false
                    ui.confirm("转发到 ${conversation.name}", "转发 ${supported.size} 条消息？") {
                        check(!uncertain) { "此批转发结果待确认，请先检查目标会话" }
                        try {
                            supported
                                .sortedBy { it.id }
                                .drop(forwarded)
                                .forEach {
                                    com.xiaobai.rapidcsac.platform.forwardMessage(
                                        context,
                                        repo,
                                        conversation.key,
                                        it,
                                    )
                                    forwarded++
                                }
                        } catch (failure: kotlinx.coroutines.CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            uncertain = true
                            error("已处理 $forwarded 条，请检查目标会话确认后续结果。${failure.message.orEmpty()}")
                        }
                        complete()
                        ui.inform("转发已处理")
                    }
                }
            },
    )
}
