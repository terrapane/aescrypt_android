/*
 *  AESCryptViewModel.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This module contains view model used by the AES Crypt application.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class AESCryptViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<AESCryptUiState>(AESCryptUiState.FileSelection)
    val uiState: StateFlow<AESCryptUiState> = _uiState.asStateFlow()

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    // These placeholder values and updated before processing starts
    private var currentAction = "Processing file(s)..."
    private var isEncrypting = false

    var isLaunchedFromIntent: Boolean = false
        private set

    init {
        viewModelScope.launch {
            AESCryptForegroundService.serviceProgress.collect { progress ->
                when {
                    progress == null && _uiState.value is AESCryptUiState.Processing -> {
                        val modeText = if (isEncrypting) "Encryption" else "Decryption"
                        Toast.makeText(context, "$modeText complete", Toast.LENGTH_SHORT).show()

                        _uiState.value = AESCryptUiState.FileSelection
                    }

                    progress == null -> {
                        _uiState.value = AESCryptUiState.FileSelection
                    }

                    progress.hasError -> {
                        _uiState.value = AESCryptUiState.ValidationError(progress.errorString)
                    }

                    progress.isCancelled -> {
                        if (_uiState.value is AESCryptUiState.Processing) {
                            val modeText = if (isEncrypting) "Encryption" else "Decryption"
                            Toast.makeText(context, "$modeText cancelled", Toast.LENGTH_SHORT).show()
                        }

                        // Returning to the main screen after canceling
                        _uiState.value = AESCryptUiState.FileSelection

                        // Clear the service-related data (especially cancelled flag)
                        AESCryptForegroundService.resetServiceProgress()
                    }

                    else -> {
                        _uiState.value = AESCryptUiState.Processing(
                            progress = progress.percentComplete,
                            statusMessage = "Processing ${progress.currentFileName}",
                            actionTitle = currentAction
                        )
                    }
                }
            }
        }
    }

    fun handleFileSelection(uris: List<Uri>, resolvedNames: List<String>, isExternal: Boolean = false) {
        isLaunchedFromIntent = isExternal

        if (uris.isEmpty()) {
            _uiState.value =
                AESCryptUiState.ValidationError("No files were selected")
            return
        }

        // Count .aes and non-.aes files to determine operating mode
        val encryptCount = resolvedNames.count { !it.endsWith(".aes", ignoreCase = true) }
        val decryptCount = resolvedNames.count { it.endsWith(".aes", ignoreCase = true) }

        if (encryptCount > 0 && decryptCount > 0) {
            _uiState.value = AESCryptUiState.ValidationError(
                "Cannot select a both .aes and non-.aes files at the same time"
            )
            return
        }

        val isEncrypting = encryptCount > 0

        _uiState.value = AESCryptUiState.PasswordEntry(
            fileUris = uris,
            fileNames = resolvedNames,
            isEncrypting = isEncrypting
        )
    }

    fun startProcessing(context: Context, password: String, state: AESCryptUiState.PasswordEntry) {
        val collidingName =
            StorageHelper.findExistingCollision(context, state.fileUris, state.isEncrypting)
        if (collidingName != null) {
            _uiState.value = AESCryptUiState.ValidationError(
                "The output file '$collidingName' already exists"
            )
            return
        }

        val action = if (state.isEncrypting) "Encrypting" else "Decrypting"
        val plural = if (state.fileNames.size > 1) "s" else ""

        currentAction = "$action File$plural"
        isEncrypting = state.isEncrypting

        _uiState.value = AESCryptUiState.Processing(0.0f, "Preparing streams...", currentAction)

        AESCryptForegroundService.start(
            context = context,
            password = password,
            uris = ArrayList(state.fileUris),
            names = ArrayList(state.fileNames),
            isEncrypting = state.isEncrypting
        )
    }

    // Return back to the start screen
    fun resetToSelection() {
        isLaunchedFromIntent = false
        _uiState.value = AESCryptUiState.FileSelection
    }
}
