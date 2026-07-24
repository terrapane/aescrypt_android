/*
 *  aescrypt_jni.cpp
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This is a JNI layer that sits between the Kotlin UI components and
 *      the AES Crypt C++ engine code.
 *
 *  Portability Issues:
 *      Android-specific code.
 */

#include <jni.h>
#include <string>
#include <string_view>
#include <thread>
#include <vector>
#include <mutex>
#include <sys/stat.h>
#include <iostream>
#include <terra/logger/logger.h>
#include <terra/logger/android_logger.h>
#include "aescrypt.h"
#include "fd_streambuf.h"
#include "secure_containers.h"
#include "encrypt_stream.h"
#include "decrypt_stream.h"
#include "process_control.h"
#include "version.h"

namespace
{

Terra::ProcessControl Process_Control;

// Preliminary setup in preparation for calling functions that actually perform
// encryption or decryption
bool execute_crypto_operation(JNIEnv *env,
                              jint input_fd,
                              jint output_fd,
                              jstring password,
                              bool is_encrypt,
                              std::string &error_reason)
{
    // Reset global telemetry state for a clean execution run
    Process_Control.Reset();

    const char *pass_chars = env->GetStringUTFChars(password, nullptr);
    const std::string_view password_view(pass_chars);
    const SecureU8String pass_str(password_view.cbegin(),
                                         password_view.cend());
    env->ReleaseStringUTFChars(password, pass_chars);

    // If the user cancelled, stop processing
    if (Process_Control.IsCancelSet()) return false;

    // Gather metrics using the raw file descriptor
    struct stat st{};
    std::size_t file_size = 0;
    if (fstat(input_fd, &st) == 0)
    {
        file_size = static_cast<std::size_t>(st.st_size);
    }

    // Setup C++ streams using SecureVector so that no file data remains in RAM
    SecureVector<char> in_read_buffer(Buffered_IO_Size, 0);
    SecureVector<char> in_write_buffer(64, 0); // Minimal output buffer
    SecureVector<char> out_read_buffer(64, 0); // Minimal input buffer
    SecureVector<char> out_write_buffer(Buffered_IO_Size, 0);

    Terra::fd_streambuf in_buf(input_fd, in_read_buffer, in_write_buffer);
    Terra::fd_streambuf out_buf(output_fd, out_read_buffer, out_write_buffer);

    std::istream in_stream(&in_buf);
    std::ostream out_stream(&out_buf);

    // Be optimistic
    bool result = true;

    // Create a AndroidLogger object
    auto logger =
        std::make_shared<Terra::Logger::AndroidLogger>("AESCrypt_Native");

    // Perform stream encryption or decryption, as indicated
    if (is_encrypt)
    {
            // Create extensions vector to be inserted into stream header
            const std::string created_by = std::string(Terra::Project_Name) +
                                           " " +
                                           std::string(Terra::Project_Version);
            const std::vector<std::pair<std::string, std::string>> extensions =
            {
                {
                    "CREATED_BY", created_by
                }
            };

            std::tie(result, error_reason) =
                Terra::EncryptStream(logger,
                                     Process_Control,
                                     pass_str,
                                     KDF_Iterations,
                                     extensions,
                                     file_size,
                                     in_stream,
                                     out_stream);
    }
    else
    {
            std::tie(result, error_reason) =
                Terra::DecryptStream(logger,
                                     Process_Control,
                                     pass_str,
                                     file_size,
                                     in_stream,
                                     out_stream);
    }

    // Flush the output stream, but do not close since the Kotlin layer
    // owns the file descriptor and will close it
    out_stream.flush();

    // Finalize global telemetry before exiting thread
    Process_Control.SetPercentComplete(1.0F);

    return result;
}

void throw_java_exception(JNIEnv *env, const char *message)
{
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass != nullptr)
    {
        env->ThrowNew(exClass, message);
    }
}

} // namespace

// JNI Public Interface Exports

extern "C" JNIEXPORT void JNICALL
Java_com_terrapane_aescrypt_NativeBridge_encryptFile(
    JNIEnv *env,
    [[maybe_unused]] jobject object,
    jint input_fd,
    jint output_fd,
    jstring password)
{
    std::string error_reason;
    const bool success = execute_crypto_operation(env,
                                                  input_fd,
                                                  output_fd,
                                                  password,
                                                  true,
                                                  error_reason);

    if (!success)
    {
        // Throw an exception only on true errors
        if (!Process_Control.IsCancelSet())
        {
            // Pass the precise C++ error reason back to Kotlin
            if (error_reason.empty()) error_reason = "Unknown error occurred";
            throw_java_exception(env, error_reason.c_str());
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_terrapane_aescrypt_NativeBridge_decryptFile(
    JNIEnv *env,
    [[maybe_unused]] jobject object,
    jint input_fd,
    jint output_fd,
    jstring password)
{
    std::string error_reason;
    const bool success = execute_crypto_operation(env,
                                                  input_fd,
                                                  output_fd,
                                                  password,
                                                  false,
                                                  error_reason);

    if (!success)
    {
        // Throw an exception only on true errors
        if (!Process_Control.IsCancelSet())
        {
            // Pass the precise C++ error reason back to Kotlin
            if (error_reason.empty()) error_reason = "Unknown error occurred";
            throw_java_exception(env, error_reason.c_str());
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_terrapane_aescrypt_NativeBridge_resetProgress(
    [[maybe_unused]] const JNIEnv *env,
    [[maybe_unused]] jobject object)
{
    Process_Control.Reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_terrapane_aescrypt_NativeBridge_cancelOperation(
    [[maybe_unused]] const JNIEnv *env,
    [[maybe_unused]] jobject object)
{
    Process_Control.SetCancel();
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_terrapane_aescrypt_NativeBridge_getProgress(
    JNIEnv *env,
    [[maybe_unused]] jobject object)
{
    // Find the Kotlin NativeProgress class
    jclass clazz = env->FindClass("com/terrapane/aescrypt/NativeProgress");

    // Updated Constructor Signature: (F, Z) -> Takes Float and Boolean
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "(FZ)V");

    return env->NewObject(clazz,
                          constructor,
                          Process_Control.GetPercentComplete(),
                          Process_Control.IsCancelSet() ? 1 : 0);
}
