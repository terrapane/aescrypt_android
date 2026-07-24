/*
 *  NativeBridge.kt
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This module defines an object and data class to facilitate calling
 *      native C++ code that performs the encryption and decryption.
 *
 *  Portability Issues:
 *      None.
 */

package com.terrapane.aescrypt

object NativeBridge {
    init {
        System.loadLibrary("aescrypt_android")
    }

    external fun encryptFile(inputFd: Int, outputFd: Int, password: String)
    external fun decryptFile(inputFd: Int, outputFd: Int, password: String)
    external fun getProgress(): NativeProgress
    external fun resetProgress()
    external fun cancelOperation()
}

// A data class that matches a C++ structure returned by getProgress()
// Note: @JvmOverloads forces the compiler to generate the (Float, Boolean)
//       constructor for JNI.
data class NativeProgress @JvmOverloads constructor(
    val percentComplete: Float,
    val isCancelled: Boolean,
    val currentFileName: String = "",
    val hasError: Boolean = false,
    val errorString: String = "",
    val isEncrypting: Boolean = true
)
