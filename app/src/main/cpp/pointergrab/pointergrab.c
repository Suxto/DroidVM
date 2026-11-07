// SPDX-License-Identifier: GPL-3.0-or-later
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int open_pad(int product, const char *name) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;
    struct dirent *entry;
    int found = -1;
    while ((entry = readdir(dir))) {
        if (strncmp(entry->d_name, "event", 5)) continue;
        char path[512], label[128] = {0};
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) continue;
        struct input_id id = {0};
        if (ioctl(fd, EVIOCGID, &id) == 0 && id.bustype == BUS_VIRTUAL
                && id.vendor == 0x15d9 && id.product == product
                && ioctl(fd, EVIOCGNAME(sizeof(label)), label) >= 0
                && !strcmp(label, name) && ioctl(fd, EVIOCGRAB, 1) == 0) {
            found = fd;
            break;
        }
        close(fd);
    }
    closedir(dir);
    return found;
}

int main(int argc, char **argv) {
    if (argc != 3 || getuid() != 0) return 1;
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t length = strlen(argv[1]);
    if (length == 0 || length >= sizeof(address.sun_path) - 1) return 1;
    memcpy(address.sun_path + 1, argv[1], length);
    int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sock < 0) return 2;
    if (connect(sock, (struct sockaddr *)&address,
                sizeof(address.sun_family) + 1 + length) != 0) { close(sock); return 2; }
    struct ucred peer;
    socklen_t peer_size = sizeof(peer);
    if (getsockopt(sock, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) != 0
            || peer.uid != (uid_t)strtoul(argv[2], NULL, 10)) { close(sock); return 3; }
    int pad = -1, mouse = -1;
    int64_t deadline = now_ms() + 3000, scan_at = 0;
    int result = 0;
    while (now_ms() < deadline) {
        int64_t now = now_ms();
        if (pad < 0 && now >= scan_at) {
            pad = open_pad(0xa1, "Xiaomi Touch");
            mouse = open_pad(0xa2, "Xiaomi Mouse");
            if (pad < 0 || mouse < 0) {
                if (pad >= 0) close(pad);
                if (mouse >= 0) close(mouse);
                pad = mouse = -1;
            }
            scan_at = now + 1000;
        }
        struct pollfd fds[] = {
            { .fd = sock, .events = POLLIN },
            { .fd = pad, .events = POLLIN },
            { .fd = mouse, .events = POLLIN }
        };
        int ready = poll(fds, 3, 100);
        if (ready < 0) { if (errno == EINTR) continue; break; }
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (fds[0].revents & POLLIN) {
            char commands[32];
            ssize_t n = recv(sock, commands, sizeof(commands), MSG_DONTWAIT);
            if (n <= 0) break;
            int quit = 0;
            for (ssize_t i = 0; i < n; i++) {
                if (commands[i] == 'H') deadline = now_ms() + 3000;
                else quit = 1;
            }
            if (quit) break;
        }
        for (int i = 1; i <= 2; i++) {
            if (fds[i].revents & (POLLERR | POLLHUP | POLLNVAL)) { result = 4; goto done; }
            if (!(fds[i].revents & POLLIN)) continue;
            struct input_event events[64];
            ssize_t n = read(fds[i].fd, events, sizeof(events));
            if (n < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if (n <= 0 || n % sizeof(struct input_event)) { result = 4; goto done; }
            // Discard the compatibility Mouse stream; the Touch stream owns all gestures.
            if (i == 2) continue;
            for (size_t j = 0; j < (size_t)n / sizeof(struct input_event); j++)
                if (events[j].type == EV_SYN && events[j].code == SYN_DROPPED) { result = 4; goto done; }
            // Never block while holding an exclusive grab. A slow UI releases the devices.
            size_t offset = 0;
            while (offset < (size_t)n) {
                ssize_t sent = send(sock, (char *)events + offset, n - offset, MSG_DONTWAIT | MSG_NOSIGNAL);
                if (sent < 0 && errno == EINTR) continue;
                if (sent <= 0) { result = 5; goto done; }
                offset += (size_t)sent;
            }
        }
    }
done:
    if (pad >= 0) { ioctl(pad, EVIOCGRAB, 0); close(pad); }
    if (mouse >= 0) { ioctl(mouse, EVIOCGRAB, 0); close(mouse); }
    close(sock);
    return result;
}
