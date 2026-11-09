package com.xiaobai.rapidcsac.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu

/** Miuix owns anchor geometry, focus, back dismissal and interruptible popup physics. */
@Composable
fun AnchoredMenu(label: String, items: List<ActionItem>, icon: String = "more") {
    OverlayIconDropdownMenu(
        entry =
            DropdownEntry(
                items.map { action ->
                    DropdownItem(
                        text = action.title,
                        onClick = action.run,
                        icon = { modifier ->
                            Icon(rapidIcon(action.icon), null, modifier.size(20.dp))
                        },
                    )
                }
            ),
        minWidth = 48.dp,
        minHeight = 48.dp,
        maxHeight = 440.dp,
    ) {
        Icon(rapidIcon(icon), label, Modifier.size(24.dp))
    }
}
