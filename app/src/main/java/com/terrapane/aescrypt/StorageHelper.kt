/*
 *  StorageHelper.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This module contains code to help formulate correct filenames for
 *      output files, get the path to files so that output can be in the
 *      same location, etc.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import android.os.Build
import android.provider.OpenableColumns
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import java.io.File
import java.io.IOException

class FileAlreadyExistsException(message: String) : Exception(message)

object StorageHelper {

    fun getOutputFileName(originalName: String, isEncrypting: Boolean): String {
        return if (isEncrypting) {
            "$originalName.aes"
        } else {
            if (originalName.endsWith(".aes", ignoreCase = true)) {
                val baseName = originalName.substring(0, originalName.length - 4)
                baseName.ifBlank { "aes.decrypted" }
            } else {
                "$originalName.decrypted"
            }
        }
    }

    fun getRealPathFromUri(context: Context, uri: Uri): String? {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val realPath = Os.readlink(fdPath)

            if (realPath.isNullOrEmpty() || realPath.startsWith("/proc/") || realPath.startsWith("/fd/")) {
                return null
            }
            return realPath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun isExternalStorageManagerFallback(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun findPhysicalParentDir(context: Context, sourceUri: Uri): File? {
        var displayName: String? = null
        var fileSize: Long = -1

        context.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) displayName = cursor.getString(nameIndex)
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        if (displayName.isNullOrBlank()) return null

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        for (volume in storageManager.storageVolumes) {
            // Reflection fallback strategy to extract absolute path without crashing API 29 compilers
            var rootPath: String? = null
            try {
                val getDirectoryMethod = volume.javaClass.getMethod("getDirectory")
                val fileObj = getDirectoryMethod.invoke(volume) as? File
                rootPath = fileObj?.absolutePath
            } catch (_: Exception) {
                // Fall back to primary framework environment string if reflection fails (e.g. on pure API 29 devices)
                if (volume.isPrimary) {
                    rootPath = Environment.getExternalStorageDirectory().absolutePath
                }
            }

            if (rootPath.isNullOrBlank()) continue

            // Convert restrictive kernel mount points to public user-space canonical paths
            if (rootPath.contains("/mnt/user/0/emulated/")) {
                rootPath = rootPath.replace("/mnt/user/0/emulated/", "/storage/emulated/")
            } else if (rootPath.startsWith("/mnt/media_rw/")) {
                rootPath = rootPath.replace("/mnt/media_rw/", "/storage/")
            }

            val baseDir = File(rootPath)
            val cleanRoot = try {
                baseDir.canonicalPath
            } catch (_: Exception) {
                rootPath
            }

            val pathsToSearch = arrayOf(
                File(cleanRoot, "Documents"),
                File(cleanRoot, "Download"),
                File(cleanRoot, "DCIM"),
                File(cleanRoot, "Pictures")
            )

            for (dir in pathsToSearch) {
                if (!dir.exists() || !dir.isDirectory) continue

                val matchedFile = dir.walkTopDown().maxDepth(5).firstOrNull { file ->
                    file.name == displayName && (fileSize == -1L || file.length() == fileSize)
                }
                if (matchedFile != null) {
                    return matchedFile.parentFile
                }
            }
        }
        return null
    }

    fun findExistingCollision(
        context: Context, fileUris: List<Uri>, isEncrypting: Boolean
    ): String? {
        for (sourceUri in fileUris) {
            var originalName = "file"
            context.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    originalName = cursor.getString(nameIndex)
                }
            }
            val targetName = getOutputFileName(originalName, isEncrypting)

            if (isExternalStorageManagerFallback()) {
                // First try kernel links, then fall back to the robust physical storage search engine
                val directPath = getRealPathFromUri(context, sourceUri)
                val parentDir = if (!directPath.isNullOrEmpty()) {
                    File(directPath).parentFile
                } else {
                    findPhysicalParentDir(context, sourceUri)
                }

                if (parentDir != null) {
                    val targetFile = File(parentDir, targetName)
                    if (targetFile.exists()) {
                        return targetName
                    }
                }
            }
        }
        return null
    }

    private fun resolveRelativePathAndVolume(
        context: Context, sourceUri: Uri
    ): Pair<String, String> {
        var detectedPath = "Download/"
        var detectedVolume = "external_primary"

        // Direct Text-Parsing of the Document ID String (preferred with
        // physical devices using MANAGE_EXTERNAL_STORAGE)
        if (DocumentsContract.isDocumentUri(context, sourceUri)) {
            try {
                val documentId = DocumentsContract.getDocumentId(sourceUri)
                if (!documentId.isNullOrEmpty() && documentId.contains(":")) {
                    val volumeId = documentId.substringBefore(":")
                    val pathSegment = documentId.substringAfter(":")

                    detectedVolume = if (volumeId.equals("primary", ignoreCase = true)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL_PRIMARY else "external_primary"
                    } else {
                        volumeId.lowercase()
                    }

                    // If the path segment contains the file name, strip it to get the raw parent directory
                    if (pathSegment.contains("/")) {
                        val folderPart = pathSegment.substringBeforeLast("/")
                        if (folderPart.isNotBlank()) {
                            detectedPath = folderPart
                        }
                    } else {
                        // It's in the root folder of that storage volume
                        detectedPath = ""
                    }

                    // Format with a clean trailing slash if a path was found
                    if (detectedPath.isNotBlank() && !detectedPath.endsWith("/")) {
                        detectedPath = "$detectedPath/"
                    }
                    return Pair(detectedPath, detectedVolume)
                }
            } catch (_: Exception) {
            }
        }

        // Emulator Fallback: Use the low-level proc link extraction if document tracking fails
        val realPath = getRealPathFromUri(context, sourceUri)
        if (!realPath.isNullOrEmpty()) {
            val file = File(realPath)
            val parentPath = file.parent
            if (!parentPath.isNullOrEmpty()) {
                val primaryStoragePath = Environment.getExternalStorageDirectory().absolutePath

                when {
                    parentPath.startsWith(primaryStoragePath) -> {
                        detectedPath =
                            parentPath.substring(primaryStoragePath.length).trimStart('/')
                        detectedVolume =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL_PRIMARY else "external_primary"
                    }

                    parentPath.startsWith("/storage/", ignoreCase = true) -> {
                        val segments = parentPath.split("/").filter { it.isNotBlank() }
                        if (segments.size >= 2) {
                            val volumeId = segments[1]
                            val volumePrefix = "/storage/$volumeId/"
                            detectedPath = parentPath.substringAfter(volumePrefix)
                            detectedVolume = volumeId.lowercase()
                        }
                    }
                }

                if (detectedPath.isNotBlank() && detectedPath != parentPath) {
                    if (!detectedPath.endsWith("/")) detectedPath = "$detectedPath/"
                    return Pair(detectedPath, detectedVolume)
                }
            }
        }

        if (!detectedPath.endsWith("/")) {
            detectedPath = "$detectedPath/"
        }

        return Pair(detectedPath, detectedVolume)
    }

    fun createOutputFileNextToOriginal(
        context: Context,
        sourceUri: Uri,
        isEncrypting: Boolean
    ): Pair<ParcelFileDescriptor, Uri> {
        var originalName = "file"
        context.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                originalName = cursor.getString(nameIndex)
            }
        }
        val targetName = getOutputFileName(originalName, isEncrypting)

        if (isExternalStorageManagerFallback()) {
            val (originalRelativePath, volumeId) = resolveRelativePathAndVolume(context, sourceUri)

            val baseStoragePath =
                if (volumeId.equals("external_primary", ignoreCase = true) || volumeId.isBlank()) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    "/storage/$volumeId"
                }

            val cleanRelativePath = originalRelativePath.trimStart('/')
            val targetFile = File(baseStoragePath, "$cleanRelativePath$targetName")

            targetFile.parentFile?.mkdirs()

            if (targetFile.exists()) {
                throw FileAlreadyExistsException("The output file '$targetName' already exists")
            }

            targetFile.createNewFile()

            val pfd = ParcelFileDescriptor.open(
                targetFile,
                ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
            ) ?: throw IOException("Failed to open physical write handle for $targetName")

            // Wrap the raw File handle in FileProvider to output a legal content:// URI
            val contentUri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile
                )
            } catch (_: Exception) {
                // Fallback just in case the fileprovider manifest entry hasn't been defined yet
                Uri.fromFile(targetFile)
            }

            return Pair(pfd, contentUri)
        } else {
            // MediaStore Fallback Strategy for Standard Scoped Storage Sandbox Operations
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Extract relative path from original document or drop into central Download folder
                    val (originalRelativePath, _) = resolveRelativePathAndVolume(context, sourceUri)
                    val targetRelativeFolder = if (originalRelativePath.isNotBlank()) {
                        "Download/$originalRelativePath".trimEnd('/')
                    } else {
                        "Download"
                    }
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelativeFolder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1) // Keep hidden until fully processed
                }
            }

            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            // Check if file already exists in MediaStore database to prevent silent overwrite loops
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(targetName)
            context.contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.count > 0) {
                    throw FileAlreadyExistsException("The output file '$targetName' already exists in MediaStore.")
                }
            }

            val targetOutputUri = context.contentResolver.insert(collectionUri, contentValues)
                ?: throw IOException("Failed to register structural entry in MediaStore for $targetName")

            val pfd = context.contentResolver.openFileDescriptor(targetOutputUri, "rwt")
                ?: throw IOException("Failed to obtain sandboxed write channel descriptor for $targetName")

            return Pair(pfd, targetOutputUri)
        }
    }

    // Convert file URIs to uri/filename pairs
    fun resolveUriDetails(context: Context, uris: List<Uri>): Pair<List<Uri>, List<String>> {
        val fileNames = uris.map { uri ->
            var name = "Unknown File"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
            name
        }
        return Pair(uris, fileNames)
    }
}
