#define _GNU_SOURCE

#include "fd_hygiene.h"

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <unistd.h>

static void close_pair(int fds[2]) {
    for (size_t index = 0; index < 2; index++) {
        if (fds[index] >= 0) close(fds[index]);
        fds[index] = -1;
    }
}

int phonecode_make_cloexec_pipe_above(int output[2], int minimum_fd) {
    if (output == NULL || minimum_fd < 0) return EINVAL;
    output[0] = -1;
    output[1] = -1;

    int raw[2] = {-1, -1};
#if defined(__linux__)
    if (pipe2(raw, O_CLOEXEC) != 0) return errno;
#else
    if (pipe(raw) != 0) return errno;
#endif

    for (size_t index = 0; index < 2; index++) {
        do {
            output[index] = fcntl(raw[index], F_DUPFD_CLOEXEC, minimum_fd);
        } while (output[index] < 0 && errno == EINTR);
        if (output[index] < 0) {
            int error = errno;
            close_pair(raw);
            close_pair(output);
            return error;
        }
    }
    close_pair(raw);
    return 0;
}
