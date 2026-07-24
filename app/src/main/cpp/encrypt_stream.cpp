/*
 *  encrypt_stream.cpp
 *
 *  Copyright (C) 2024, 2025, 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This file implements logic to encrypt a C++ stream.
 *
 *  Portability Issues:
 *      None.
 */

#include <iostream>
#include <sstream>
#include <thread>
#include <mutex>
#include <cstddef>
#include <cstdint>
#include <vector>
#include <utility>
#include <string>
#include <memory>
#include <terra/aescrypt/engine/encryptor.h>
#include <terra/logger/logger.h>
#include "encrypt_stream.h"
#include "secure_containers.h"
#include "process_control.h"
#include "aescrypt.h"

namespace Terra
{

/*
 *  EncryptStream()
 *
 *  Description:
 *      This function will encrypt the given input stream to the given output
 *      stream using the specified password.
 *
 *  Parameters:
 *      logger [in]
 *          The logger to which logging output will be sent.
 *
 *      process_control [in]
 *          A structure used by the main thread and worker thread to control
 *          execution.
 *
 *      password [in]
 *          The password (in UTF-8 encoding) to use to encrypt files.
 *
 *      iterations [in]
 *          The number of iterations to use with the KDF function.
 *
 *      extensions [in]
 *          A list of name/value string pairs that are inserted into the
 *          head of the AES Crypt output stream.  These are neither encrypted
 *          nor authenticated.
 *
 *      input_size [in]
 *          The number of octets in the input stream (if known).
 *
 *      istream [in]
 *          Input stream from which plaintext is read.
 *
 *      ostream [out]
 *          Output stream to which ciphertext is written.
 *
 *  Returns:
 *      True if encryption is successful, false if not.  Also included will
 *      be an error string if the result is false.
 *
 *  Comments:
 *      None.
 */
std::pair<bool, std::string> EncryptStream(
    Terra::Logger::LoggerPointer logger,
    ProcessControl &process_control,
    const SecureU8String &password,
    const std::uint32_t iterations,
    const std::vector<std::pair<std::string, std::string>> &extensions,
    const std::size_t input_size,
    std::istream &istream,
    std::ostream &ostream)
{
    Terra::AESCrypt::Engine::EncryptResult encrypt_result{};
    bool encryption_complete{};
    bool cancel_encryption{};

    using namespace Terra::AESCrypt::Engine;

    // Create an AES Crypt Engine Encryptor object
    Encryptor encryptor(logger);

    // Routine to update the progress information
    auto progress_updater =
        [&]([[maybe_unused]] const std::string &,
            std::size_t position)
        {
            process_control.SetPercentComplete(static_cast<float>(position) /
                                               static_cast<float>(input_size));
        };

    logger->Log("Starting encryption thread");

    // Encrypt the stream via a separate thread
    std::thread encrypt_thread(
        [&]()
        {
            // Encrypt the current input stream
            encrypt_result =
                encryptor.Encrypt(static_cast<std::u8string>(password),
                                  iterations,
                                  istream,
                                  ostream,
                                  extensions,
                                  progress_updater,
                                  input_size / 100);

            // Lock the mutex to assign result
            const std::lock_guard<std::mutex> lock(process_control.mutex);
            encryption_complete = true;
            process_control.cv.notify_all();
        });

    // Lock the mutex
    std::unique_lock<std::mutex> lock(process_control.mutex);

    // Wait for encryption to complete or to be told to terminate
    process_control.cv.wait(lock,
                            [&]() -> bool
                            {
                                return encryption_complete ||
                                       process_control.IsCancelSet(true);
                            });

    // If the user cancelled, cancel encryption if still going
    cancel_encryption =
        process_control.IsCancelSet(true) && (!encryption_complete);

    // Unlock the mutex
    lock.unlock();

    // If the user cancelled, can encryption if still working
    if (cancel_encryption) encryptor.Cancel();

    // Wait for the encryption thread to exit
    encrypt_thread.join();

    logger->Log("Encryption thread finished");

    // If encryption failed for reasons other than cancellation, report why
    if ((encrypt_result != EncryptResult::Success) &&
        (encrypt_result != EncryptResult::EncryptionCancelled))
    {
        std::ostringstream oss;
        oss << "Encryption error: " << encrypt_result;
        logger->error << oss.str() << std::flush;
        return {false, oss.str()};
    }

    return {encrypt_result == EncryptResult::Success, {}};
}

} // namespace

