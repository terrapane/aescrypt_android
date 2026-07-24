/*
 *  AESCryptForegroundService.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      Foreground service to facilitate encryption and decryption of
 *      files selected by the user.  This will make call into native C++ code
 *      via JNI in order to perform the actual file encryption.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

class AESCryptForegroundService : Service() {

    // Custom internal exception class to cleanly handle cancellation routing
    private class OperationCancelledException(message: String) : Exception(message)

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val singleThreadExecutor = Executors.newSingleThreadExecutor()
    private var pollingJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "aescrypt_processing_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ERROR_NOTIFICATION_ID = 99

        private const val EXTRA_PASSWORD = "com.terrapane.aescrypt.extra.PASSWORD"
        private const val EXTRA_URIS = "com.terrapane.aescrypt.extra.URIS"
        private const val EXTRA_NAMES = "com.terrapane.aescrypt.extra.NAMES"
        private const val EXTRA_IS_ENCRYPTING = "com.terrapane.aescrypt.extra.IS_ENCRYPTING"

        private val _serviceProgress = MutableStateFlow<NativeProgress?>(null)
        val serviceProgress: StateFlow<NativeProgress?> = _serviceProgress.asStateFlow()

        private var currentProcessingFileName = ""

        fun clearErrorNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.cancel(ERROR_NOTIFICATION_ID)
        }

        fun requestCancel() {
            NativeBridge.cancelOperation()
        }

        fun resetServiceProgress() {
            _serviceProgress.value = null
        }

        fun start(
            context: Context,
            password: String,
            uris: ArrayList<Uri>,
            names: ArrayList<String>,
            isEncrypting: Boolean
        ) {
            val intent = Intent(context, AESCryptForegroundService::class.java).apply {
                putExtra(EXTRA_PASSWORD, password)
                putParcelableArrayListExtra(EXTRA_URIS, uris)
                putStringArrayListExtra(EXTRA_NAMES, names)
                putExtra(EXTRA_IS_ENCRYPTING, isEncrypting)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val isEncrypting = intent.getBooleanExtra(EXTRA_IS_ENCRYPTING, true)

        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java)
            ?: emptyList<Uri>()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: emptyList<String>()

        if (password.isEmpty() || uris.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val multiple = uris.size > 1
        val plural = if (multiple) "s" else ""

        val initialNotification = buildProgressNotification(
            getTitleText(isEncrypting, multiple),
            "Preparing to process file$plural...",
            0.0f
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        singleThreadExecutor.execute {
            runOperation(uris, names, password, isEncrypting)
        }

        return START_NOT_STICKY
    }

    private fun runOperation(
        uris: List<Uri>,
        names: List<String>,
        password: String,
        isEncrypting: Boolean
    ) {
        NativeBridge.resetProgress()

        // Tracks completed physical file paths across the entire batch run
        val successfullyWrittenPhysicalPaths = mutableListOf<String>()
        val multiple = (uris.size > 1)

        try {
            for (i in uris.indices) {
                if (NativeBridge.getProgress().isCancelled) {
                    throw OperationCancelledException("Operation was cancelled by the user")
                }

                var inputPfd: ParcelFileDescriptor? = null
                var outputPfd: ParcelFileDescriptor? = null
                var targetOutputUri: Uri? = null

                try {
                    _serviceProgress.value = NativeProgress(
                        percentComplete = 0.0f,
                        isCancelled = false,
                        currentFileName = names[i],
                        isEncrypting = isEncrypting
                    )
                    updateNotification(getTitleText(isEncrypting, multiple), "Preparing ${names[i]}...", 0.0f)

                    inputPfd = contentResolver.openFileDescriptor(uris[i], "r")
                        ?: throw IllegalStateException("Failed to open read descriptor for ${names[i]}")

                    val outputPair =
                        StorageHelper.createOutputFileNextToOriginal(this, uris[i], isEncrypting)
                    outputPfd = outputPair.first
                    targetOutputUri = outputPair.second

                    currentProcessingFileName = names[i]
                    startPolling(isEncrypting, multiple)

                    if (isEncrypting) {
                        NativeBridge.encryptFile(inputPfd.fd, outputPfd.fd, password)
                    } else {
                        NativeBridge.decryptFile(inputPfd.fd, outputPfd.fd, password)
                    }

                    // Close file handles
                    try { outputPfd.close(); outputPfd = null } catch (_: Exception) {}
                    try { inputPfd.close(); inputPfd = null } catch (_: Exception) {}

                    // Explicitly publish the file to the OS and throw if it fails
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val authority = targetOutputUri.authority ?: ""

                        if (authority.contains("fileprovider")) {
                            // Direct physical file path: Collect the path text to scan at the finish line
                            val fileRealPath = StorageHelper.getRealPathFromUri(this, uris[i])
                            if (!fileRealPath.isNullOrEmpty()) {
                                val originalFile = java.io.File(fileRealPath)
                                val parentDir = originalFile.parentFile
                                if (parentDir != null) {
                                    val targetName =
                                        StorageHelper.getOutputFileName(names[i], isEncrypting)
                                    val directPhysicalFile = java.io.File(parentDir, targetName)

                                    // Save path to scan in bulk later
                                    successfullyWrittenPhysicalPaths.add(directPhysicalFile.absolutePath)
                                }
                            }
                        } else {
                            // Scoped storage sandbox path fallback: Toggle the database pending status flag
                            val updateValues = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                            try {
                                val rowsUpdated = contentResolver.update(
                                    targetOutputUri,
                                    updateValues,
                                    null,
                                    null
                                )
                                if (rowsUpdated != 1) {
                                    throw IllegalStateException("MediaStore record could not be updated")
                                }
                            } catch (e: Exception) {
                                throw IllegalStateException(
                                    "Unable to publish output file: ${e.localizedMessage}",
                                    e
                                )
                            }
                        }
                    }

                    publishFinalProgress(isEncrypting, multiple)
                    stopPolling()

                    if (NativeBridge.getProgress().isCancelled) {
                        throw OperationCancelledException("Operation cancelled by user")
                    }
                } catch (e: Exception) {
                    stopPolling()
                    // Clean up the virtual media file from the database on error
                    if (targetOutputUri != null) {
                        try {
                            contentResolver.delete(targetOutputUri, null, null)
                        } catch (_: Exception) {
                        }
                    }
                    throw e
                } finally {
                    try { inputPfd?.close() } catch (_: Exception) {}
                    try { outputPfd?.close() } catch (_: Exception) {}
                }
            }

            resetServiceProgress()
        } catch (e: Exception) {
            stopPolling()

            val wasCancelled =
                NativeBridge.getProgress().isCancelled || e is OperationCancelledException

            _serviceProgress.value = NativeProgress(
                currentFileName = "",
                percentComplete = 0.0f,
                isCancelled = wasCancelled,
                hasError = !wasCancelled,
                errorString = if (wasCancelled) "" else (e.localizedMessage
                    ?: "Unknown failure occurred")
            )

            if (!wasCancelled) {
                showErrorNotification(
                    isEncrypting,
                    errorMessage = e.localizedMessage ?: "Unknown failure occurred"
                )
            }
        } finally {
            stopPolling()

            // Trigger media indexing for all completed physical files gathered
            if (successfullyWrittenPhysicalPaths.isNotEmpty()) {
                android.media.MediaScannerConnection.scanFile(
                    this,
                    successfullyWrittenPhysicalPaths.toTypedArray(),
                    Array(successfullyWrittenPhysicalPaths.size) { "application/octet-stream" },
                    null
                )
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startPolling(isEncrypting: Boolean, multiple: Boolean) {
        pollingJob = serviceScope.launch {
            var lastFileName = ""
            var lastPercentInt = -1
            var lastNotificationTime = 0L

            while (isActive) {
                delay(100.milliseconds)
                val nativeReport = NativeBridge.getProgress()

                val currentPercentInt = (nativeReport.percentComplete * 100).toInt()
                val currentFileName = currentProcessingFileName

                val fileNameChanged = currentFileName != lastFileName
                val percentChanged = currentPercentInt != lastPercentInt

                // Perform updates when filename or percent changes
                if (fileNameChanged || percentChanged) {
                    lastFileName = currentFileName
                    lastPercentInt = currentPercentInt

                    // Update StateFlow for the progress meter
                    _serviceProgress.value = nativeReport.copy(
                        currentFileName = currentFileName
                    )

                    // Post notifications on file changes or after 500ms
                    val currentTime = System.currentTimeMillis()
                    if (fileNameChanged || currentTime - lastNotificationTime >= 500) {
                        lastNotificationTime = currentTime

                        val contentText = "Processing $currentFileName ($currentPercentInt%)"
                        val titleText = getTitleText(isEncrypting, multiple)
                        updateNotification(titleText, contentText, nativeReport.percentComplete)
                    }
                }
            }
        }
    }

    private fun publishFinalProgress(isEncrypting: Boolean, multiple: Boolean) {
        val finalReport = NativeBridge.getProgress()

        // Update the StateFlow for the UI
        _serviceProgress.value = finalReport.copy(
            currentFileName = currentProcessingFileName,
        )

        // Update the notification UI
        val percentString = (finalReport.percentComplete * 100).toInt()
        val contentText = "Processing $currentProcessingFileName ($percentString%)"
        val titleText = getTitleText(isEncrypting, multiple)
        updateNotification(titleText, contentText, finalReport.percentComplete)
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Encryption/Decryption Progress",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        titleText: String,
        contentText: String,
        progress: Float
    ): Notification {
        val maxProgress = 100
        val currentProgress = (progress * 100).toInt()

        // Create an intent to bring the existing MainActivity to the foreground
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aescrypt_lock)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(maxProgress, currentProgress, false)
            .build()
    }

    private fun updateNotification(titleText: String, contentText: String, progress: Float) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildProgressNotification(titleText, contentText, progress))
    }

    private fun getTitleText(isEncrypting: Boolean, multiple: Boolean): String {
        val plural = if (multiple) "s" else ""
        return when (isEncrypting) {
            true -> "Encrypting File$plural"
            false -> "Decrypting File$plural"
        }
    }

    private fun getFailedTitleText(isEncrypting: Boolean): String {
        return when (isEncrypting) {
            true -> "Encryption Failed"
            false -> "Decryption Failed"
        }
    }

    private fun showErrorNotification(isEncrypting: Boolean, errorMessage: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Explicit intent targeting MainActivity without clearing the task stack
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            ERROR_NOTIFICATION_ID, // Use unique request code for distinct pending intents
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val errorNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aescrypt_lock)
            .setContentTitle(getFailedTitleText(isEncrypting))
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(ERROR_NOTIFICATION_ID, errorNotification)
    }

    override fun onDestroy() {
        // Attempt to cancel any active processing (harmless if there is none)
        requestCancel()
        serviceScope.cancel()
        singleThreadExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
