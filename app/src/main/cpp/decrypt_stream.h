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
 *      This file defines the function to decrypt a C++ stream.
 *
 *  Portability Issues:
 *      None.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <utility>
#include <iostream>
#include <vector>
#include <string>
#include <terra/logger/logger.h>
#include "secure_containers.h"
#include "process_control.h"

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
    std::ostream &ostream);

} // namespace Terra
