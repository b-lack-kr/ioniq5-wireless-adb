/* NEEV private ADB diagnostic, Apache-2.0. */
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <net/if.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <unistd.h>

#include "session.h"
#include "events.h"

static int server_fd = -1;

/* setuid/setgid clear PDEATHSIG. Arm it again as shell before listening.
 * Checking the expected parent after prctl also covers an already-dead worker. */
static void require_worker(void) {
    const char *expected = getenv("NEEV_ADB_WORKER_PID");
    char actual[32];
    if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) < 0) {
        perror("Watch ADB worker");
        exit(1);
    }
    pid_t parent = getppid();
    snprintf(actual, sizeof(actual), "%ld", (long)parent);
    if (parent <= 1 || !expected || strcmp(expected, actual) != 0) {
        fprintf(stderr, "ADB worker unavailable\n");
        exit(1);
    }
}

/* Bind as root, but listen and accept only after upstream drops to AID_SHELL. */
void neev_prepare(const char *iface, const char *key_file) {
    require_worker();
    neev_monitor_init(0);
    struct ifreq query = {0};
    size_t length = strlen(iface);
    if (length <= 6 || length > 9 || strncmp(iface, "rtwlan", 6) != 0 ||
            strspn(iface + 6, "0123456789") != length - 6) {
        fprintf(stderr, "Invalid WLAN interface\n");
        exit(1);
    }
    if (!neev_verify_key(key_file) || chdir("/") != 0) {
        fprintf(stderr, "Invalid ADB public key\n");
        exit(1);
    }
    server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0 || fcntl(server_fd, F_SETFD, FD_CLOEXEC) < 0 ||
            setsockopt(server_fd, SOL_SOCKET, SO_BINDTODEVICE, iface, length + 1) != 0) {
        perror("Bind to WLAN");
        exit(1);
    }
    memcpy(query.ifr_name, iface, length + 1);
    struct sockaddr_in *address = (struct sockaddr_in *)&query.ifr_addr;
    if (ioctl(server_fd, SIOCGIFADDR, &query) != 0 ||
            address->sin_family != AF_INET ||
            address->sin_addr.s_addr != inet_addr("192.168.43.1")) {
        fprintf(stderr, "Dongle address unavailable\n");
        exit(1);
    }
    int reuse = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    address->sin_port = htons(5555);
    if (bind(server_fd, (const struct sockaddr *)address, sizeof(*address)) < 0) {
        perror("Bind to AP address");
        exit(1);
    }
    /* A host may disconnect while the transport thread is writing. */
    signal(SIGPIPE, SIG_IGN);
}

/* sysdeps.h remaps several libc function names; keep socket setup above it. */
#include "sysdeps.h"
#include "adb.h"

static void *accept_clients(void *arg) {
    for (;;) {
        int fd = adb_socket_accept(server_fd, NULL, NULL);
        if (fd < 0) {
            if (errno == EINTR) continue;
            perror("Accept ADB connection");
            _exit(1);
        }
        close_on_exec(fd);
        disable_tcp_nagle(fd);
        /* Keep at most one live host transport, closing duplicate connections. */
        if (register_socket_transport(fd, "host", 5555, 1) < 0)
            adb_close(fd);
    }
}

void neev_accept_clients(void) {
    adb_thread_t thread;
    require_worker();
    if (getuid() != 2000 || geteuid() != 2000 || getgid() != 2000 ||
            listen(server_fd, 4) < 0 ||
            adb_thread_create(&thread, accept_clients, NULL)) {
        fprintf(stderr, "Unable to listen as shell uid 2000\n");
        exit(1);
    }
    neev_monitor_start();
}
