// PNRT shim: identical in effect to Eden's common/stb.h (the implementation lives in
// shim/stb_impl.cpp).
#pragma once

#define STBI_ONLY_JPEG 1
#define STBI_ONLY_PNG 1
#include <stb_image.h>
#include <stb_image_resize.h>
#include <stb_image_write.h>
