/*
 *  aescrypt.h
 *
 *  Copyright (C) 2024, 2025, 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This header file defines some common types used by AES Crypt.
 *
 *  Portability Issues:
 *      None.
 */

#pragma once

#include <cstddef>
#include <cstdint>

// Iterations used by PBKDF2
constexpr std::uint32_t KDF_Iterations = 600'000;

// Size in octets of buffer for file I/O
constexpr std::size_t Buffered_IO_Size = 131'072;
