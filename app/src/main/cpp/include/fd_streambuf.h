/*
 *  fd_streambuf.h
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      Custom stream buffer for use with file descriptors to allow use of file
 *      descriptors with std::istream and std::ostream objects.
 *
 *  Portability Issues:
 *      Requires C++20 or higher.
 */

#pragma once

#include <streambuf>
#include <span>
#include <ranges>
#include <memory>
#include <unistd.h>

namespace Terra
{

class fd_streambuf : public std::streambuf
{
    public:
        explicit fd_streambuf(int fd,
                              std::span<char> in_span,
                              std::span<char> out_span) noexcept :
            fd{fd},
            in_span{in_span}
        {
            setg(in_span.data(), in_span.data(), in_span.data());

            if (!out_span.empty())
            {
                setp(out_span.data(), out_span.subspan(out_span.size()).data());
            }
            else
            {
                setp(nullptr, nullptr);
            }
        }
        fd_streambuf(const fd_streambuf &other) = delete;
        fd_streambuf(fd_streambuf &&other) = delete;

        ~fd_streambuf() override { internal_sync(); }

        fd_streambuf &operator=(const fd_streambuf &other) = delete;
        fd_streambuf &operator=(fd_streambuf &&other) = delete;

    protected:
        int_type underflow() override
        {
            if (gptr() < egptr())
            {
                return traits_type::to_int_type(*gptr());
            }

            if (in_span.empty()) return traits_type::eof();

            ssize_t bytes_read = 0;
            do
            {
                bytes_read = ::read(fd, in_span.data(), in_span.size());
            } while (bytes_read == -1 && errno == EINTR);

            if (bytes_read <= 0) return traits_type::eof();

            char *base = in_span.data();
            char *end_ptr =
                in_span.subspan(static_cast<size_t>(bytes_read)).data();

            setg(base, base, end_ptr);

            return traits_type::to_int_type(*gptr());
        }

        std::streamsize xsgetn(char *s, std::streamsize count) override
        {
            if (count <= 0) return 0;

            std::streamsize total = 0;
            std::span<char> dest(s, static_cast<size_t>(count));

            while (!dest.empty())
            {
                if (gptr() < egptr())
                {
                    const auto available =
                        static_cast<size_t>(egptr() - gptr());
                    const auto n = std::min(available, dest.size());

                    std::ranges::copy(std::span(gptr(), n), dest.begin());

                    gbump(static_cast<int>(n));
                    dest = dest.subspan(n);
                    total += static_cast<std::streamsize>(n);
                }
                else
                {
                    if (underflow() == traits_type::eof()) break;
                }
            }
            return total;
        }

        int_type pbackfail(int_type c) override
        {
            if (gptr() > eback())
            {
                gbump(-1);

                if (c != traits_type::eof())
                {
                    *gptr() = traits_type::to_char_type(c);
                }

                return traits_type::to_int_type(*gptr());
            }

            return traits_type::eof();
        }

        int_type overflow(int_type ch) override
        {
            if (ch == traits_type::eof()) return traits_type::not_eof(ch);

            if (epptr() == nullptr) return traits_type::eof();

            if (pptr() >= epptr())
            {
                if (internal_sync() == -1) return traits_type::eof();
            }

            *pptr() = traits_type::to_char_type(ch);
            pbump(1);

            return ch;
        }

        std::streamsize xsputn(const char *s, std::streamsize count) override
        {
            if (count <= 0) return 0;

            if (epptr() == nullptr) return 0;

            std::streamsize total = 0;
            std::span<const char> source(s, static_cast<size_t>(count));

            while (!source.empty())
            {
                const auto available = static_cast<size_t>(epptr() - pptr());

                if (pptr() == pbase() &&
                    source.size() >= static_cast<size_t>(epptr() - pbase()))
                {
                    ssize_t bytes_written = 0;
                    do
                    {
                        bytes_written =
                            ::write(fd, source.data(), source.size());
                    } while (bytes_written == -1 && errno == EINTR);

                    if (bytes_written <= 0) break;

                    source = source.subspan(static_cast<size_t>(bytes_written));
                    total += static_cast<std::streamsize>(bytes_written);
                    continue;
                }

                if (available > 0)
                {
                    const auto n = std::min(available, source.size());
                    std::ranges::copy(source.first(n), pptr());

                    pbump(static_cast<int>(n));
                    source = source.subspan(n);
                    total += static_cast<std::streamsize>(n);
                }
                else
                {
                    if (internal_sync() == -1) break;
                }
            }

            return total;
        }

        int sync() override { return internal_sync(); }

    private:
        int internal_sync()
        {
            char *base = pbase();
            const char *curr = pptr();

            if (base == nullptr || curr == base) return 0;

            std::span<char> remaining(base, static_cast<size_t>(curr - base));

            while (!remaining.empty())
            {
                ssize_t bytes_written = 0;
                do
                {
                    bytes_written =
                        ::write(fd, remaining.data(), remaining.size());
                } while (bytes_written == -1 && errno == EINTR);

                if (bytes_written <= 0)
                {
                    if (remaining.data() != base)
                    {
                        std::ranges::move(remaining, base);
                        setp(base, epptr());
                        pbump(static_cast<int>(remaining.size()));
                    }
                    return -1;
                }

                remaining =
                    remaining.subspan(static_cast<size_t>(bytes_written));
            }

            setp(base, epptr());

            return 0;
        }

        int fd;
        std::span<char> in_span;
};

} // namespace Terra
