/*
 *  AESCryptApp.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This file defines the AESCryptApp object, which creates the view model
 *      and creates the screen the user sees.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable

@Composable
fun AESCryptApp(
    viewModel: AESCryptViewModel,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            AESCryptTopBar()
        },
    ) { innerPadding ->
        AESCryptScreen(
            viewModel = viewModel,
            windowSizeClass = windowSizeClass,
            modifier = modifier
                .padding(innerPadding)
        )
    }
}
