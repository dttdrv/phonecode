#include <fcntl.h>
#include <stdint.h>
#include <unistd.h>

#include "fd_hygiene.h"

int main(void) {
    int pipe_fds[2] = {-1, -1};
    if (phonecode_make_cloexec_pipe_above(pipe_fds, 64) != 0) return 1;
    if (pipe_fds[0] < 64 || pipe_fds[1] < 64) return 2;
    if ((fcntl(pipe_fds[0], F_GETFD) & FD_CLOEXEC) == 0) return 3;
    if ((fcntl(pipe_fds[1], F_GETFD) & FD_CLOEXEC) == 0) return 4;

    const uint8_t sent = 0x5a;
    uint8_t received = 0;
    if (write(pipe_fds[1], &sent, sizeof(sent)) != sizeof(sent)) return 5;
    if (read(pipe_fds[0], &received, sizeof(received)) != sizeof(received)) return 6;
    close(pipe_fds[0]);
    close(pipe_fds[1]);
    return received == sent ? 0 : 7;
}
