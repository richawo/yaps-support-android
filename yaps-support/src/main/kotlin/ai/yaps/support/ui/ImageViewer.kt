package ai.yaps.support.ui

import ai.yaps.support.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Full-screen photo viewer: swipe between a message's photos, pinch to zoom, drag to pan,
 * double-tap to toggle 1× / 2.5×. Back or the close button dismisses it.
 */
@Composable
internal fun ImageViewer(images: List<ViewerImage>, start: Int, imageLoader: ImageLoader?, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(images[page], imageLoader, onZoomChange = { zoomed = it })
            }
            if (images.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${images.size}",
                    style = TypeTokens.Tag,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 16.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp)
                    .size(44.dp)
                    .clip(CircleShape),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.yaps_support_close_photo))
            }
        }
    }
}

@Composable
private fun ZoomableImage(image: ViewerImage, imageLoader: ImageLoader?, onZoomChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val shown by animateFloatAsState(scale, if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f), label = "zoom")
    fun set(s: Float, o: Offset) {
        scale = s
        offset = o
        onZoomChange(s > 1.01f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) set(1f, Offset.Zero)
                    else set(2.5f, (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f)
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, 5f)
                    val maxX = size.width * (s - 1f) / 2f
                    val maxY = size.height * (s - 1f) / 2f
                    set(s, if (s <= 1f) Offset.Zero else Offset((offset.x + pan.x * s).coerceIn(-maxX, maxX), (offset.y + pan.y * s).coerceIn(-maxY, maxY)))
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(image.url).apply { image.cacheKey?.let { memoryCacheKey(it); diskCacheKey(it) } }.build(),
            imageLoader = imageLoader ?: coil3.SingletonImageLoader.get(context),
            contentDescription = image.description ?: stringResource(R.string.yaps_support_photo),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = shown
                scaleY = shown
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}
