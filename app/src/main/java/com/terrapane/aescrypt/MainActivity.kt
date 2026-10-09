/*
 *  MainActivity.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This module contains the main entry point for the AES Crypt application.
 *      It also contains code to facilitate permission checking / approval.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.terrapane.aescrypt.ui.theme.AESCryptTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AESCryptViewModel by viewModels()

    // Register the native pop-up launcher for the notification permission
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // After dealing with the notification pop-up, proceed to the All Files request
        checkAndRequestAllFilesPermission()
    }

    // Request permissions for Android 10 and lower
    private val requestLegacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        val writeGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false

        if (!readGranted || !writeGranted) {
            Toast.makeText(
                this,
                "Storage permission is required to encrypt or decrypt",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Trigger the permission request on launch if not granted already
        checkAndRequestNotificationPermission()

        setContent {
            AESCryptTheme {
                val windowSizeClass = calculateWindowSizeClass(activity = this)
                AESCryptApp(
                    viewModel = viewModel,
                    windowSizeClass = windowSizeClass
                )
            }
        }

        // Process file if app was launched via "Open With..."
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure state is pristine for the next launch (useful for intent launches)
        if (isFinishing) {
            viewModel.resetToSelection()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == null) return

        //  Handle launching directly from Home Screen / App Launcher
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            viewModel.resetToSelection()
            return
        }

        val urisToProcess = mutableListOf<Uri>()

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { urisToProcess.add(it) }
            }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                uri?.let { urisToProcess.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.let { urisToProcess.addAll(it) }
            }
        }

        if (urisToProcess.isNotEmpty()) {
            // The following prevents reprocessing the intent
            this.intent.action = null
            this.intent.data = null

            val (validUris, fileNames) = StorageHelper.resolveUriDetails(this, urisToProcess)
            // Pass URIs and path names to file selection function
            viewModel.handleFileSelection(validUris, fileNames, isExternal = true)
        }
    }

    // Permission to receive notifications
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasNotificationPermission) {
                // Triggers the standard bottom sheet/pop-up window
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return // Pause here; the launcher's callback handles continuing the sequence
            }
        }

        // Fall through if on older Android version or already granted
        checkAndRequestAllFilesPermission()
    }

    // Permission to access files
    private fun checkAndRequestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            }
        } else {
            // Android 10 and lower (API 29 and below)
            val readGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            val writeGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            if (!readGranted || !writeGranted) {
                requestLegacyStoragePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }
}