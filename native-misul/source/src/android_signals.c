#include <arpa/inet.h>
#include <netdb.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <string.h>

static pthread_mutex_t signal_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct sigaction original_sigio;
static struct sigaction original_sigpipe;
static int signal_runtime_active;

__attribute__((visibility("hidden"))) int misul_android_resolve_addresses(
    const char *host,
    char *output,
    size_t output_size
) {
    if (host == NULL || output == NULL || output_size == 0) return 0;
    struct addrinfo hints = {0};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *results = NULL;
    if (getaddrinfo(host, NULL, &hints, &results) != 0) return 0;
    size_t written = 0;
    int resolved = 0;
    for (struct addrinfo *entry = results; entry != NULL; entry = entry->ai_next) {
        if (entry->ai_addr == NULL) continue;
        const void *source = NULL;
        if (entry->ai_family == AF_INET) {
            source = &((const struct sockaddr_in *)entry->ai_addr)->sin_addr;
        } else if (entry->ai_family == AF_INET6) {
            source = &((const struct sockaddr_in6 *)entry->ai_addr)->sin6_addr;
        } else continue;
        char address[INET6_ADDRSTRLEN];
        if (inet_ntop(entry->ai_family, source, address, sizeof(address)) == NULL) continue;
        const size_t length = strlen(address);
        if (written + length + 2 > output_size) break;
        memcpy(output + written, address, length);
        written += length;
        output[written++] = '\n';
        output[written] = '\0';
        resolved += 1;
    }
    freeaddrinfo(results);
    return resolved;
}

static void ignore_for_interruption(int signal_number) {
    (void)signal_number;
}

static int install_handlers(void) {
    struct sigaction action = {0};
    action.sa_handler = ignore_for_interruption;
    if (sigemptyset(&action.sa_mask) != 0) return 0;
    if (sigaction(SIGIO, &action, NULL) != 0) return 0;
    if (sigaction(SIGPIPE, &action, NULL) != 0) {
        (void)sigaction(SIGIO, &original_sigio, NULL);
        return 0;
    }
    return 1;
}

__attribute__((visibility("hidden"))) int misul_android_signals_acquire(void) {
    if (pthread_mutex_lock(&signal_mutex) != 0) return 0;
    if (signal_runtime_active ||
        sigaction(SIGIO, NULL, &original_sigio) != 0 ||
        sigaction(SIGPIPE, NULL, &original_sigpipe) != 0) {
        (void)pthread_mutex_unlock(&signal_mutex);
        return 0;
    }
    signal_runtime_active = 1;
    (void)pthread_mutex_unlock(&signal_mutex);
    return 1;
}

__attribute__((visibility("hidden"))) int misul_android_signals_repair(void) {
    if (pthread_mutex_lock(&signal_mutex) != 0) return 0;
    const int repaired = signal_runtime_active && install_handlers();
    (void)pthread_mutex_unlock(&signal_mutex);
    return repaired;
}

__attribute__((visibility("hidden"))) void misul_android_signals_release(void) {
    if (pthread_mutex_lock(&signal_mutex) != 0) return;
    if (signal_runtime_active) {
        (void)sigaction(SIGIO, &original_sigio, NULL);
        (void)sigaction(SIGPIPE, &original_sigpipe, NULL);
        signal_runtime_active = 0;
    }
    (void)pthread_mutex_unlock(&signal_mutex);
}
