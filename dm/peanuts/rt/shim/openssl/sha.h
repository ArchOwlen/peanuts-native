// PNRT shim for OpenSSL's <openssl/sha.h>: mod_module.cpp verifies a package's module .so against
// the manifest's sha256 with SHA256(). There is no OpenSSL on the Android side of Peanuts, so this
// is a small self-contained SHA-256 (shim/shim.cpp) behind the same two names.
#pragma once

#include <cstddef>

#define SHA256_DIGEST_LENGTH 32

extern "C" unsigned char* SHA256(const unsigned char* data, size_t len, unsigned char* out);
