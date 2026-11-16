package com.aibuilt.claudeclone.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Reads only small, explicitly bounded payloads. ZIP entries are streamed and are never
 * written to disk. Limits also protect against unexpectedly large or highly-compressed files.
 */
class AttachmentProcessor(private val context: Context) {
    fun read(uri: Uri): PendingAttachment {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val metadata = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (!cursor.moveToFirst()) null else {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        }
        val name = metadata?.first?.takeIf(String::isNotBlank) ?: "attachment"
        val size = metadata?.second
        val lowerName = name.lowercase(Locale.ROOT)
        val base = PendingAttachment(
            name = name,
            mimeType = mime,
            uri = uri.toString(),
            sizeBytes = size
        )

        return when {
            lowerName.endsWith(".zip") || mime.equals("application/zip", true) ->
                inspectZip(uri, base)
            mime.startsWith("image/") ->
                readSmallImage(uri, base)
            isReadableText(name, mime) ->
                readSmallText(uri, base)
            else -> base
        }
    }

    private fun inspectZip(uri: Uri, base: PendingAttachment): PendingAttachment {
        val manifest = StringBuilder()
        val extracted = StringBuilder()
        var entries = 0
        var totalExtracted = 0
        val manifestLimit = 1_000
        val perEntryLimit = 48 * 1024
        val totalTextLimit = 256 * 1024

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The selected archive could not be opened. It may be unavailable from its provider.")
        ZipInputStream(input.buffered()).use { zip ->
            while (entries < manifestLimit) {
                val entry = zip.nextEntry ?: break
                entries++
                val entryName = entry.name.take(1_000)
                val entrySize = if (entry.size >= 0) entry.size.toString() else "unknown"
                manifest.append(if (entry.isDirectory) "Directory: " else "File: ")
                    .append(entryName)
                    .append(" (")
                    .append(entrySize)
                    .append(" bytes)\n")

                if (!entry.isDirectory && isReadableTextName(entryName)) {
                    val remaining = totalTextLimit - totalExtracted
                    if (remaining > 0) {
                        val content = readBounded(zip, minOf(perEntryLimit, remaining))
                        if (content.isNotBlank()) {
                            extracted.append("\n--- ")
                                .append(entryName)
                                .append(" ---\n")
                                .append(content)
                                .append('\n')
                            totalExtracted += content.toByteArray(Charsets.UTF_8).size
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        if (entries == manifestLimit) manifest.append("Manifest truncated after $manifestLimit entries.\n")
        return base.copy(
            extractedText = extracted.toString(),
            archiveManifest = manifest.toString()
        )
    }

    private fun readSmallText(uri: Uri, base: PendingAttachment): PendingAttachment {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The selected file could not be opened. It may be unavailable from its provider.")
        val text = input.use { readBounded(it, MAX_TEXT_BYTES) }
        val suffix = if (base.sizeBytes == null || base.sizeBytes > MAX_TEXT_BYTES) {
            "\n[Text extraction limited to ${MAX_TEXT_BYTES / 1024} KB.]"
        } else {
            ""
        }
        return base.copy(extractedText = text + suffix)
    }

    private fun readSmallImage(uri: Uri, base: PendingAttachment): PendingAttachment {
        if (base.sizeBytes != null && base.sizeBytes > MAX_IMAGE_BYTES) return base
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The selected image could not be opened. It may be unavailable from its provider.")
        val bytes = input.use { readBytesBounded(it, MAX_IMAGE_BYTES) } ?: return base
        return base.copy(base64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun readBounded(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun readBytesBounded(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (total + read > limit) return null
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun isReadableText(name: String, mime: String): Boolean {
        return mime.startsWith("text/") ||
            mime.contains("json", true) ||
            mime.contains("xml", true) ||
            mime.contains("javascript", true) ||
            mime.contains("yaml", true) ||
            isReadableTextName(name)
    }

    private fun isReadableTextName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in setOf(
            "txt", "md", "csv", "json", "xml", "html", "htm", "css", "js", "ts",
            "kt", "java", "py", "c", "h", "cpp", "hpp", "rs", "go", "swift",
            "sh", "yaml", "yml", "toml", "ini", "properties", " gradle", "sql"
        )
    }

    private companion object {
        const val MAX_TEXT_BYTES = 512 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
