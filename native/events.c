/* NEEV ADB diagnostic, Apache-2.0. Command text is exposed only through a
 * root-owned, bounded volatile descriptor after client authentication. */
#include "events.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define LOG_LIMIT (128 * 1024)
#define EVENT_BUDGET 32
#define COMMANDS 7
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int log_fd = -1, traffic_fd = -1, client_fd = -1, command_fd = -1, initialized;
static off_t log_offset;
static off_t command_offset;
static uint64_t seq, snapshot_seq, packets[2], bytes[2], pending[2][COMMANDS][2];
static uint64_t shell_active, sync_active, rejected, dropped;
static unsigned event_budget = EVENT_BUDGET;
static int authenticated;
static const char *commands[COMMANDS] = { "CNXN", "AUTH", "OPEN", "OKAY", "CLSE", "WRTE", "OTHER" };

static uint64_t uptime_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return 0;
    return (uint64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static void disable(int *fd) {
    if (*fd >= 0) close(*fd);
    *fd = -1;
}

static int take_fd(int fd, uid_t owner, struct stat *info) {
    int flags = fcntl(fd, F_GETFD);
    /* Even a rejected descriptor must not leak through a shell exec. */
    if (flags < 0) return -1;
    if (fcntl(fd, F_SETFD, flags | FD_CLOEXEC) < 0 || fstat(fd, info) ||
            !S_ISREG(info->st_mode) || info->st_uid != owner ||
            (info->st_mode & 0777) != 0600 || info->st_nlink != 1) {
        close(fd);
        return -1;
    }
    flags = fcntl(fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) == O_RDONLY || (flags & O_APPEND)) {
        close(fd);
        return -1;
    }
    return fd;
}

static int write_at(int *fd, const char *data, size_t length, off_t offset) {
    size_t done = 0;
    if (*fd < 0) return 0;
    while (done < length) {
        ssize_t n = pwrite(*fd, data + done, length - done, offset + done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { disable(fd); return 0; }
        done += n;
    }
    return 1;
}

static void replace(int *fd, const char *data, size_t length) {
    if (write_at(fd, data, length, 0) && ftruncate(*fd, length)) disable(fd);
}

static void snapshot(void) {
    char line[320];
    int n = snprintf(line, sizeof(line), "V1 %" PRIu64 " %" PRIu64 " %d %" PRIu64
                     " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 "\n",
                     uptime_ms(), ++snapshot_seq, authenticated, packets[0], bytes[0],
                     packets[1], bytes[1], shell_active, sync_active, seq);
    if (n > 0 && (size_t)n < sizeof(line)) replace(&traffic_fd, line, n);
}

static void raw_event(const char *name, uint64_t a, uint64_t b) {
    char line[160];
    if (log_fd < 0) return;
    int n = snprintf(line, sizeof(line), "E1 %" PRIu64 " %" PRIu64 " %s %" PRIu64 " %" PRIu64 "\n",
                     ++seq, uptime_ms(), name, a, b);
    if (n <= 0 || (size_t)n >= sizeof(line)) return;
    if (write_at(&log_fd, line, n, log_offset)) log_offset += n;
}

static void event(const char *name, uint64_t a, uint64_t b, int limited) {
    if (limited && !event_budget) { ++dropped; return; }
    if (limited) --event_budget;
    if (log_fd < 0) return;
    /* Reserve the maximum record size so rollover never exceeds the cap. */
    if (log_offset + 160 > LOG_LIMIT) {
        if (ftruncate(log_fd, 0)) { disable(&log_fd); return; }
        log_offset = 0;
        raw_event("ROLLOVER", 0, 0);
    }
    raw_event(name, a, b);
}

void neev_monitor_init(uid_t expected_owner) {
    struct stat stats[4];
    pthread_mutex_lock(&lock);
    if (initialized) { pthread_mutex_unlock(&lock); return; }
    initialized = 1;
    log_fd = take_fd(7, expected_owner, &stats[0]);
    traffic_fd = take_fd(8, expected_owner, &stats[1]);
    client_fd = take_fd(9, expected_owner, &stats[2]);
    command_fd = take_fd(6, expected_owner, &stats[3]);
    /* Distinct files prevent accidental aliasing from corrupting the format. */
    if (traffic_fd >= 0 && log_fd >= 0 && stats[0].st_dev == stats[1].st_dev &&
            stats[0].st_ino == stats[1].st_ino) disable(&traffic_fd);
    if (client_fd >= 0 && ((log_fd >= 0 && stats[0].st_dev == stats[2].st_dev &&
            stats[0].st_ino == stats[2].st_ino) || (traffic_fd >= 0 &&
            stats[1].st_dev == stats[2].st_dev && stats[1].st_ino == stats[2].st_ino)))
        disable(&client_fd);
    if (command_fd >= 0 && ((log_fd >= 0 && stats[0].st_dev == stats[3].st_dev && stats[0].st_ino == stats[3].st_ino) ||
            (traffic_fd >= 0 && stats[1].st_dev == stats[3].st_dev && stats[1].st_ino == stats[3].st_ino) ||
            (client_fd >= 0 && stats[2].st_dev == stats[3].st_dev && stats[2].st_ino == stats[3].st_ino))) disable(&command_fd);
    if (log_fd >= 0 && ftruncate(log_fd, 0)) disable(&log_fd);
    if (command_fd >= 0 && ftruncate(command_fd, 0)) disable(&command_fd);
    /* Remove stale authenticated state before a listener can accept clients. */
    replace(&client_fd, "0\n", 2);
    snapshot();
    pthread_mutex_unlock(&lock);
}

void neev_monitor_authenticated(int value) {
    pthread_mutex_lock(&lock);
    value = !!value;
    if (authenticated != value) {
        authenticated = value;
        if (!value && command_fd >= 0) {
            if (ftruncate(command_fd, 0)) disable(&command_fd);
            command_offset = 0;
        }
        replace(&client_fd, value ? "1\n" : "0\n", 2);
        event(value ? "AUTHENTICATED" : "DISCONNECTED", 0, 0, 1);
        snapshot();
    }
    pthread_mutex_unlock(&lock);
}

void neev_monitor_auth_rejected(void) {
    pthread_mutex_lock(&lock);
    ++rejected;
    pthread_mutex_unlock(&lock);
}

void neev_monitor_packet(int transmit, uint32_t command, uint32_t length) {
    /* Numeric wire command values, independent of AOSP headers for host tests. */
    static const uint32_t values[COMMANDS - 1] = {
        0x4e584e43, 0x48545541, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257 };
    unsigned index = 0;
    while (index < COMMANDS - 1 && values[index] != command) ++index;
    transmit = !!transmit;
    pthread_mutex_lock(&lock);
    ++packets[transmit]; bytes[transmit] += length;
    ++pending[transmit][index][0]; pending[transmit][index][1] += length;
    pthread_mutex_unlock(&lock);
}

unsigned neev_monitor_service_kind(const char *name) {
    if (!strncmp(name, "shell:", 6)) {
        if (name[6]) {
            char clean[257], lower[257], record[320]; size_t i=0; int sensitive=0;
            while (i<256 && name[6+i]) {
                unsigned char c=(unsigned char)name[6+i];
                clean[i]=(c>=32 && c<127)?(char)c:'?';
                lower[i]=(clean[i]>='A' && clean[i]<='Z')?(char)(clean[i]+32):clean[i]; ++i;
            }
            clean[i]=0; lower[i]=0;
            const char *words[]={"pass","token","secret","psk","credential","authorization","cookie","key","ssid",0};
            for (i=0;words[i];i++) if(strstr(lower,words[i])) { sensitive=1; break; }
            pthread_mutex_lock(&lock);
            if (authenticated && command_fd>=0) {
                int n=snprintf(record,sizeof(record),"C1 %s\n",sensitive?"[민감한 명령 숨김]":clean);
                if(n>0 && (size_t)n<sizeof(record)) {
                    if(command_offset+n>16384) { if(ftruncate(command_fd,0)) disable(&command_fd); command_offset=0; }
                    if(write_at(&command_fd,record,(size_t)n,command_offset)) command_offset+=n;
                }
            }
            pthread_mutex_unlock(&lock);
            return NEEV_SERVICE_EXEC;
        }
        return NEEV_SERVICE_SHELL;
    }
    if (!strcmp(name, "sync:")) return NEEV_SERVICE_SYNC;
    return NEEV_SERVICE_NONE;
}

static void service(unsigned kind, unsigned channel, int opening) {
    const char *name;
    uint64_t *active;
    if (kind == NEEV_SERVICE_SHELL) name = opening ? "SHELL_OPEN" : "SHELL_CLOSE";
    else if (kind == NEEV_SERVICE_EXEC) name = opening ? "EXEC_OPEN" : "EXEC_CLOSE";
    else if (kind == NEEV_SERVICE_SYNC) name = opening ? "SYNC_OPEN" : "SYNC_CLOSE";
    else return;
    pthread_mutex_lock(&lock);
    active = kind == NEEV_SERVICE_SYNC ? &sync_active : &shell_active;
    if (opening) ++*active;
    else if (*active) --*active;
    event(name, channel, 0, 1);
    pthread_mutex_unlock(&lock);
}
void neev_monitor_service_open(unsigned kind, unsigned channel) { service(kind, channel, 1); }
void neev_monitor_service_close(unsigned kind, unsigned channel) { service(kind, channel, 0); }

void neev_monitor_flush(void) {
    unsigned direction, command;
    char name[24];
    pthread_mutex_lock(&lock);
    if (rejected) { event("AUTH_REJECTED", rejected, 0, 0); rejected = 0; }
    if (dropped) { event("EVENTS_DROPPED", dropped, 0, 0); dropped = 0; }
    for (direction = 0; direction < 2; ++direction) {
        for (command = 0; command < COMMANDS; ++command) {
            if (!pending[direction][command][0]) continue;
            snprintf(name, sizeof(name), "%s_%s", direction ? "TX" : "RX", commands[command]);
            event(name, pending[direction][command][0], pending[direction][command][1], 0);
            pending[direction][command][0] = pending[direction][command][1] = 0;
        }
    }
    event_budget = EVENT_BUDGET;
    snapshot();
    pthread_mutex_unlock(&lock);
}

static void *monitor_thread(void *unused) {
    (void)unused;
    for (;;) {
        struct timespec remaining = {1, 0};
        while (nanosleep(&remaining, &remaining) && errno == EINTR) {}
        neev_monitor_flush();
    }
    return NULL;
}

void neev_monitor_start(void) {
    pthread_t thread;
    pthread_mutex_lock(&lock);
    event("LISTENING", 0, 0, 0);
    snapshot();
    pthread_mutex_unlock(&lock);
    if (!pthread_create(&thread, NULL, monitor_thread, NULL)) pthread_detach(thread);
    else {
        /* A worker that verifies these FD targets must not trust a frozen
         * snapshot. Authentication callbacks can still maintain FD9. */
        pthread_mutex_lock(&lock);
        disable(&traffic_fd);
        disable(&log_fd);
        pthread_mutex_unlock(&lock);
    }
}
