package com.strategy.booster.comps

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import androidx.core.net.toUri

object MediaStoreDownloads {

    fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    private fun downloadsCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Files.getContentUri("external")

    private fun nameExists(resolver: ContentResolver, displayName: String): Boolean {
        val coll = downloadsCollection()
        resolver.query(
            coll,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(displayName),
            null
        )?.use { c -> return c.moveToFirst() }
        return false
    }

    fun uniqueDisplayName(resolver: ContentResolver, baseName: String): String {
        if (!nameExists(resolver, baseName)) return baseName
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "${stem}_${i}${ext}"
            if (!nameExists(resolver, candidate)) return candidate
            i++
        }
    }

    fun createOrGetDownloadUri(context: Context, displayName: String, mime: String? = null): Uri {
        val resolver = context.contentResolver
        val coll = downloadsCollection()

        resolver.query(
            coll,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(displayName),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(coll, id.toString())
            }
        }

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime ?: guessMimeFromName(displayName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return resolver.insert(coll, cv)
            ?: throw IllegalStateException("Failed to insert into MediaStore Downloads")
    }

    fun finishPending(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, cv, null, null)
        }
    }

    fun createTargetInSettingsDirOrDownloads(
        context: Context,
        preferredDirTreeUri: String?,
        fileNameFromUrl: String
    ): Pair<String, Uri> {
        val resolver = context.contentResolver

        val rawName = try {
            URLDecoder.decode(fileNameFromUrl, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            fileNameFromUrl
        }.ifBlank { "download.bin" }

        if (!preferredDirTreeUri.isNullOrBlank()) {
            val tree = preferredDirTreeUri.toUri()
            runCatching {
                val parentDocId = DocumentsContract.getTreeDocumentId(tree)
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)

                val unique = ensureUniqueInTree(resolver, tree, parentDocId, rawName)
                val mime = guessMimeFromName(unique)

                val created: Uri? = DocumentsContract.createDocument(resolver, parentDocUri, mime, unique)
                if (created != null) {
                    return unique to created
                }
            }.onFailure {
                // MediaStore
            }
        }

        // Fallback: MediaStore Downloads
        val base = rawName
        val unique = uniqueDisplayName(resolver, base)
        val mime = guessMimeFromName(unique)
        val uri = createOrGetDownloadUri(context, unique, mime)
        return unique to uri
    }

    private fun ensureUniqueInTree(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        desiredName: String
    ): String {
        val existing = queryChildNames(resolver, treeUri, parentDocId)
        if (!existing.contains(desiredName)) return desiredName

        val (stem, ext) = splitName(desiredName)
        var idx = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "${stem}_$idx" else "${stem}_$idx.$ext"
            if (!existing.contains(candidate)) return candidate
            idx++
        }
    }

    private fun queryChildNames(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String
    ): Set<String> {
        val out = mutableSetOf<String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        resolver.query(childrenUri, proj, null, null, null)?.use { c ->
            val idx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                out += c.getString(idx) ?: continue
            }
        }
        return out
    }

    private fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) {
            name to ""
        } else {
            name.substring(0, dot) to name.substring(dot + 1)
        }
    }
}
