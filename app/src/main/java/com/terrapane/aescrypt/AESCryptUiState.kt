/*
 *  AESCryptUiState.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This file contains the sealed interface containing the user interface
 *      state and used by the view model.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import android.net.Uri

sealed interface AESCryptUiState {
    object FileSelection : AESCryptUiState

    data class PasswordEntry(
        val fileUris: List<Uri>,
        val fileNames: List<String>,
        val isEncrypting: Boolean
    ) : AESCryptUiState

    data class ValidationError(val message: String) : AESCryptUiState

    data class Processing(
        val progress: Float,
        val statusMessage: String,
        val actionTitle: String
    ) : AESCryptUiState
}
