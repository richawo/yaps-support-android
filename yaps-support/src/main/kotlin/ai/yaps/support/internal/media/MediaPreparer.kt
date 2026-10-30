package ai.yaps.support.internal.media

import ai.yaps.support.internal.core.Limits
import ai.yaps.support.internal.core.Ulid
import ai.yaps.support.internal.outbox.PendingAttachment
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Our own FileProvider subclass, so its manifest entry never collides with the host app's. */
internal class SupportFileProvider : FileProvider()

internal sealed interface PrepareResult {
    data class Ok(val attachment: PendingAttachment) : PrepareResult
    data object Unreadable : PrepareResult
    data object TooLarge : PrepareResult
    data object UnsupportedType : PrepareResult
}

internal object MediaPreparer {

    /** A content URI the system camera can write a full-size capture into. */
    fun newCameraUri(context: Context): Pair<Uri, File> {
        val dir = File(context.cacheDir, "yaps-support/camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.yapssupport.fileprovider", file)
        return uri to file
    }

    /**
     * Decodes [uri], applies its EXIF orientation, downscales to ≤ 2,048 px on the long edge and
     * re-encodes as JPEG ≤ 1 MiB (PROTOCOL §2). Transparency becomes white. Runs on a background thread.
     */
    fun prepareImage(context: Context, uri: Uri, outDir: File): PrepareResult = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return PrepareResult.Unreadable
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return PrepareResult.Unreadable

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Limits.IMAGE_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return PrepareResult.Unreadable

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        var bitmap = orient(decoded, orientation)
        var edge = Limits.IMAGE_EDGE
        var bytes: ByteArray
        while (true) {
            val scaled = scaleToEdge(bitmap, edge)
            bytes = encodeUnder(scaled, Limits.IMAGE_BYTES)
            if (bytes.size <= Limits.IMAGE_BYTES || edge <= 640) {
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
                break
            }
            if (scaled !== bitmap) scaled.recycle()
            edge = (edge * 0.8).roundToInt()
        }

        outDir.mkdirs()
        val file = File(outDir, "${Ulid.generate()}.jpg")
        file.writeBytes(bytes)
        val attachment = PendingAttachment(
            localPath = file.absolutePath,
            kind = "image",
            contentType = "image/jpeg",
            size = bytes.size.toLong(),
            width = bitmap.width,
            height = bitmap.height,
            filename = (displayName(context, uri)?.substringBeforeLast('.') ?: "photo") + ".jpg",
        )
        bitmap.recycle()
        PrepareResult.Ok(attachment)
    } catch (_: OutOfMemoryError) {
        PrepareResult.Unreadable
    } catch (_: Exception) {
        PrepareResult.Unreadable
    }

    /** Copies a PDF, TXT, ZIP or LOG file (≤ 10 MiB) into the outbox. */
    fun prepareFile(context: Context, uri: Uri, outDir: File): PrepareResult = try {
        val name = displayName(context, uri) ?: "file"
        val contentType = Limits.fileContentType(name) ?: return PrepareResult.UnsupportedType
        val declaredSize = size(context, uri)
        if (declaredSize != null && declaredSize > Limits.FILE_BYTES) return PrepareResult.TooLarge
        outDir.mkdirs()
        val file = File(outDir, "${Ulid.generate()}.${name.substringAfterLast('.', "bin")}")
        var copied = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    copied += n
                    if (copied > Limits.FILE_BYTES) break
                    output.write(buffer, 0, n)
                }
            }
        } ?: return PrepareResult.Unreadable
        if (copied > Limits.FILE_BYTES) {
            file.delete()
            return PrepareResult.TooLarge
        }
        PrepareResult.Ok(PendingAttachment(file.absolutePath, "file", contentType, copied, filename = name))
    } catch (_: Exception) {
        PrepareResult.Unreadable
    }

    private fun orient(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun scaleToEdge(src: Bitmap, edge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        val needsWhite = src.hasAlpha()
        if (longEdge <= edge && !needsWhite) return src
        val ratio = if (longEdge > edge) edge.toFloat() / longEdge else 1f
        val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    private fun encodeUnder(bitmap: Bitmap, maxBytes: Int): ByteArray {
        var quality = 86
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes || quality <= 50) return out.toByteArray()
            quality -= 8
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun size(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }.getOrNull()
}
