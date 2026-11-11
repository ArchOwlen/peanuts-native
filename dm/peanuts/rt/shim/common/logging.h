// PNRT shim for Eden's common/logging.h: the LOG_* macros over one printf-style sink
// (pnrt_log_sink; stderr by default, __android_log_print on Android via peanuts_rt.cpp).
#pragma once

#include <cstdio>
#include <format>
#include <string>
#include <string_view>

#include "common/common_types.h"
#include "fmt/format.h"

namespace Common::Log {

enum class Level : u8 { Trace, Debug, Info, Warning, Error, Critical, Count };

// Only the classes the lifted files name. Unused ones cost nothing.
enum class Class : u8 { Log, Common, Core, Core_Timing, Debug, Kernel, Service, Render, Input, Frontend, Count };

/// The single sink every LOG_* lands in: (level, "file:line func", message).
using Sink = void (*)(int level, const char* where, const char* message);
void SetSink(Sink sink);
void SetMinLevel(Level level);
Level MinLevel();
void Emit(Level level, const char* file, unsigned line, const char* function, const std::string& text);

template <typename... Args>
void FmtLogMessage(Class, Level level, const char* file, unsigned line, const char* function,
                   std::format_string<Args...> fmt_str, const Args&... args) {
    if (level < MinLevel()) {
        return;
    }
    Emit(level, file, line, function, std::vformat(fmt_str.get(), std::make_format_args(args...)));
}

} // namespace Common::Log

#define LOG_TRACE(log_class, ...) \
    ::Common::Log::FmtLogMessage(::Common::Log::Class::log_class, ::Common::Log::Level::Trace, \
                                 __FILE__, __LINE__, __func__, __VA_ARGS__)
#define LOG_DEBUG(log_class, ...) \
    ::Common::Log::FmtLogMessage(::Common::Log::Class::log_class, ::Common::Log::Level::Debug, \
                                 __FILE__, __LINE__, __func__, __VA_ARGS__)
#define LOG_INFO(log_class, ...) \
    ::Common::Log::FmtLogMessage(::Common::Log::Class::log_class, ::Common::Log::Level::Info, \
                                 __FILE__, __LINE__, __func__, __VA_ARGS__)
#define LOG_WARNING(log_class, ...) \
    ::Common::Log::FmtLogMessage(::Common::Log::Class::log_class, ::Common::Log::Level::Warning, \
                                 __FILE__, __LINE__, __func__, __VA_ARGS__)
#define LOG_ERROR(log_class, ...) \
    ::Common::Log::FmtLogMessage(::Common::Log::Class::log_class, ::Common::Log::Level::Error, \
                                 __FILE__, __LINE__, __func__, __VA_ARGS__)
#define LOG_CRITICAL(log_class, ...) \
    ::Common::Log::FmtLogMessage(::Common::Log::Class::log_class, ::Common::Log::Level::Critical, \
                                 __FILE__, __LINE__, __func__, __VA_ARGS__)
