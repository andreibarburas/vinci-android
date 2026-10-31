package com.brbrs.vinci.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.FileOutputStream

/**
 * A simple finger-drawn sketch, saved as a PNG and handed back as a file Uri the caller can
 * pass straight into the existing attachment flow (attachFiles() already reads via
 * ContentResolver.openInputStream, which works with file:// Uris natively).
 */
@Composable
fun SketchPadDialog(
    onDismiss: () -> Unit,
    onSave: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var currentStroke by remember { mutableStateOf<MutableList<Offset>?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "Cancel", tint = Color.Black)
                    }
                    Text("Sketch", style = MaterialTheme.typography.titleMedium, color = Color.Black)
                    Row {
                        TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                        TextButton(
                            enabled = strokes.isNotEmpty() && canvasSize.width > 0,
                            onClick = {
                                renderStrokesToFile(context, strokes, canvasSize.width, canvasSize.height)?.let(onSave)
                            },
                        ) { Text("Save") }
                    }
                }
                HorizontalDivider()
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val stroke = mutableListOf(offset)
                                    currentStroke = stroke
                                    strokes.add(stroke)
                                },
                                onDrag = { change, _ -> currentStroke?.add(change.position) },
                                onDragEnd = { currentStroke = null },
                            )
                        },
                ) {
                    strokes.forEach { stroke ->
                        for (i in 0 until stroke.size - 1) {
                            drawLine(Color.Black, stroke[i], stroke[i + 1], strokeWidth = 6f, cap = StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

private fun renderStrokesToFile(context: Context, strokes: List<List<Offset>>, width: Int, height: Int): Uri? {
    if (width <= 0 || height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 6f
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            isAntiAlias = true
        }
        strokes.forEach { stroke ->
            for (i in 0 until stroke.size - 1) {
                canvas.drawLine(stroke[i].x, stroke[i].y, stroke[i + 1].x, stroke[i + 1].y, paint)
            }
        }
        val file = File(context.cacheDir, "vinci_sketch_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Uri.fromFile(file)
    }.getOrNull()
}
