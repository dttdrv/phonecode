#ifndef PHONECODE_FD_HYGIENE_H
#define PHONECODE_FD_HYGIENE_H

int phonecode_make_cloexec_pipe_above(int output[2], int minimum_fd);

#endif
