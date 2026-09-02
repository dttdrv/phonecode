#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct misul_android_handle misul_android_handle;

typedef struct {
    uint8_t *data;
    size_t len;
} misul_android_buffer;

typedef uint32_t (*abi_version_fn)(void);
typedef uint32_t (*open_fn)(const uint8_t *, size_t, misul_android_handle **);
typedef uint32_t (*request_fn)(misul_android_handle *, const uint8_t *, size_t, misul_android_buffer *);
typedef uint32_t (*next_event_fn)(misul_android_handle *, uint32_t, misul_android_buffer *);
typedef uint32_t (*host_response_fn)(misul_android_handle *, const uint8_t *, size_t);
typedef uint32_t (*buffer_free_fn)(misul_android_handle *, misul_android_buffer *);
typedef uint32_t (*close_fn)(misul_android_handle **);

enum {
    MISUL_ANDROID_OK = 0,
    MISUL_ANDROID_INVALID_ARGUMENT = 1,
    MISUL_ANDROID_INVALID_CONFIG = 2,
    MISUL_ANDROID_OUT_OF_MEMORY = 3,
    MISUL_ANDROID_RUNTIME_ERROR = 4,
    MISUL_ANDROID_UNSUPPORTED = 5,
    MISUL_ANDROID_WRONG_OWNER = 6,
};

static pthread_once_t load_once = PTHREAD_ONCE_INIT;
static void *library;
static const char *load_error;
static abi_version_fn misul_abi_version;
static open_fn misul_open;
static request_fn misul_request;
static next_event_fn misul_next_event;
static host_response_fn misul_host_response;
static buffer_free_fn misul_buffer_free;
static close_fn misul_close;

static void load_misul(void) {
    library = dlopen("libmisul.so", RTLD_NOW | RTLD_LOCAL);
    if (library == NULL) {
        load_error = dlerror();
        return;
    }

#define RESOLVE(name)                                                           \
    do {                                                                        \
        dlerror();                                                              \
        *(void **)(&misul_##name) = dlsym(library, "misul_android_" #name);    \
        load_error = dlerror();                                                 \
        if (load_error != NULL) return;                                         \
    } while (0)

    RESOLVE(abi_version);
    RESOLVE(open);
    RESOLVE(request);
    RESOLVE(next_event);
    RESOLVE(host_response);
    RESOLVE(buffer_free);
    RESOLVE(close);
#undef RESOLVE
}

static int ensure_loaded(JNIEnv *env) {
    pthread_once(&load_once, load_misul);
    if (load_error == NULL && library != NULL) return 1;
    jclass type = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, load_error == NULL ? "Unable to load libmisul.so" : load_error);
    }
    return 0;
}

static void throw_status(JNIEnv *env, uint32_t status) {
    const char *class_name = "java/lang/IllegalStateException";
    const char *message = "Misul native runtime failed";
    switch (status) {
        case MISUL_ANDROID_INVALID_ARGUMENT:
            class_name = "java/lang/IllegalArgumentException";
            message = "Misul rejected an invalid native argument";
            break;
        case MISUL_ANDROID_INVALID_CONFIG:
            class_name = "java/lang/IllegalArgumentException";
            message = "Misul rejected the runtime configuration";
            break;
        case MISUL_ANDROID_OUT_OF_MEMORY:
            class_name = "java/lang/OutOfMemoryError";
            message = "Misul could not allocate native memory";
            break;
        case MISUL_ANDROID_UNSUPPORTED:
            class_name = "java/lang/UnsupportedOperationException";
            message = "Misul does not support this native operation";
            break;
        case MISUL_ANDROID_WRONG_OWNER:
            message = "Misul rejected a buffer owned by another runtime";
            break;
        case MISUL_ANDROID_RUNTIME_ERROR:
        default:
            break;
    }
    jclass type = (*env)->FindClass(env, class_name);
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static jbyte *bytes(JNIEnv *env, jbyteArray input, jsize *length) {
    if (input == NULL) {
        throw_status(env, MISUL_ANDROID_INVALID_ARGUMENT);
        return NULL;
    }
    *length = (*env)->GetArrayLength(env, input);
    return (*env)->GetByteArrayElements(env, input, NULL);
}

JNIEXPORT jint JNICALL
Java_dev_phonecode_app_runtime_MisulNative_nativeAbiVersion(JNIEnv *env, jobject self) {
    (void)self;
    if (!ensure_loaded(env)) return 0;
    return (jint)misul_abi_version();
}

JNIEXPORT jlong JNICALL
Java_dev_phonecode_app_runtime_MisulNative_nativeOpen(JNIEnv *env, jobject self, jbyteArray config) {
    (void)self;
    if (!ensure_loaded(env)) return 0;
    jsize length = 0;
    jbyte *input = bytes(env, config, &length);
    if (input == NULL) return 0;
    misul_android_handle *handle = NULL;
    uint32_t status = misul_open((const uint8_t *)input, (size_t)length, &handle);
    (*env)->ReleaseByteArrayElements(env, config, input, JNI_ABORT);
    if (status != MISUL_ANDROID_OK) {
        throw_status(env, status);
        return 0;
    }
    return (jlong)(uintptr_t)handle;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_phonecode_app_runtime_MisulNative_nativeRequest(JNIEnv *env, jobject self, jlong raw_handle, jbyteArray record) {
    (void)self;
    if (!ensure_loaded(env)) return NULL;
    jsize length = 0;
    jbyte *input = bytes(env, record, &length);
    if (input == NULL) return NULL;
    misul_android_handle *handle = (misul_android_handle *)(uintptr_t)raw_handle;
    misul_android_buffer output = {0};
    uint32_t status = misul_request(handle, (const uint8_t *)input, (size_t)length, &output);
    (*env)->ReleaseByteArrayElements(env, record, input, JNI_ABORT);
    if (status != MISUL_ANDROID_OK) {
        throw_status(env, status);
        return NULL;
    }
    if (output.len > INT32_MAX) {
        (void)misul_buffer_free(handle, &output);
        throw_status(env, MISUL_ANDROID_RUNTIME_ERROR);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)output.len);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)output.len, (const jbyte *)output.data);
    }
    uint32_t free_status = misul_buffer_free(handle, &output);
    if (result != NULL && !(*env)->ExceptionCheck(env) && free_status != MISUL_ANDROID_OK) {
        throw_status(env, free_status);
        return NULL;
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_phonecode_app_runtime_MisulNative_nativeNextEvent(JNIEnv *env, jobject self, jlong raw_handle, jint timeout_ms) {
    (void)self;
    if (!ensure_loaded(env)) return NULL;
    if (timeout_ms < 0) {
        throw_status(env, MISUL_ANDROID_INVALID_ARGUMENT);
        return NULL;
    }
    misul_android_handle *handle = (misul_android_handle *)(uintptr_t)raw_handle;
    misul_android_buffer output = {0};
    uint32_t status = misul_next_event(handle, (uint32_t)timeout_ms, &output);
    if (status != MISUL_ANDROID_OK) {
        throw_status(env, status);
        return NULL;
    }
    if (output.data == NULL && output.len == 0) return NULL;
    if (output.data == NULL || output.len > INT32_MAX) {
        (void)misul_buffer_free(handle, &output);
        throw_status(env, MISUL_ANDROID_RUNTIME_ERROR);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)output.len);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)output.len, (const jbyte *)output.data);
    }
    uint32_t free_status = misul_buffer_free(handle, &output);
    if (result != NULL && !(*env)->ExceptionCheck(env) && free_status != MISUL_ANDROID_OK) {
        throw_status(env, free_status);
        return NULL;
    }
    return result;
}

JNIEXPORT void JNICALL
Java_dev_phonecode_app_runtime_MisulNative_nativeHostResponse(JNIEnv *env, jobject self, jlong raw_handle, jbyteArray record) {
    (void)self;
    if (!ensure_loaded(env)) return;
    jsize length = 0;
    jbyte *input = bytes(env, record, &length);
    if (input == NULL) return;
    misul_android_handle *handle = (misul_android_handle *)(uintptr_t)raw_handle;
    uint32_t status = misul_host_response(handle, (const uint8_t *)input, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, record, input, JNI_ABORT);
    if (status != MISUL_ANDROID_OK) throw_status(env, status);
}

JNIEXPORT void JNICALL
Java_dev_phonecode_app_runtime_MisulNative_nativeClose(JNIEnv *env, jobject self, jlong raw_handle) {
    (void)self;
    if (!ensure_loaded(env)) return;
    misul_android_handle *handle = (misul_android_handle *)(uintptr_t)raw_handle;
    uint32_t status = misul_close(&handle);
    if (status != MISUL_ANDROID_OK) throw_status(env, status);
}
