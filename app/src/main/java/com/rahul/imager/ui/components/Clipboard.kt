package com.rahul.imager.ui.components

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * Returns a function that copies plain text to the clipboard.
 *
 * Built on `LocalClipboard` rather than the deprecated `LocalClipboardManager`: the modern API is
 * suspending, so the copy is launched on the composition scope and the caller keeps a plain
 * synchronous lambda.
 */
@Composable
fun rememberClipboardCopier(label: String): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, label) {
        { text: String ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
            }
            Unit
        }
    }
}
