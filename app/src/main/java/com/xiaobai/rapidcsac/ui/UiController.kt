package com.xiaobai.rapidcsac.ui

import androidx.compose.runtime.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.SnackbarHostState

@Immutable
data class InputField(
    val key: String,
    val label: String,
    val initial: String = "",
    val password: Boolean = false,
    val multiline: Boolean = false,
    val numeric: Boolean = false,
    val choices: List<String> = emptyList(),
    val toggle: Boolean = false,
    val visibleWhen: Pair<String, String>? = null,
)

data class Editor(
    val title: String,
    val fields: List<InputField>,
    val submit: suspend (JSONObject) -> Unit,
    val id: String = UUID.randomUUID().toString(),
)

data class ActionItem(
    val title: String,
    val icon: String = "next",
    val destructive: Boolean = false,
    val swatchColor: Int? = null,
    val run: () -> Unit,
)

data class ActionMenu(val title: String, val actions: List<ActionItem>)

data class Confirmation(
    val title: String,
    val body: String,
    val destructive: Boolean = false,
    val action: suspend () -> Unit,
)

/** Shared library-backed feedback and forms keep async errors and dismissal behavior consistent. */
@Stable
class UiController(private val scope: CoroutineScope) {
    val snackbar = SnackbarHostState()
    var editor by mutableStateOf<Editor?>(null)
    var menu by mutableStateOf<ActionMenu?>(null)
    var confirmation by mutableStateOf<Confirmation?>(null)
    var textViewer by mutableStateOf<String?>(null)

    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: "操作失败，请重试")
            }
        }
    }

    fun inform(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun edit(title: String, fields: List<InputField>, submit: suspend (JSONObject) -> Unit) {
        menu = null
        editor = Editor(title, fields, submit)
    }

    fun confirm(
        title: String,
        body: String,
        destructive: Boolean = false,
        action: suspend () -> Unit,
    ) {
        menu = null
        confirmation = Confirmation(title, body, destructive, action)
    }

    fun actions(title: String, actions: List<ActionItem>) {
        menu = ActionMenu(title, actions)
    }
}
