/*
 *  PasswordEntryContent.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This module contains the user interface code to facilitate password
 *      entry.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terrapane.aescrypt.ui.theme.MonoInputOutputStyle

@Composable
fun ColumnScope.PasswordEntryContent(
    state: AESCryptUiState.PasswordEntry,
    isWideScreen: Boolean,
    onBack: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    val passwordsMatch = password == confirmPassword
    val isPasswordEmpty = password.isBlank()
    val canSubmit = if (state.isEncrypting) {
        !isPasswordEmpty && passwordsMatch
    } else {
        !isPasswordEmpty
    }

    val contentModifier = if (isWideScreen) {
        Modifier
            .widthIn(max = 500.dp)
            .fillMaxWidth()
    } else {
        Modifier.fillMaxWidth()
    }

    val action = if (state.isEncrypting) "Encrypt" else "Decrypt"
    val plural = if (state.fileNames.size > 1) "s" else ""

    // Text fields at the top of the form
    Text(
        text = "$action File$plural",
        style = MaterialTheme.typography.titleMedium,
        modifier = contentModifier
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Selected file$plural:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = contentModifier
    )

    // Selected files box
    LazyColumn(
        modifier = contentModifier
            .heightIn(max = 150.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(state.fileNames.size) { index ->
            val fileName = state.fileNames[index]
            Text(
                text = "• $fileName",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    val trailingIconBlock: @Composable () -> Unit = {
        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
            Icon(
                painter = painterResource(
                    id = if (isPasswordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                ),
                contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    val visualTransformation = if (isPasswordVisible) {
        VisualTransformation.None
    } else {
        PasswordVisualTransformation()
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Password fields
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Enter Password") },
        visualTransformation = visualTransformation,
        trailingIcon = trailingIconBlock,
        singleLine = true,
        textStyle = MonoInputOutputStyle,
        modifier = contentModifier
    )

    Spacer(modifier = Modifier.height(16.dp))

    if (state.isEncrypting) {
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirm Password") },
            visualTransformation = visualTransformation,
            trailingIcon = trailingIconBlock,
            singleLine = true,
            textStyle = MonoInputOutputStyle,
            isError = !passwordsMatch && confirmPassword.isNotEmpty(),
            supportingText = {
                if (!passwordsMatch && confirmPassword.isNotEmpty()) {
                    Text(
                        "Passwords do not match",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            modifier = contentModifier
        )
    }

    // Spacer to push the action buttons to the bottom of the screen
    // or provide 16.dp in space if already near the bottom
    Spacer(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 16.dp)
    )

    // Action buttons
    Row(
        modifier = contentModifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.weight(1f)
        ) {
            Text("Cancel")
        }
        Button(
            onClick = { if (canSubmit) onConfirm(password) },
            enabled = canSubmit,
            modifier = Modifier.weight(1f)
        ) {
            Text(action)
        }
    }
}
