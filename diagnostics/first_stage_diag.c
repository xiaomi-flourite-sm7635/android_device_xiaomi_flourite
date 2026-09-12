/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/reboot.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define DIAG_PREFIX "flourite-first-stage: "
#define WATCHDOG_TIMEOUT_SECONDS 90

static int kmsg_fd = -1;

static void Log(const char *format, ...) {
  char message[2048];
  int prefix_length = snprintf(message, sizeof(message), "<6>" DIAG_PREFIX);
  if (prefix_length < 0 || (size_t)prefix_length >= sizeof(message)) {
    return;
  }

  va_list args;
  va_start(args, format);
  int body_length = vsnprintf(message + prefix_length,
                              sizeof(message) - prefix_length, format, args);
  va_end(args);
  if (body_length < 0) {
    return;
  }

  size_t length = (size_t)prefix_length + (size_t)body_length;
  if (length >= sizeof(message) - 1) {
    length = sizeof(message) - 2;
  }
  message[length++] = '\n';
  (void)write(kmsg_fd, message, length);
}

static void DumpFile(const char *label, const char *path, size_t limit) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd == -1) {
    Log("%s unavailable: %s", label, strerror(errno));
    return;
  }

  size_t total = 0;
  while (total < limit) {
    char buffer[1024];
    size_t wanted = sizeof(buffer) - 1;
    if (wanted > limit - total) {
      wanted = limit - total;
    }

    ssize_t bytes = read(fd, buffer, wanted);
    if (bytes <= 0) {
      break;
    }
    total += (size_t)bytes;

    for (ssize_t i = 0; i < bytes; ++i) {
      if (buffer[i] == '\n' || buffer[i] == '\r' || buffer[i] == '\0') {
        buffer[i] = ' ';
      }
    }
    buffer[bytes] = '\0';
    Log("%s: %s", label, buffer);
  }
  close(fd);
}

static void DumpDirectory(const char *path, size_t limit) {
  DIR *directory = opendir(path);
  if (directory == NULL) {
    Log("directory %s unavailable: %s", path, strerror(errno));
    return;
  }

  size_t count = 0;
  struct dirent *entry;
  while (count < limit && (entry = readdir(directory)) != NULL) {
    if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
      continue;
    }

    char entry_path[512];
    int path_length =
        snprintf(entry_path, sizeof(entry_path), "%s/%s", path, entry->d_name);
    if (path_length < 0 || (size_t)path_length >= sizeof(entry_path)) {
      continue;
    }

    char target[512];
    ssize_t target_length = readlink(entry_path, target, sizeof(target) - 1);
    if (target_length >= 0) {
      target[target_length] = '\0';
      Log("dir %s: %s -> %s", path, entry->d_name, target);
    } else {
      struct stat info;
      if (stat(entry_path, &info) == 0) {
        Log("dir %s: %s mode=%o dev=%u:%u", path, entry->d_name,
            (unsigned int)(info.st_mode & 07777),
            (unsigned int)major(info.st_rdev),
            (unsigned int)minor(info.st_rdev));
      } else {
        Log("dir %s: %s", path, entry->d_name);
      }
    }
    ++count;
  }
  closedir(directory);
}

static void DumpLink(const char *label, const char *path) {
  char target[512];
  ssize_t length = readlink(path, target, sizeof(target) - 1);
  if (length < 0) {
    Log("%s unavailable: %s", label, strerror(errno));
    return;
  }
  target[length] = '\0';
  Log("%s: %s", label, target);
}

static bool IsNumericName(const char *name) {
  if (*name == '\0') {
    return false;
  }

  for (const char *character = name; *character != '\0'; ++character) {
    if (*character < '0' || *character > '9') {
      return false;
    }
  }
  return true;
}

static bool ProcessExists(const char *wanted_name) {
  DIR *proc = opendir("/proc");
  if (proc == NULL) {
    return false;
  }

  bool found = false;
  struct dirent *entry;
  while ((entry = readdir(proc)) != NULL) {
    if (!IsNumericName(entry->d_name)) {
      continue;
    }

    char path[128];
    int path_length =
        snprintf(path, sizeof(path), "/proc/%s/comm", entry->d_name);
    if (path_length < 0 || (size_t)path_length >= sizeof(path)) {
      continue;
    }

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd == -1) {
      continue;
    }

    char name[64];
    ssize_t length = read(fd, name, sizeof(name) - 1);
    close(fd);
    if (length <= 0) {
      continue;
    }
    while (length > 0 &&
           (name[length - 1] == '\n' || name[length - 1] == '\r')) {
      --length;
    }
    name[length] = '\0';

    if (strcmp(name, wanted_name) == 0) {
      found = true;
      break;
    }
  }

  closedir(proc);
  return found;
}

static void DumpState(const char *phase) {
  Log("state snapshot: %s", phase);
  DumpLink("pid1-exe", "/proc/1/exe");
  DumpFile("pid1-wchan", "/proc/1/wchan", 512);
  DumpFile("pid1-stack", "/proc/1/stack", 8192);
  DumpFile("pid1-status", "/proc/1/status", 8192);
  DumpFile("mounts", "/proc/mounts", 16384);
  DumpFile("modules", "/proc/modules", 32768);
  DumpDirectory("/dev/block/mapper", 128);
  DumpDirectory("/dev/block/by-name", 192);
  DumpDirectory("/dev/block/bootdevice/by-name", 192);
}

static bool SecondStageStarted(void) {
  struct stat info;
  return stat("/dev/socket/property_service", &info) == 0;
}

static bool RecoveryMode(void) {
  return access("/system/bin/recovery", F_OK) == 0;
}

static void ReleaseFirstStageInit(void) {
  pid_t console_supervisor = getppid();
  if (console_supervisor <= 1) {
    Log("cannot identify first-stage console supervisor, parent=%d",
        console_supervisor);
    return;
  }

  if (kill(console_supervisor, SIGKILL) == -1) {
    Log("failed to terminate first-stage console supervisor %d: %s",
        console_supervisor, strerror(errno));
    return;
  }
  Log("terminated first-stage console supervisor %d", console_supervisor);
}

static void RebootToRecovery(void) {
  Log("watchdog timeout; requesting warm reboot to recovery");

  for (;;) {
    errno = 0;
    long result = syscall(SYS_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2,
                          LINUX_REBOOT_CMD_RESTART2, "recovery");
    Log("reboot syscall returned %ld: %s", result, strerror(errno));
    sleep(5);
  }
}

int main(void) {
  kmsg_fd = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
  if (kmsg_fd == -1) {
    return 1;
  }

  Log("diagnostic hook started, pid=%d", getpid());
  DumpFile("cmdline", "/proc/cmdline", 16384);
  DumpFile("bootconfig", "/proc/bootconfig", 32768);
  DumpState("before first-stage mounts");

  if (RecoveryMode()) {
    Log("recovery mode detected; watchdog disabled");
    ReleaseFirstStageInit();
    close(kmsg_fd);
    return 0;
  }

  pid_t watchdog = fork();
  if (watchdog < 0) {
    Log("failed to fork watchdog: %s", strerror(errno));
    ReleaseFirstStageInit();
    close(kmsg_fd);
    return 1;
  }
  if (watchdog > 0) {
    Log("watchdog armed for %d seconds, pid=%d", WATCHDOG_TIMEOUT_SECONDS,
        watchdog);
    ReleaseFirstStageInit();
    close(kmsg_fd);
    return 0;
  }

  (void)setsid();
  bool second_stage_logged = false;
  for (int elapsed = 1; elapsed <= WATCHDOG_TIMEOUT_SECONDS; ++elapsed) {
    sleep(1);
    if (!second_stage_logged && SecondStageStarted()) {
      Log("second-stage property service detected after %d seconds", elapsed);
      second_stage_logged = true;
    }
    if (ProcessExists("adbd")) {
      Log("adbd detected after %d seconds; watchdog disarmed", elapsed);
      close(kmsg_fd);
      _exit(0);
    }
    if (ProcessExists("system_server")) {
      Log("system_server detected after %d seconds; watchdog disarmed",
          elapsed);
      close(kmsg_fd);
      _exit(0);
    }
    if (elapsed == 10 || elapsed == 30 || elapsed == 60) {
      char phase[64];
      (void)snprintf(phase, sizeof(phase), "watchdog +%d seconds", elapsed);
      DumpState(phase);
    }
  }

  DumpState("watchdog timeout");
  RebootToRecovery();
}
