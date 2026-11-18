/* NEEV private ADB diagnostic, Apache-2.0. Uses AOSP libmincrypt. */
#include <resolv.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <mincrypt/rsa.h>
#include <mincrypt/sha.h>
#include "session.h"

static RSAPublicKey pinned_key;

/* Provision exactly one adb public key from local USB, before dropping uid. */
int neev_verify_key(const char *key_file) {
    char line[2048];
    unsigned char decoded[sizeof(RSAPublicKey) + 4];
    FILE *f = fopen(key_file, "r");
    if (!f) return 0;
    int one_line = fgets(line, sizeof(line), f) != NULL && fgetc(f) == EOF && !ferror(f);
    fclose(f);
    if (!one_line) return 0;
    line[strcspn(line, " \t\r\n")] = 0;
    if (__b64_pton(line, decoded, sizeof(decoded)) != sizeof(pinned_key)) return 0;
    memcpy(&pinned_key, decoded, sizeof(pinned_key));
    return pinned_key.len == RSANUMWORDS && pinned_key.exponent == 65537;
}

int adb_auth_generate_token(void *token, size_t len) {
    FILE *urandom = fopen("/dev/urandom", "rb");
    if (!urandom) return 0;
    size_t count = fread(token, 1, len, urandom);
    fclose(urandom);
    return count == len ? (int)len : 0;
}

int adb_auth_verify(void *token, void *signature, int length) {
    return RSA_verify(&pinned_key, signature, length, token, SHA_DIGEST_SIZE);
}
