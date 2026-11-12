// AES-128 (ECB block, CTR, XTS with Nintendo's big-endian sector tweak) and SHA-256 for libnximport. Own implementation (FIPS-197 / FIPS 180-4),
// with AES-NI / SHA-NI (x86-64) and ARMv8 Crypto Extensions (aarch64: AES, SHA2) paths picked at run time when the CPU has them, else the portable ones.
// Every function is thread-safe once the first call (any of them, single-threaded) has run the CPU detection; the hashes are self-tested against known answers.
#ifndef NX_CRYPTO_H
#define NX_CRYPTO_H
#include <stddef.h>
#include <stdint.h>

typedef struct NxAes { uint8_t rk[11][16]; uint8_t drk[11][16]; } NxAes;   // round keys; drk = the equivalent-inverse-cipher keys the hardware paths use

void nx_aes_init(NxAes *a, const uint8_t key[16]);
void nx_aes_encrypt_block(const NxAes *a, const uint8_t in[16], uint8_t out[16]);
void nx_aes_decrypt_block(const NxAes *a, const uint8_t in[16], uint8_t out[16]);
// ECB over n*16 bytes (in may equal out)
void nx_aes_ecb_decrypt(const NxAes *a, const uint8_t *in, uint8_t *out, size_t n);
// CTR: ctr is the 16-byte counter of the first block; it is incremented (big-endian, 128-bit) as blocks go and left at the next block's value
void nx_aes_ctr(const NxAes *a, uint8_t ctr[16], const uint8_t *in, uint8_t *out, size_t n);
// XTS decrypt of whole sectors with the Nintendo tweak (the sector number as a 16-byte big-endian integer): data key a1, tweak key a2
void nx_aes_xts_decrypt(const NxAes *a1, const NxAes *a2, uint64_t sector, size_t sector_size, const uint8_t *in, uint8_t *out, size_t n);

typedef struct NxSha256 { uint32_t h[8]; uint64_t len; uint8_t buf[64]; size_t fill; } NxSha256;
void nx_sha256_init(NxSha256 *s);
void nx_sha256_update(NxSha256 *s, const void *p, size_t n);
void nx_sha256_final(NxSha256 *s, uint8_t out[32]);
void nx_sha256(const void *p, size_t n, uint8_t out[32]);

const char *nx_aes_backend(void);   // "aesni", "armv8" or "portable", for messages
const char *nx_sha_backend(void);   // "sha-ni", "armv8" or "portable"
#endif
