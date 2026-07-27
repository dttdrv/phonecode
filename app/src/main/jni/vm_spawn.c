#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include "fd_hygiene.h"

static void close_fd(int fd) {
    if (fd >= 0) {
        close(fd);
    }
}

static void close_fds(int *fds, size_t count) {
    for (size_t index = 0; index < count; index++) {
        close_fd(fds[index]);
        fds[index] = -1;
    }
}

static void free_arguments(char **arguments, size_t count) {
    if (arguments == NULL) {
        return;
    }
    for (size_t index = 0; index < count; index++) {
        free(arguments[index]);
    }
    free(arguments);
}

static int copy_string(JNIEnv *environment, jstring value, char **destination) {
    const char *source = (*environment)->GetStringUTFChars(environment, value, NULL);
    if (source == NULL) {
        return ENOMEM;
    }
    *destination = strdup(source);
    (*environment)->ReleaseStringUTFChars(environment, value, source);
    return *destination == NULL ? ENOMEM : 0;
}

static char **copy_arguments(
    JNIEnv *environment,
    jstring executable,
    jobjectArray values,
    size_t *count,
    int *error
) {
    jsize value_count = (*environment)->GetArrayLength(environment, values);
    *count = (size_t)value_count + 1;
    char **arguments = calloc(*count + 1, sizeof(char *));
    if (arguments == NULL) {
        *error = ENOMEM;
        return NULL;
    }
    *error = copy_string(environment, executable, &arguments[0]);
    for (jsize index = 0; *error == 0 && index < value_count; index++) {
        jstring value = (*environment)->GetObjectArrayElement(environment, values, index);
        if (value == NULL) {
            *error = EINVAL;
            break;
        }
        *error = copy_string(environment, value, &arguments[(size_t)index + 1]);
        (*environment)->DeleteLocalRef(environment, value);
    }
    if (*error != 0) {
        free_arguments(arguments, *count);
        return NULL;
    }
    return arguments;
}

static void child_error(int fd, int error) {
    ssize_t ignored = write(fd, &error, sizeof(error));
    (void)ignored;
    _exit(127);
}

JNIEXPORT jint JNICALL
Java_dev_phonecode_app_runtime_QemuNative_start(
    JNIEnv *environment,
    jobject instance,
    jstring executable,
    jobjectArray argument_values,
    jint kernel_fd,
    jint initramfs_fd,
    jint system_image_fd,
    jint console_fd,
    jint control_fd
) {
    (void)instance;
    int error = 0;
    size_t argument_count = 0;
    char **arguments = copy_arguments(
        environment,
        executable,
        argument_values,
        &argument_count,
        &error
    );
    int received[] = {kernel_fd, initramfs_fd, system_image_fd, console_fd, control_fd};
    if (arguments == NULL) {
        close_fds(received, 5);
        return -error;
    }

    int prepared[] = {-1, -1, -1, -1, -1};
    for (size_t index = 0; index < 5; index++) {
        prepared[index] = fcntl(received[index], F_DUPFD_CLOEXEC, 16);
        if (prepared[index] < 0) {
            error = errno;
            close_fds(received, 5);
            close_fds(prepared, 5);
            free_arguments(arguments, argument_count);
            return -error;
        }
    }
    close_fds(received, 5);

    int error_pipe[2] = {-1, -1};
    error = phonecode_make_cloexec_pipe_above(error_pipe, 64);
    if (error != 0) {
        close_fds(prepared, 5);
        free_arguments(arguments, argument_count);
        return -error;
    }

    pid_t parent = getpid();
    pid_t child = fork();
    if (child < 0) {
        error = errno;
        close_fds(prepared, 5);
        close_fds(error_pipe, 2);
        free_arguments(arguments, argument_count);
        return -error;
    }

    if (child == 0) {
        close_fd(error_pipe[0]);
        if (setpgid(0, 0) != 0) child_error(error_pipe[1], errno);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) child_error(error_pipe[1], errno);
        if (getppid() != parent) child_error(error_pipe[1], ESRCH);
        if (
            dup2(prepared[3], STDIN_FILENO) < 0 ||
            dup2(prepared[3], STDOUT_FILENO) < 0 ||
            dup2(prepared[3], STDERR_FILENO) < 0 ||
            dup2(prepared[0], 3) < 0 ||
            dup2(prepared[1], 4) < 0 ||
            dup2(prepared[2], 5) < 0 ||
            dup2(prepared[4], 6) < 0
        ) {
            child_error(error_pipe[1], errno);
        }
        close_fds(prepared, 5);
        execv(arguments[0], arguments);
        child_error(error_pipe[1], errno);
    }

    close_fds(prepared, 5);
    close_fd(error_pipe[1]);
    free_arguments(arguments, argument_count);

    int child_failure = 0;
    ssize_t bytes;
    do {
        bytes = read(error_pipe[0], &child_failure, sizeof(child_failure));
    } while (bytes < 0 && errno == EINTR);
    close_fd(error_pipe[0]);
    if (bytes > 0) {
        waitpid(child, NULL, 0);
        return -child_failure;
    }
    if (bytes < 0) {
        error = errno;
        kill(-child, SIGKILL);
        waitpid(child, NULL, 0);
        return -error;
    }
    return child;
}

JNIEXPORT jboolean JNICALL
Java_dev_phonecode_app_runtime_QemuNative_isRunning(
    JNIEnv *environment,
    jobject instance,
    jint pid
) {
    (void)environment;
    (void)instance;
    if (pid <= 0) {
        return JNI_FALSE;
    }
    int status = 0;
    pid_t result;
    do {
        result = waitpid(pid, &status, WNOHANG);
    } while (result < 0 && errno == EINTR);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_phonecode_app_runtime_QemuNative_stop(
    JNIEnv *environment,
    jobject instance,
    jint pid
) {
    (void)environment;
    (void)instance;
    if (pid <= 0) {
        return -EINVAL;
    }
    if (kill(-pid, SIGTERM) != 0 && errno != ESRCH) {
        return -errno;
    }
    for (int attempt = 0; attempt < 10; attempt++) {
        pid_t result = waitpid(pid, NULL, WNOHANG);
        if (result == pid || (result < 0 && errno == ECHILD)) {
            return 0;
        }
        usleep(50000);
    }
    if (kill(-pid, SIGKILL) != 0 && errno != ESRCH) {
        return -errno;
    }
    while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {
    }
    return 0;
}
