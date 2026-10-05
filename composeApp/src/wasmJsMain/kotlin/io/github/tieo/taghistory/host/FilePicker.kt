package io.github.tieo.taghistory.host

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.coroutines.resume

/**
 * Opens the browser's file chooser for one file matching [accept] and
 * returns its bytes, or null when the user closes the chooser.
 */
suspend fun pickFile(accept: String): ByteArray? = suspendCancellableCoroutine { cont ->
    openFilePicker(accept) { buffer ->
        if (cont.isActive) cont.resume(buffer?.toByteArray())
    }
}

private fun ArrayBuffer.toByteArray(): ByteArray {
    val view = Int8Array(this)
    return ByteArray(view.length) { view[it] }
}

// The `cancel` event fires when the chooser closes without a pick, so the
// caller is resumed either way.
private fun openFilePicker(accept: String, onResult: (ArrayBuffer?) -> Unit): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.addEventListener('cancel', () => onResult(null));
    input.addEventListener('change', () => {
        const file = input.files && input.files[0];
        if (!file) { onResult(null); return; }
        file.arrayBuffer().then((b) => onResult(b), () => onResult(null));
    });
    input.click();
    }""",
)
