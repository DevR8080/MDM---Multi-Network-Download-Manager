package com.strategy.booster.view

import android.webkit.MimeTypeMap
import androidx.annotation.DrawableRes
import com.strategy.booster.R

object FileIcons {

    private val map: Map<String, Int> = mapOf(
        "zip" to R.drawable.archive,
        "rar" to R.drawable.archive,
        "7z"  to R.drawable.archive,
        "tar" to R.drawable.archive,
        "gz"  to R.drawable.archive,

        "apk" to R.drawable.file,
        "exe" to R.drawable.file,
        "dmg" to R.drawable.file,
        "iso" to R.drawable.file,

        "mp4" to R.drawable.video,
        "mkv" to R.drawable.video,
        "mov" to R.drawable.video,
        "avi" to R.drawable.video,
        "webm" to R.drawable.video,

        "mp3" to R.drawable.music,
        "wav" to R.drawable.music,
        "flac" to R.drawable.music,
        "aac" to R.drawable.music,
        "ogg" to R.drawable.music,

        "jpg" to R.drawable.image,
        "jpeg" to R.drawable.image,
        "png" to R.drawable.image,
        "gif" to R.drawable.image,
        "webp" to R.drawable.image,
        "svg" to R.drawable.image,

        "pdf" to R.drawable.docs,
        "txt" to R.drawable.docs,
        "csv" to R.drawable.docs,
        "json" to R.drawable.docs,
        "xml" to R.drawable.docs,
        "html" to R.drawable.docs,

        "doc" to R.drawable.docs,
        "docx" to R.drawable.docs,
        "xls" to R.drawable.docs,
        "xlsx" to R.drawable.docs,
        "ppt" to R.drawable.docs,
        "pptx" to R.drawable.docs
    )

    @DrawableRes
    fun iconFor(filename: String, mimeHint: String? = null): Int {
        val ext = filename.substringAfterLast('.', "").lowercase()
        map[ext]?.let { return it }
        when {
            mimeHint?.startsWith("image/") == true -> return R.drawable.image
            mimeHint?.startsWith("video/") == true -> return R.drawable.video
            mimeHint?.startsWith("audio/") == true -> return R.drawable.music
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        when {
            mime?.startsWith("image/") == true -> return R.drawable.image
            mime?.startsWith("video/") == true -> return R.drawable.video
            mime?.startsWith("audio/") == true -> return R.drawable.music
            mime == "application/pdf" -> return R.drawable.docs
        }
        return R.drawable.file
    }
}
