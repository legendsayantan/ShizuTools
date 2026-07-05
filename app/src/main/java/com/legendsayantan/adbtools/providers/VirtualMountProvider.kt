package com.legendsayantan.adbtools.providers

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.legendsayantan.adbtools.lib.ShizuToolsController
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch

class VirtualMountProvider : DocumentsProvider() {

    private val DEFAULT_ROOT_ID = "shizutools_virtual_mount"
    private val DEFAULT_DOCUMENT_ID = "/storage/emulated/0/Android/data"

    private val rootProjection = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    )

    private val documentProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    )

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: rootProjection)
        val row = result.newRow()
        row.add(Root.COLUMN_ROOT_ID, DEFAULT_ROOT_ID)
        row.add(Root.COLUMN_SUMMARY, "ShizuTools Virtual Mount")
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        val prefs = context?.getSharedPreferences("virtual_mount", android.content.Context.MODE_PRIVATE)
        val mountName = prefs?.getString("mount_name", "Unlocked Android Data") ?: "Unlocked Android Data"
        
        row.add(Root.COLUMN_TITLE, mountName)
        row.add(Root.COLUMN_DOCUMENT_ID, getMountAddress())
        row.add(Root.COLUMN_ICON, android.R.drawable.ic_menu_manage)
        return result
    }
    
    private fun getMountAddress(): String {
        val prefs = context?.getSharedPreferences("virtual_mount", android.content.Context.MODE_PRIVATE)
        return prefs?.getString("mount_path", DEFAULT_DOCUMENT_ID) ?: DEFAULT_DOCUMENT_ID
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: documentProjection)
        val file = File(documentId)
        val isDir = file.isDirectory
        val size = if (isDir) 0 else file.length()
        includeFile(result, documentId, file.name, size, file.lastModified(), isDir)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: documentProjection)

        val latch = CountDownLatch(1)
        var pfd: ParcelFileDescriptor? = null
        
        ShizuToolsController.execute { service ->
            pfd = service.listDirectory(parentDocumentId)
            latch.countDown()
        }
        
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

        pfd?.let { fd ->
            try {
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val parts = line!!.split("|")
                        if (parts.size >= 4) {
                            val name = parts[0].replace("%7C", "|").replace("%0A", "\n")
                            val size = parts[1].toLongOrNull() ?: 0L
                            val lastMod = parts[2].toLongOrNull() ?: 0L
                            val isDir = parts[3] == "true"
                            
                            val docId = if (parentDocumentId.endsWith("/")) {
                                parentDocumentId + name
                            } else {
                                "$parentDocumentId/$name"
                            }
                            includeFile(result, docId, name, size, lastMod, isDir)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val latch = CountDownLatch(1)
        var resultFd: ParcelFileDescriptor? = null
        
        ShizuToolsController.execute { service ->
            resultFd = service.openFile(documentId, mode)
            latch.countDown()
        }
        
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        
        return resultFd ?: throw java.io.FileNotFoundException("Failed to open $documentId")
    }

    override fun deleteDocument(documentId: String) {
        val latch = CountDownLatch(1)
        ShizuToolsController.execute { service ->
            service.deletePath(documentId)
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val latch = CountDownLatch(1)
        var newId: String? = null
        ShizuToolsController.execute { service ->
            newId = service.createDocument(parentDocumentId, mimeType, displayName)
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return newId ?: throw java.io.FileNotFoundException("Failed to create document")
    }
    
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return documentId.startsWith(parentDocumentId)
    }

    private fun includeFile(
        result: MatrixCursor,
        docId: String,
        name: String,
        size: Long,
        lastModified: Long,
        isDir: Boolean
    ) {
        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, docId)
        row.add(Document.COLUMN_DISPLAY_NAME, name)
        row.add(Document.COLUMN_SIZE, size)
        row.add(Document.COLUMN_LAST_MODIFIED, lastModified)

        var flags = Document.FLAG_SUPPORTS_DELETE
        if (isDir) {
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            row.add(Document.COLUMN_MIME_TYPE, getMimeType(name))
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        row.add(Document.COLUMN_FLAGS, flags)
    }

    private fun getMimeType(name: String): String {
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = name.substring(lastDot + 1).lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mime != null) return mime
        }
        return "application/octet-stream"
    }
}
