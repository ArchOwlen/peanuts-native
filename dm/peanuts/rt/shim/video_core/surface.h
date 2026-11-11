// PNRT shim for Eden's video_core/surface.h: the PixelFormat names texture_readback.h compares
// (there is no GPU texture cache here; the readback backend reports Unsupported).
#pragma once

#include "common/common_types.h"

namespace VideoCore::Surface {

enum class PixelFormat : u32 {
    A8B8G8R8_UNORM = 0,
    A8B8G8R8_SNORM,
    A8B8G8R8_SINT,
    A8B8G8R8_UINT,
    R5G6B5_UNORM,
    B5G6R5_UNORM,
    A1R5G5B5_UNORM,
    A2B10G10R10_UNORM,
    A2B10G10R10_UINT,
    A2R10G10B10_UNORM,
    A1B5G5R5_UNORM,
    A5B5G5R1_UNORM,
    R8_UNORM,
    R8_SNORM,
    R8_SINT,
    R8_UINT,
    A8B8G8R8_SRGB,
    B8G8R8A8_UNORM,
    B8G8R8A8_SRGB,
    Invalid = 255,
};

} // namespace VideoCore::Surface
