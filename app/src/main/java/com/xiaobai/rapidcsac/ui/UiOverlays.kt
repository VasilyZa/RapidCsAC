package com.xiaobai.rapidcsac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun UiOverlays(ui: UiController) {
    var retainedText by remember { mutableStateOf("") }
    LaunchedEffect(ui.textViewer) { ui.textViewer?.let { retainedText = it } }
    if (ui.textViewer != null || retainedText.isNotEmpty())
        CompactSheet(
            show = ui.textViewer != null,
            title = "选择文字",
            onDismissRequest = { ui.textViewer = null },
            onDismissFinished = { if (ui.textViewer == null) retainedText = "" },
        ) {
            SelectionContainer(
                Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .65f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(ui.textViewer ?: retainedText)
            }
        }

    // Retain content until the library completes the exit transition.
    var lastMenu by remember { mutableStateOf<ActionMenu?>(null) }
    var lastEditor by remember { mutableStateOf<Editor?>(null) }
    var lastConfirmation by remember { mutableStateOf<Confirmation?>(null) }
    LaunchedEffect(ui.menu) { ui.menu?.let { lastMenu = it } }
    LaunchedEffect(ui.editor) { ui.editor?.let { lastEditor = it } }
    LaunchedEffect(ui.confirmation) { ui.confirmation?.let { lastConfirmation = it } }
    val menu = ui.menu ?: lastMenu
    if (menu != null)
        CompactSheet(
            show = ui.menu != null,
            title = menu.title,
            onDismissRequest = { ui.menu = null },
            onDismissFinished = { if (ui.menu == null) lastMenu = null },
        ) {
            val maximumHeight = LocalWindowInfo.current.containerDpSize.height * .65f
            key(menu) {
                LazyColumn(Modifier.heightIn(max = maximumHeight)) {
                    itemsIndexed(menu.actions) { _, action ->
                        CompactSheetRow(
                            title = action.title,
                            destructive = action.destructive,
                            leading = {
                                if (action.swatchColor != null)
                                    Box(
                                        Modifier.size(24.dp)
                                            .background(Color(action.swatchColor), CircleShape)
                                            .border(
                                                1.dp,
                                                MiuixTheme.colorScheme.outline,
                                                CircleShape,
                                            )
                                    )
                                else
                                    Icon(
                                        rapidIcon(action.icon),
                                        null,
                                        Modifier.size(24.dp),
                                        tint =
                                            if (action.destructive) MiuixTheme.colorScheme.error
                                            else MiuixTheme.colorScheme.onSurface,
                                    )
                            },
                            onClick = {
                                ui.menu = null
                                action.run()
                            },
                        )
                    }
                }
            }
        }
    val editor = ui.editor ?: lastEditor
    if (editor != null)
        key(editor.id) {
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            val values = remember {
                mutableStateMapOf<String, String>().apply {
                    editor.fields.forEach { put(it.key, it.initial) }
                }
            }
            val scope = rememberCoroutineScope()
            val focus = LocalFocusManager.current
            CompactSheet(
                show = ui.editor != null,
                title = editor.title,
                dismissLabel = "取消",
                allowDismiss = !busy,
                onDismissRequest = { if (!busy) ui.editor = null },
                onDismissFinished = { if (ui.editor == null) lastEditor = null },
            ) {
                Column(
                    Modifier.imePadding().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    editor.fields
                        .filter { field ->
                            val condition = field.visibleWhen
                            condition == null || values[condition.first] == condition.second
                        }
                        .forEach { field ->
                            when {
                                field.choices.isNotEmpty() ->
                                    OverlaySpinnerPreference(
                                        items = field.choices.map { DropdownItem(text = it) },
                                        selectedIndex =
                                            (values[field.key]?.toIntOrNull() ?: 0).coerceIn(
                                                field.choices.indices
                                            ),
                                        title = field.label,
                                        insideMargin =
                                            PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        enabled = !busy,
                                        onSelectedIndexChange = {
                                            values[field.key] = it.toString()
                                        },
                                    )
                                field.toggle ->
                                    SwitchPreference(
                                        checked = values[field.key] == "1",
                                        title = field.label,
                                        insideMargin =
                                            PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        enabled = !busy,
                                        onCheckedChange = {
                                            values[field.key] = if (it) "1" else "0"
                                        },
                                    )
                                else ->
                                    RapidField(
                                        values[field.key].orEmpty(),
                                        { values[field.key] = it },
                                        field.label,
                                        password = field.password,
                                        multiline = field.multiline,
                                        numeric = field.numeric,
                                        enabled = !busy,
                                    )
                            }
                        }
                    if (error.isNotEmpty()) Text(error, color = MiuixTheme.colorScheme.error)
                    PrimaryButton(if (busy) "正在提交…" else "确定", Modifier.fillMaxWidth(), !busy) {
                        focus.clearFocus()
                        busy = true
                        error = ""
                        scope.launch {
                            try {
                                editor.submit(
                                    JSONObject().apply {
                                        values.forEach { (key, value) -> put(key, value) }
                                    }
                                )
                                if (ui.editor === editor) ui.editor = null
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                error = failure.message ?: "操作失败，请重试"
                            } finally {
                                busy = false
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    val confirmation = ui.confirmation ?: lastConfirmation
    if (confirmation != null)
        key(confirmation) {
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            OverlayDialog(
                show = ui.confirmation != null,
                title = confirmation.title,
                summary = confirmation.body,
                onDismissRequest = { if (!busy) ui.confirmation = null },
                onDismissFinished = { if (ui.confirmation == null) lastConfirmation = null },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (error.isNotEmpty()) Text(error, color = MiuixTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button({ ui.confirmation = null }, Modifier.weight(1f), enabled = !busy) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        confirmation.action()
                                        ui.confirmation = null
                                    } catch (failure: CancellationException) {
                                        throw failure
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "操作失败，请重试"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            colors =
                                if (confirmation.destructive)
                                    ButtonDefaults.buttonColors(
                                        color = MiuixTheme.colorScheme.error,
                                        contentColor = MiuixTheme.colorScheme.onError,
                                    )
                                else ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(if (busy) "处理中…" else "确定")
                        }
                    }
                }
            }
        }
}
