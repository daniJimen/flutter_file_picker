package com.mr.flutter.plugin.filepicker

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.mr.flutter.plugin.filepicker.FilePickerDelegate.Companion.REQUEST_CODE
import com.mr.flutter.plugin.filepicker.FilePickerDelegate.Companion.SAVE_FILE_CODE
import com.mr.flutter.plugin.filepicker.FilePickerDelegate.Companion.finishWithAlreadyActiveError
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.tika.Tika
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.plus

object FileUtils {
    private const val TAG = "FilePickerUtils"
    private const val PRIMARY_VOLUME_NAME = "primary"




    fun FilePickerDelegate.processFiles(activity: Activity, data: Intent?, compressionQuality: Int, loadDataToMemory: Boolean, type: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (data == null) {
                finishWithError("unknown_activity", "Unknown activity error, please fill an issue.")
                return@launch
            }

            val files = mutableListOf<FileInfo>()

            when {
                data.clipData != null -> {
                    for (i in 0 until data.clipData!!.itemCount) {
                        var uri = data.clipData!!.getItemAt(i).uri
                        uri = processUri(activity, uri, compressionQuality)
                        addFile(activity, uri, loadDataToMemory, files)
                    }
                    finishWithSuccess(files)
                }

                data.data != null -> {
                    var uri = data.data!!
                    uri = processUri(activity, uri, compressionQuality)

                    if (type == "dir") {
                        uri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                        val dirPath = getFullPathFromTreeUri(uri, activity)
                        if (dirPath != null) {
                            finishWithSuccess(dirPath)
                        } else {
                            finishWithError("unknown_path", "Failed to retrieve directory path.")
                        }
                    } else {
                        io.flutter.Log.d(
                            FilePickerDelegate.TAG,
                            "File added"
                        )
                        addFile(activity, uri, loadDataToMemory, files)
                        handleFileResult(files)
                    }
                }

                data.extras?.containsKey("selectedItems") == true -> {
                    val fileUris = getSelectedItems(data.extras!!)
                    fileUris?.filterIsInstance<Uri>()?.forEach { uri ->
                        addFile(activity, uri, loadDataToMemory, files)
                    }
                    finishWithSuccess(files)
                }

                else -> finishWithError("unknown_activity", "Unknown activity error, please fill an issue.")
            }
        }
    }

    fun forceRenameWithCopy(
        context: Context,
        uri: Uri,
        newNameWithExtension: String,
        bytes: ByteArray?
    ): Uri? {
        val mimeType = context.contentResolver.getType(uri) ?: "image/*" // por defecto

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newNameWithExtension)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }

        val newUri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                context.contentResolver.insert(MediaStore.Files.getContentUri("external"),values)
            }

        if (newUri != null) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                context.contentResolver.openOutputStream(newUri)?.use { output ->
                    bytes?.let {
                        output.write(it)
                        output.flush()
                    }
                }
            }
        }

        return newUri
    }

    fun FilePickerDelegate.handleFileResult(files: List<FileInfo>) {
        if (files.isNotEmpty()) {
            io.flutter.Log.d(FilePickerDelegate.TAG, "File path: $files")
            finishWithSuccess(files)
        } else {
            finishWithError("unknown_path", "Failed to retrieve path.")
        }
    }



    fun FilePickerDelegate.startFileExplorer() {
        val intent: Intent

        // Temporary fix, remove this null-check after Flutter Engine 1.14 has landed on stable
        if (type == null) {
            return
        }

        if (type == "dir") {
            intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        } else {
            if (type == "image/*") {
                intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            } else {
                intent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
            }
            val uri = (Environment.getExternalStorageDirectory().path + File.separator).toUri()
            io.flutter.Log.d(FilePickerDelegate.TAG, "Selected type $type")
            intent.setDataAndType(uri, this.type)
            intent.type = this.type
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, this.isMultipleSelection)
            intent.putExtra("multi-pick", this.isMultipleSelection)

            type?.takeIf { it.contains(",") }
                ?.split(",")
                ?.filter { it.isNotEmpty() }
                ?.let { allowedExtensions = ArrayList(it) }

            if (allowedExtensions != null) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, allowedExtensions)
            }
        }

        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivityForResult(intent, REQUEST_CODE)
        } else {
            io.flutter.Log.e(
                FilePickerDelegate.TAG,
                "Can't find a valid activity to handle the request. Make sure you've a file explorer installed."
            )
            finishWithError("invalid_format_type", "Can't handle the provided file type.")
        }
    }

    fun FilePickerDelegate?.startFileExplorer(
        type: String?,
        isMultipleSelection: Boolean?,
        withData: Boolean?,
        allowedExtensions: ArrayList<String?>?,
        compressionQuality: Int? = 0,
        result: MethodChannel.Result
    ) {
        if (this?.setPendingMethodCallAndResult(result) == false) {
            finishWithAlreadyActiveError(result)
            return
        }
        this?.type = type
        if (isMultipleSelection != null) {
            this?.isMultipleSelection = isMultipleSelection
        }
        if (withData != null) {
            this?.loadDataToMemory = withData
        }
        this?.allowedExtensions = allowedExtensions
        if (compressionQuality != null) {
            this?.compressionQuality = compressionQuality
        }

        this?.startFileExplorer()
    }

    fun getFileExtension(bytes: ByteArray?):String{
        val tika = Tika()
        val mimeType = tika.detect(bytes)
        return mimeType.substringAfter("/")
    }


    fun getMimeTypeForBytes(bytes: ByteArray?):String{
        val tika = Tika()
        val mimeType = tika.detect(bytes)
        return mimeType
    }

    fun FilePickerDelegate.saveFile(
        fileName: String?,
        type: String?,
        initialDirectory: String?,
        allowedExtensions: ArrayList<String?>?,
        bytes: ByteArray?,
        result: MethodChannel.Result
    ) {
        if (!this.setPendingMethodCallAndResult(result)) {
            finishWithAlreadyActiveError(result)
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        if (!fileName.isNullOrEmpty()) {
            intent.putExtra(Intent.EXTRA_TITLE, fileName)
        }
        this.bytes = bytes
        if ("dir" != type) {
            intent.type = getMimeTypeForBytes(bytes)
        }
        if (!initialDirectory.isNullOrEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialDirectory.toUri())
            }
        }
        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivityForResult(intent, SAVE_FILE_CODE)
        } else {
            io.flutter.Log.e(
                FilePickerDelegate.TAG,
                "Can't find a valid activity to handle the request. Make sure you've a file explorer installed."
            )
            finishWithError("invalid_format_type", "Can't handle the provided file type.")
        }
    }

    fun processUri(activity: Activity, uri: Uri, compressionQuality: Int): Uri {
        return if (compressionQuality > 0 && isImage(activity.applicationContext, uri)) {
            compressImage(uri, compressionQuality, activity.applicationContext)
        } else {
            uri
        }
    }

    fun addFile(activity: Activity, uri: Uri, loadDataToMemory: Boolean, files: MutableList<FileInfo>) {
        openFileStream(activity, uri, loadDataToMemory)?.let { file ->
            files.add(file)
            io.flutter.Log.d(FilePickerDelegate.TAG, "[FilePick] URI: ${uri.path}")
        }
    }
    @Suppress("deprecation")
    fun getSelectedItems(bundle: Bundle): ArrayList<Parcelable>? {
        if (Build.VERSION.SDK_INT >= 33) {
            return bundle.getParcelableArrayList("selectedItems", Parcelable::class.java)
        }

        return bundle.getParcelableArrayList("selectedItems")
    }


    fun getMimeTypes(allowedExtensions: ArrayList<String>?): ArrayList<String?>? {
        if (allowedExtensions.isNullOrEmpty()) {
            return null
        }

        val mimes = ArrayList<String?>()

        for (i in allowedExtensions.indices) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                allowedExtensions[i]
            )
            if (mime == null) {
                Log.w(
                    TAG,
                    "Custom file type " + allowedExtensions[i] + " is unsupported and will be ignored."
                )
                continue
            }

            mimes.add(mime)
        }
        io.flutter.Log.d(
            TAG,
            "Allowed file extensions mimes: $mimes"
        )
        return mimes
    }

    @JvmStatic
    fun getFileName(uri: Uri, context: Context): String? {
        var result: String? = null

        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                ).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        result =
                            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    }
                }
            }
            if (result == null) {
                result = uri.path?.substringAfterLast('/')
            }
        } catch (ex: Exception) {
            io.flutter.Log.e(
                TAG,
                "Failed to handle file name: $ex"
            )
        }

        return result
    }

    @JvmStatic
    fun isImage(context: Context, uri: Uri): Boolean {
        val extension = getFileExtension(context, uri)
        return (extension != null && (extension.contentEquals("jpg") || extension.contentEquals("jpeg") || extension.contentEquals(
            "png"
        ) || extension.contentEquals("WEBP")))
    }

    private fun getFileExtension(context: Context, uri: Uri): String? {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri)
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    }

    private fun getCompressFormat(context: Context, uri: Uri): Bitmap.CompressFormat {
        val format = getFileExtension(context, uri)
        return when (format!!.uppercase(Locale.getDefault())) {
            "PNG" -> Bitmap.CompressFormat.PNG
            "WEBP" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
    }

    @JvmStatic
    fun compressImage(originalImageUri: Uri, compressionQuality: Int, context: Context): Uri {
        val compressedUri: Uri
        try {
            context.contentResolver.openInputStream(originalImageUri).use { imageStream ->
                val compressedFile = createImageFile(context, originalImageUri)
                val originalBitmap = BitmapFactory.decodeStream(imageStream)
                // Compress and save the image
                val fos = FileOutputStream(compressedFile)
                originalBitmap.compress(
                    getCompressFormat(context, originalImageUri),
                    compressionQuality,
                    fos
                )
                fos.flush()
                fos.close()
                compressedUri = Uri.fromFile(compressedFile)
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        return compressedUri
    }

    @Throws(IOException::class)
    private fun createImageFile(context: Context, uri: Uri): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "IMAGE_" + timeStamp + "_"
        val storageDir = context.cacheDir
        return File.createTempFile(imageFileName, "." + getFileExtension(context, uri), storageDir)
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    @JvmStatic
    fun clearCache(context: Context): Boolean {
        try {
            val cacheDir = File(context.cacheDir.toString() + "/file_picker/")
            recursiveDeleteFile(cacheDir)
        } catch (ex: Exception) {
            io.flutter.Log.e(
                TAG,
                "There was an error while clearing cached files: $ex"
            )
            return false
        }
        return true
    }

    fun loadData(file: File, fileInfo: FileInfo.Builder) {
        try {
            val size = file.length().toInt()
            val bytes = ByteArray(size)

            try {
                val buf = BufferedInputStream(FileInputStream(file))
                buf.read(bytes, 0, bytes.size)
                buf.close()
            } catch (e: FileNotFoundException) {
                io.flutter.Log.e(FilePickerDelegate.TAG, "File not found: " + e.message)
            } catch (e: IOException) {
                io.flutter.Log.e(
                    FilePickerDelegate.TAG,
                    "Failed to close file streams: " + e.message
                )
            }
            fileInfo.withData(bytes)
        } catch (e: Exception) {
            io.flutter.Log.e(
                TAG,
                "Failed to load bytes into memory with error $e. Probably the file is too big to fit device memory. Bytes won't be added to the file this time."
            )
        }
    }

    @JvmStatic
    fun openFileStream(context: Context, uri: Uri, withData: Boolean): FileInfo? {
        io.flutter.Log.i(FilePickerDelegate.TAG, "Caching from URI: $uri")
        var `in`: InputStream? = null
        var fos: FileOutputStream? = null
        val fileInfo = FileInfo.Builder()
        val fileName = getFileName(uri, context)
        val path =
            context.cacheDir.absolutePath + "/file_picker/" + System.currentTimeMillis() + "/" + (fileName ?: "unamed")

        val file = File(path)

        if (!file.exists()) {
            try {
                file.parentFile?.mkdirs()

                `in` = context.contentResolver.openInputStream(uri)
                fos = FileOutputStream(file)

                val out = BufferedOutputStream(fos)
                val buffer = ByteArray(8192)
                var len: Int

                while ((`in`!!.read(buffer).also { len = it }) >= 0) {
                    out.write(buffer, 0, len)
                }
                out.flush()
            } catch (e: Exception) {
                io.flutter.Log.e(
                    FilePickerDelegate.TAG,
                    "Failed to retrieve and cache file: " + e.message,
                    e
                )
                return null
            } finally {
                try {
                    fos?.fd?.sync()
                    fos?.close()
                    `in`?.close()
                } catch (ex: IOException) {
                    io.flutter.Log.e(
                        FilePickerDelegate.TAG,
                        "Failed to close file streams: " + ex.message,
                        ex
                    )
                }
            }
        }

        io.flutter.Log.d(FilePickerDelegate.TAG, "File loaded and cached at: $path")

        if (withData) {
            loadData(file, fileInfo)
        }

        fileInfo
            .withPath(path)
            .withName(fileName)
            .withUri(uri)
            .withSize(file.length())

        return fileInfo.build()
    }

    @JvmStatic
    fun getFullPathFromTreeUri(treeUri: Uri?, con: Context): String? {
        if (treeUri == null) {
            return null
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (isDownloadsDocument(treeUri)) {
                val docId = DocumentsContract.getDocumentId(treeUri)
                val extPath =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                if (docId == "downloads") {
                    return extPath
                } else if (docId.matches("^ms[df]\\:.*".toRegex())) {
                    val fileName = getFileName(treeUri, con)
                    return "$extPath/$fileName"
                } else if (docId.startsWith("raw:")) {
                    return docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()[1]
                }
                return null
            }
        }

        var volumePath = getVolumePath(
            getVolumeIdFromTreeUri(
                treeUri
            ), con
        )
            ?: return File.separator

        if (volumePath.endsWith(File.separator)) volumePath =
            volumePath.substring(0, volumePath.length - 1)

        var documentPath = getDocumentPathFromTreeUri(treeUri)

        if (documentPath.endsWith(File.separator)) documentPath =
            documentPath.substring(0, documentPath.length - 1)

        return if (!documentPath.isEmpty()) {
            if (documentPath.startsWith(File.separator)) {
                volumePath + documentPath
            } else {
                volumePath + File.separator + documentPath
            }
        } else {
            volumePath
        }
    }

    private fun getDirectoryPath(
        storageVolumeClazz: Class<*>,
        storageVolumeElement: Any?
    ): String? {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val getPath = storageVolumeClazz.getMethod("getPath")
                return getPath.invoke(storageVolumeElement) as String
            }

            val getDirectory = storageVolumeClazz.getMethod("getDirectory")
            val f = getDirectory.invoke(storageVolumeElement) as File
            return f.path
        } catch (_: Exception) {
            return null
        }
    }

    private fun getVolumePath(volumeId: String?, context: Context): String? {
        try {
            val mStorageManager =
                context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val storageVolumeClazz = Class.forName("android.os.storage.StorageVolume")
            val getVolumeList = mStorageManager.javaClass.getMethod("getVolumeList")
            val getUuid = storageVolumeClazz.getMethod("getUuid")
            val isPrimary = storageVolumeClazz.getMethod("isPrimary")
            val result = getVolumeList.invoke(mStorageManager) ?: return null

            val length = java.lang.reflect.Array.getLength(result)
            for (i in 0 until length) {
                val storageVolumeElement = java.lang.reflect.Array.get(result, i)
                val uuid = getUuid.invoke(storageVolumeElement) as? String
                val primary = isPrimary.invoke(storageVolumeElement) as? Boolean

                // primary volume?
                if (primary != null && PRIMARY_VOLUME_NAME == volumeId) {
                    return getDirectoryPath(storageVolumeClazz, storageVolumeElement)
                }

                // other volumes?
                if (uuid != null && uuid == volumeId) {
                    return getDirectoryPath(storageVolumeClazz, storageVolumeElement)
                }
            }
            // not found.
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private fun getVolumeIdFromTreeUri(treeUri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return if (split.size > 0) split[0]
        else null
    }


    private fun getDocumentPathFromTreeUri(treeUri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return if ((split.size >= 2)) split[1]
        else File.separator
    }

    private fun recursiveDeleteFile(file: File?) {
        if (file == null || !file.exists()) {
            return
        }

        if (file.listFiles() != null && file.isDirectory) {
            for (child in file.listFiles().orEmpty()) {
                recursiveDeleteFile(child)
            }
        }

        file.delete()
    }
}