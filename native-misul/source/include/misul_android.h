#ifndef MISUL_ANDROID_H
#define MISUL_ANDROID_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MISUL_ANDROID_ABI_VERSION 1u
#define MISUL_ANDROID_MAX_RECORD_BYTES (1024u * 1024u)

typedef struct misul_android_handle misul_android_handle;

typedef struct {
    uint8_t *data;
    size_t len;
} misul_android_buffer;

typedef enum {
    MISUL_ANDROID_OK = 0,
    MISUL_ANDROID_INVALID_ARGUMENT = 1,
    MISUL_ANDROID_INVALID_CONFIG = 2,
    MISUL_ANDROID_OUT_OF_MEMORY = 3,
    MISUL_ANDROID_RUNTIME_ERROR = 4,
    MISUL_ANDROID_UNSUPPORTED = 5,
    MISUL_ANDROID_WRONG_OWNER = 6
} misul_android_status;

uint32_t misul_android_abi_version(void);
misul_android_status misul_android_open(
    const uint8_t *config,
    size_t config_len,
    misul_android_handle **out_handle
);
misul_android_status misul_android_request(
    misul_android_handle *handle,
    const uint8_t *record,
    size_t record_len,
    misul_android_buffer *out_result
);
misul_android_status misul_android_next_event(
    misul_android_handle *handle,
    uint32_t timeout_ms,
    misul_android_buffer *out_result
);
misul_android_status misul_android_host_response(
    misul_android_handle *handle,
    const uint8_t *record,
    size_t record_len
);
misul_android_status misul_android_buffer_free(
    misul_android_handle *handle,
    misul_android_buffer *buffer
);
misul_android_status misul_android_close(misul_android_handle **handle);

#ifdef __cplusplus
}
#endif

#endif
