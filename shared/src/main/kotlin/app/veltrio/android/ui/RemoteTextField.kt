package app.veltrio.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import app.veltrio.android.LocalTv

/** A text field a remote can pass over: on a TV, focus lands on the frame
 *  and the keyboard only opens on OK, instead of popping up (and taking the
 *  D-pad) whenever the focus crosses the field. On a phone, the field as is.
 *  [field] gets the modifier to put on the text field itself. */
@Composable
fun RemoteTextField(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    field: @Composable (Modifier) -> Unit,
) {
    if (!LocalTv.current) {
        Box(modifier.padding(contentPadding)) { field(Modifier) }
        return
    }
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) runCatching { requester.requestFocus() } }
    Box(modifier.focusProperties { canFocus = !editing }.clickable { editing = true }.padding(contentPadding)) {
        field(
            Modifier.focusRequester(requester).focusProperties { canFocus = editing }.onFocusChanged {
                // Leaving the field (D-pad away) ends the edit.
                if (focused && !it.isFocused) editing = false
                focused = it.isFocused
            },
        )
    }
}
