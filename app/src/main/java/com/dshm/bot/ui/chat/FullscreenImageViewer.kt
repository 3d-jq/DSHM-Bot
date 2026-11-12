package com.dshm.bot.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.dshm.bot.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏图片查看器：点对话里的图片缩略图打开。
 *
 * 行为对齐 DeepSearch 的 `FullscreenImageViewer`：沉浸式全屏、双击放大/还原、
 * 双指缩放与拖动、单击切换工具栏，底部是「复制 / 分享 / 保存」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FullscreenImageViewer(
    item: ChatAttachmentItem,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val file = remember(item.path) { File(item.path) }

    // 进入隐藏系统栏，退出恢复。只改 appearance 与可见性，不动
    // `decorFitsSystemWindows`（那是 MainActivity 的 edge-to-edge 状态，不能碰）。
    DisposableEffect(Unit) {
        val window = findActivity(view.context)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var showChrome by remember { mutableStateOf(true) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AsyncImage(
                model = file,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 8f)
                            scale = next
                            offset = if (next > 1f) offset + pan else Offset.Zero
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                            onTap = { showChrome = !showChrome },
                        )
                    },
            )

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = context.getString(R.string.image_viewer_close),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val pill = RoundedCornerShape(32.dp)
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .background(Color(0xFF3A3A3C), pill)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), pill)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    ImageAction(
                        label = context.getString(R.string.image_action_copy),
                        icon = R.drawable.ic_lucide_copy,
                        onClick = {
                            scope.launch { copyImageToClipboard(context, file) }
                        },
                    )
                    Spacer(Modifier.width(28.dp))
                    ImageAction(
                        label = context.getString(R.string.image_action_share),
                        icon = R.drawable.ic_lucide_share_2,
                        onClick = { shareImage(context, file, item.name) },
                    )
                    Spacer(Modifier.width(28.dp))
                    ImageAction(
                        label = context.getString(R.string.image_action_save),
                        icon = R.drawable.ic_lucide_download,
                        onClick = {
                            scope.launch { saveImageToGallery(context, file, item.name) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageAction(label: String, icon: Int, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = label,
            tint = Color.White,
        )
        Spacer(Modifier.width(6.dp))
        androidx.compose.material3.Text(
            text = label,
            color = Color.White,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}

private suspend fun copyImageToClipboard(context: Context, file: File) {
    withContext(Dispatchers.IO) {
        // FileProvider 没配到这个路径就会抛 IllegalArgumentException —— 不能让整个
        // App 挂在一次「复制」上，失败就提示。
        val clip = runCatching {
            val uri = fileUri(context, file)
            // 允许任何粘贴目标读取这张图（图库 / 输入法预览 / 文件管理器）。
            context.grantUriPermission("*", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ClipData.newUri(context.contentResolver, file.name, uri)
        }.getOrNull()
        withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clip == null || clipboard == null) {
                Toast.makeText(context, R.string.image_copy_failed, Toast.LENGTH_SHORT).show()
            } else {
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, R.string.image_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun shareImage(context: Context, file: File, name: String) {
    val shared = runCatching {
        val uri = fileUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, name))
    }
    if (shared.isFailure) {
        Toast.makeText(context, R.string.image_share_failed, Toast.LENGTH_SHORT).show()
    }
}

private suspend fun saveImageToGallery(context: Context, file: File, name: String) {
    withContext(Dispatchers.IO) {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeOf(name))
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri != null && runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> out.write(file.readBytes()) }
            }.isSuccess
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            runCatching { file.copyTo(File(dir, name), overwrite = true) }.isSuccess
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (ok) R.string.image_saved else R.string.image_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

private fun fileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

/** 从可能被 Dialog 包裹的 context 一路找到 Activity。 */
private tailrec fun findActivity(context: android.content.Context): android.app.Activity? =
    when (context) {
        is android.app.Activity -> context
        is android.content.ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
