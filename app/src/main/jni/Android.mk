LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := phonecode_vm
LOCAL_SRC_FILES := vm_spawn.c fd_hygiene.c misul_jni.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
