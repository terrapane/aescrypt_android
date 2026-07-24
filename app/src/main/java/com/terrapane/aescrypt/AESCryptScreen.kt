/*
 *  AESCryptScreen.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This module contains the user interface code that allows users to
 *      select files, prompt for passwords, show progress, and display errors.
 *      Each of the content sections are contained in separate modules.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AESCryptScreen(
    viewModel: AESCryptViewModel,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current

    val isWideScreen = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    // File picker targeting multiple distinct files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val (validUris, fileNames) = StorageHelper.resolveUriDetails(context, uris)
            viewModel.handleFileSelection(validUris, fileNames)
        }
    }

    when (val state = uiState) {
        is AESCryptUiState.FileSelection -> {
            // If opened from a file manager, close the app rather than prompt for files
            if (viewModel.isLaunchedFromIntent) {
                LaunchedEffect(Unit) {
                    activity?.finish()
                }
            } else {
                CenteredContent(modifier) {
                    FileSelectionContent(onSelectFiles = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    })
                }
            }
        }

        is AESCryptUiState.PasswordEntry -> {
            FormContent(modifier) {
                PasswordEntryContent(
                    state = state,
                    isWideScreen = isWideScreen,
                    onBack = {
                        if (viewModel.isLaunchedFromIntent) {
                            // Close the activity if launched from an intent
                            activity?.finish()
                        } else {
                            // Reset back to the file selection state
                            viewModel.resetToSelection()
                        }
                     },
                    onConfirm = { password ->
                        viewModel.startProcessing(context, password, state)
                    }
                )
            }
        }

        is AESCryptUiState.ValidationError -> {
            CenteredContent(modifier) {
                ValidationFailureContent(
                    message = state.message,
                    modifier = Modifier,
                    onOK = {
                        AESCryptForegroundService.clearErrorNotification(context)

                        if (viewModel.isLaunchedFromIntent) {
                            // Close the activity if launched from an intent
                            activity?.finish()
                        } else {
                            // Reset back to the file selection state
                            viewModel.resetToSelection()
                        }
                    }
                )
            }
        }

        is AESCryptUiState.Processing -> {
            CenteredContent(modifier) {
                ProcessingContent(
                    state = state,
                    isWideScreen = isWideScreen,
                    modifier = Modifier,
                    onCancel = {
                        AESCryptForegroundService.requestCancel()
                    }
                )
            }
        }
    }
}

@Composable
fun CenteredContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
)
{
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun FormContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(WindowInsets.navigationBars)
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content()
    }
}