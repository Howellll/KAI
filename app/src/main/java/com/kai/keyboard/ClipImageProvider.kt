package com.kai.keyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/**
 * Отдаёт картинку из истории буфера приложению, в которое её вставляют. Не экспортирован:
 * прочитать может только то приложение, которому клавиатура выдала разрешение на эту ссылку
 * при вставке. Картинка идёт из памяти по трубе — на диск ничего не пишется; через час
 * (когда запись пропадает из истории) ссылка перестаёт работать.
 */
class ClipImageProvider : ContentProvider() {
    override fun onCreate() = true

    private fun image(uri: Uri) = uri.lastPathSegment?.toLongOrNull()?.let { Brain.clips.image(it) }

    /**
     * Тип — только тем, у кого есть право прочитать ссылку. Иначе любое приложение
     * могло бы перебором узнавать, когда в буфере появляются картинки.
     */
    override fun getType(uri: Uri): String? {
        val ctx = context ?: return null
        val pid = android.os.Binder.getCallingPid()
        val uid = android.os.Binder.getCallingUid()
        if (uid != android.os.Process.myUid() &&
            ctx.checkUriPermission(uri, pid, uid, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return image(uri)?.mime
    }

    override fun getTypeAnonymous(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val img = image(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        // Android 11+: «файл» в памяти (memfd) — без потоков-писателей, его можно перематывать,
        // и зависший читатель никого не держит. На диск по-прежнему ничего не пишется.
        if (android.os.Build.VERSION.SDK_INT >= 30) memFile(img.bytes)?.let { return it }
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        // Пишем в трубу немногими потоками: читатель, который не читает, не наплодит их без счёта.
        writers.execute {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(img.bytes) }
            }.onFailure { runCatching { write.closeWithError(it.message) } }
        }
        return read
    }

    @android.annotation.TargetApi(30)
    private fun memFile(bytes: ByteArray): ParcelFileDescriptor? = runCatching {
        val fd = android.system.Os.memfd_create("klava-clip", 0)
        try {
            var off = 0
            while (off < bytes.size) off += android.system.Os.write(fd, bytes, off, bytes.size - off)
            android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
            ParcelFileDescriptor.dup(fd)
        } finally {
            android.system.Os.close(fd)
        }
    }.getOrNull()

    /** Имя и размер — некоторые приложения спрашивают их до чтения. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val img = image(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val ext = img.mime.substringAfter('/', "png").substringBefore('+')
        return MatrixCursor(cols).apply {
            addRow(cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "image.$ext"
                    OpenableColumns.SIZE -> img.bytes.size.toLong()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private val writers = java.util.concurrent.Executors.newFixedThreadPool(2)
        const val AUTHORITY = "com.kai.keyboard.clips"
        fun uri(id: Long): Uri = Uri.parse("content://$AUTHORITY/image/$id")
    }
}
