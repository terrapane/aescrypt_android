/*
 *  decrypt_stream.cpp
 *
 *  Copyright (C) 2024, 2025, 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This file implements the function to decrypt a C++ stream.
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
#include <terra/aescrypt/engine/decryptor.h>
#include <terra/logger/logger.h>
#include "decrypt_stream.h"
#include "secure_containers.h"
#include "process_control.h"
#include "aescrypt.h"

namespace Terra
{

/*
 *  DecryptStream()
 *
 *  Description:
 *      This function will decrypt the given input stream to the given output
 *      stream using the specified password.
 *
 *  Parameters:
 *      logger [in]
 *          The logger to which logging output will be sent.
 *
 *      process_control [in]
 *          A structure used by the main thread and worker thread to control
 *          execution.  For example, if the user pressed CTRL-C while
 *          decryption is in progress, it will gracefully terminate
 *          decryption and allow the program to exit.
 *
 *      password [in]
 *          The password (in UTF-8 encoding) to use to decrypt files.
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
 *      True if decryption is successful, false if not. Also included will
 *      be an error string if the result is false.
 *
 *  Comments:
 *      None.
 */
std::pair<bool, std::string> DecryptStream(
    Terra::Logger::LoggerPointer logger,
    ProcessControl &process_control,
    const SecureU8String &password,
    const std::size_t input_size,
    std::istream &istream,
    std::ostream &ostream)
{
    Terra::AESCrypt::Engine::DecryptResult decrypt_result{};
    bool decryption_complete{};
    bool cancel_decryption{};

    using namespace Terra::AESCrypt::Engine;

    // Create an AES Crypt Engine Decryptor object
    Decryptor decryptor(logger);

    // Routine to update the progress information
    auto progress_updater =
        [&]([[maybe_unused]] const std::string &,
            std::size_t position)
        {
            process_control.SetPercentComplete(static_cast<float>(position) /
                                               static_cast<float>(input_size));
        };

    logger->Log("Starting decryption thread");

    // Decrypt the stream via a separate thread
    std::thread decrypt_thread(
        [&]()
        {
            // Decrypt the current input stream
            decrypt_result = decryptor.Decrypt(
                static_cast<std::u8string>(password),
                istream,
                ostream,
                progress_updater,
                input_size / 100);

            // Lock the mutex to assign result
            const std::lock_guard<std::mutex> lock(process_control.mutex);
            decryption_complete = true;
            process_control.cv.notify_all();
        });

    // Lock the mutex
    std::unique_lock<std::mutex> lock(process_control.mutex);

    // Wait for decryption to complete or to be told to terminate
    process_control.cv.wait(lock,
                            [&]() -> bool
                            {
                                return decryption_complete ||
                                       process_control.IsCancelSet(true);
                            });

    // If the user cancelled, cancel decryption if still going
    cancel_decryption =
        process_control.IsCancelSet(true) && (!decryption_complete);

    // Unlock the mutex
    lock.unlock();

    // If the user cancelled, can decryption if still working
    if (cancel_decryption) decryptor.Cancel();

    // Wait for the decryption thread to exit
    decrypt_thread.join();

    logger->Log("Decryption thread finished");

    // If decryption failed for reasons other than cancellation, report why
    if ((decrypt_result != DecryptResult::Success) &&
        (decrypt_result != DecryptResult::DecryptionCancelled))
    {
        std::ostringstream oss;
        oss << "Decryption error: " << decrypt_result;
        logger->error << oss.str() << std::flush;
        return {false, oss.str()};
    }

    return {decrypt_result == DecryptResult::Success, {}};
}

} // namespace Terra
