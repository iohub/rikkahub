package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.image.RemoteImageGenerationClient
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.compose.koinInject

// 上传前把长边压到这个尺寸：全身照动辄十几 MB，不压必然撞服务端 16MB 上限
private const val PHOTO_MAX_SIZE = 1280

// 服务端照片库里的固定文件名：一张照片对应一个助手，不需要区分钟表
private const val PHOTO_FILENAME = "full_body.png"

/**
 * 助手的「全身照片」选择器：本地选图 → 压缩 → 上传服务端照片库 → 记住 photo_id。
 *
 * 上传成功后只把 `photo_id` 与本地预览地址交给 [onUpdate]；之后每次出图，服务端都会把
 * 这张照片固定放在 workflow 的 image1 上，用来保持人物外观一致。
 *
 * 所有失败都只显示一行红字，不抛异常：设置页不该因为一次网络抖动崩掉。
 */
@Composable
fun ReferencePhotoPicker(
    photoId: String?,
    photoUrl: String?,
    onUpdate: (id: String?, url: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filesManager: FilesManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val client: RemoteImageGenerationClient = koinInject()

    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        uploading = true
        error = null
        scope.launch {
            // 落盘的本地文件：任何一步失败都要删掉，否则 filesDir/upload 里留孤儿文件
            var savedUri: Uri? = null
            try {
                val config = settingsStore.settingsFlow.value.imageGenerationConfig
                // 服务地址没配就白上传了，先给一句明确的提示
                if (config.baseUrl.isBlank()) {
                    error = context.getString(R.string.reference_photo_need_service)
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) { readCompressedPng(context, uri) }
                if (bytes == null) {
                    error = context.getString(R.string.reference_photo_upload_failed, "图片读取失败")
                    return@launch
                }
                val saved = withContext(Dispatchers.IO) {
                    filesManager.createChatFilesByByteArrays(
                        byteArrays = listOf(bytes),
                        displayName = PHOTO_FILENAME,
                        mimeType = "image/png",
                    ).firstOrNull()
                }
                if (saved == null) {
                    error = context.getString(R.string.reference_photo_upload_failed, "本地保存失败")
                    return@launch
                }
                savedUri = saved
                // 服务端只认完整 data URL，裸 base64 会被 400 拒掉
                val dataUrl = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                val uploaded = client.uploadPhoto(config, dataUrl, PHOTO_FILENAME)
                val photo = uploaded.getOrNull()
                if (photo == null) {
                    withContext(Dispatchers.IO) { filesManager.deleteChatFiles(listOf(saved)) }
                    savedUri = null
                    error = context.getString(
                        R.string.reference_photo_upload_failed,
                        uploaded.exceptionOrNull()?.message ?: "unknown",
                    )
                    return@launch
                }
                // 换照片时旧照片在服务端留着只占磁盘，best-effort 清掉（失败不影响本地流程）
                photoId?.takeIf { it.isNotBlank() && it != photo.photoId }?.let { oldId ->
                    runCatching { client.deletePhoto(config, oldId) }
                }
                error = null
                onUpdate(photo.photoId, saved.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                savedUri?.let { stale ->
                    withContext(Dispatchers.IO) { filesManager.deleteChatFiles(listOf(stale)) }
                }
                error = context.getString(
                    R.string.reference_photo_upload_failed,
                    t.message ?: t::class.simpleName ?: "unknown",
                )
            } finally {
                uploading = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { imagePickerLauncher.launch("image/*") },
                enabled = !uploading,
            ) {
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Image02,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                }
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(
                    text = when {
                        uploading -> stringResource(R.string.reference_photo_uploading)
                        photoId != null -> stringResource(R.string.reference_photo_change)
                        else -> stringResource(R.string.reference_photo_pick)
                    }
                )
            }
            if (photoId != null && !uploading) {
                TextButton(
                    onClick = {
                        error = null
                        val oldId = photoId
                        val oldUrl = photoUrl
                        // 先清本地状态：UI 立刻回到「没照片」，网络清理放在后台
                        onUpdate(null, null)
                        scope.launch {
                            val config = settingsStore.settingsFlow.value.imageGenerationConfig
                            if (config.baseUrl.isNotBlank()) {
                                runCatching { client.deletePhoto(config, oldId) }
                            }
                            oldUrl?.let { url ->
                                withContext(Dispatchers.IO) {
                                    runCatching { filesManager.deleteChatFiles(listOf(url.toUri())) }
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.reference_photo_remove))
                }
            }
        }

        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 把用户选中的图片压到 [PHOTO_MAX_SIZE] 长边并转成 PNG 字节；读不出来返回 null。
 *
 * 极少数格式（HEIC 之类）BitmapFactory 解不开，这时退回原始字节交给服务端判断。
 */
private fun readCompressedPng(context: Context, uri: Uri): ByteArray? {
    val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, PHOTO_MAX_SIZE)
    if (bitmap != null) {
        val bytes = runCatching { FileUtils.compressBitmapToPng(bitmap) }.getOrNull()
        runCatching { bitmap.recycle() }
        return bytes
    }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()
}
