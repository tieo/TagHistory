package io.github.tieo.taghistory.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import org.jetbrains.skia.Image
import java.io.File

/**
 * Off-screen renderer: paints a composable to a PNG with no display, so every
 * view in the app can be reviewed as a gallery of images. Runs on the JVM
 * (compose.desktop.currentOs) from the same composables the app ships, so a
 * picture cannot quietly stop matching the screen it came from.
 */
fun renderToPng(
    name: String,
    widthDp: Int,
    heightDp: Int,
    dark: Boolean = false,
    outDir: File,
    // Pixels per dp. A phone render is read at its own size and wants two; a
    // window-wide one is only looked at small (half a window beside the phone),
    // so it stays legible at a lower scale and costs far fewer pixels.
    scale: Float = 2f,
    content: @Composable () -> Unit,
) {
    val density = Density(scale)
    val scene = ImageComposeScene(
        width = (widthDp * density.density).toInt(),
        height = (heightDp * density.density).toInt(),
        density = density,
    ) {
        TagHistoryTheme(darkTheme = dark) {
            // The window behind the screen. A sheet or overlay drawn in place
            // carries no background of its own, so without this a dark render
            // comes out as pale content on white.
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
    try {
        val img: Image = scene.render()
        val png = img.encodeToData()!!.bytes
        outDir.mkdirs()
        File(outDir, "$name.png").writeBytes(png)
    } finally {
        scene.close()
    }
}

fun main() {
    val outDir = File(System.getProperty("gallery.out") ?: "build/gallery")
    renderToPng("smoke", 360, 120, outDir = outDir) {
        Text("render harness works", Modifier.padding(24.dp))
    }
    println("wrote ${File(outDir, "smoke.png").absolutePath}")
}
