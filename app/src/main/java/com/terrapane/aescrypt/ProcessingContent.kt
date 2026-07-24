/*
 *  ProcessingContent.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This module contains the user interface code to show the Processing
 *      status as files are encrypted or decrypted.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingContent(
    state: AESCryptUiState.Processing,
    isWideScreen: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Dynamically adjust the width logic based on the screen profile orientation
    val outerModifier = if (isWideScreen) {
        modifier
            .widthIn(max = 800.dp)
            .fillMaxWidth()
    } else {
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
    }

    // Main root framing to handle full canvas space alignment
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Scrollable shell wrapping the internal view layouts
        Box(
            modifier = outerModifier.verticalScroll(scrollState),
            contentAlignment = Alignment.Center
        ) {
            if (isWideScreen) {
                // Wide Screen / Landscape: Side-by-side columns centered vertically
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextInfoSection(
                        title = state.actionTitle,
                        modifier = Modifier.weight(1f),
                        alignment = Alignment.Start
                    )
                    ProgressControlSection(
                        progress = state.progress,
                        statusMessage = state.statusMessage,
                        onCancel = onCancel,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // Standard Screen / Portrait: Vertically stacked layout
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextInfoSection(
                        title = state.actionTitle,
                        alignment = Alignment.CenterHorizontally
                    )
                    ProgressControlSection(
                        progress = state.progress,
                        statusMessage = state.statusMessage,
                        onCancel = onCancel
                    )
                }
            }
        }
    }
}

@Composable
private fun TextInfoSection(
    title: String,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = alignment
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp),
            textAlign = if (alignment == Alignment.Start) TextAlign.Start else TextAlign.Center
        )

        Text(
            text = "Keep AES Crypt open or check the notification tray if you switch applications",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (alignment == Alignment.Start) TextAlign.Start else TextAlign.Center
        )
    }
}

@Composable
private fun ProgressControlSection(
    progress: Float,
    statusMessage: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val percentValue = (progress * 100).toInt()

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(120.dp)
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 8.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                text = "$percentValue%",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.width(180.dp)
        ) {
            Text("Cancel")
        }
    }
}
