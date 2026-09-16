/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <linux/reboot.h>
#include <limits.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define DIAG_PREFIX "flourite-first-stage: "
#define WATCHDOG_TIMEOUT_SECONDS 90
#define OOPS_DISCOVERY_ATTEMPTS 200
#define OOPS_DISCOVERY_DELAY_US 100000
#define OOPS_MAX_PARTITION_SIZE (16ULL * 1024ULL * 1024ULL)
#define OOPS_RECORD_SIZE (2ULL * 1024ULL * 1024ULL)
#define OOPS_HEADER_SIZE 4096ULL
#define MTDOOPS_MAGIC_V1 UINT32_C(0x5d005d00)
#define MTDOOPS_MAGIC_V2 UINT32_C(0x5d005e00)
#define PERSISTENT_MARKER "FLOURITE_FIRST_STAGE_DIAG_V10"
#define EARLY_OOPS_NODE ".flourite-first-stage-oops"

static int kmsg_fd = -1;
static int kmsg_read_fd = -1;
static int oops_fd = -1;
static int proc_root_fd = -1;
static int dev_root_fd = -1;
static int sys_class_block_fd = -1;
static int sys_dev_block_fd = -1;
static uint64_t oops_record_base;
static uint64_t oops_write_offset;
static uint32_t oops_sequence;
static unsigned int oops_record_index;
static bool oops_log_truncated;
static bool second_stage_logged;
static bool adbd_logged;
static bool system_server_logged;
static bool direct_persistent_log;
static bool direct_persistent_log_write;

static void PersistentAppend(const void *buffer, size_t length);

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

  // /dev/kmsg rate-limits bursts from userspace. Detailed process snapshots
  // are the very lines most likely to be dropped, so write those straight to
  // the validated persistent record once it is available.
  if (direct_persistent_log && oops_fd >= 0 && !oops_log_truncated &&
      !direct_persistent_log_write) {
    direct_persistent_log_write = true;
    PersistentAppend(message, length);
    direct_persistent_log_write = false;
    return;
  }
  (void)write(kmsg_fd, message, length);
}

static ssize_t ReadSmallFileAt(int directory_fd, const char *path, char *buffer,
                               size_t capacity) {
  if (capacity < 2) {
    errno = EINVAL;
    return -1;
  }

  int fd = openat(directory_fd, path, O_RDONLY | O_CLOEXEC);
  if (fd == -1) {
    return -1;
  }

  ssize_t bytes;
  do {
    bytes = read(fd, buffer, capacity - 1);
  } while (bytes < 0 && errno == EINTR);
  close(fd);

  if (bytes < 0) {
    return -1;
  }
  buffer[bytes] = '\0';
  return bytes;
}

static bool HasExactLine(const char *buffer, const char *wanted) {
  size_t wanted_length = strlen(wanted);
  const char *line = buffer;

  while (*line != '\0') {
    const char *line_end = strchr(line, '\n');
    size_t line_length =
        line_end == NULL ? strlen(line) : (size_t)(line_end - line);
    if (line_length == wanted_length &&
        memcmp(line, wanted, wanted_length) == 0) {
      return true;
    }
    if (line_end == NULL) {
      break;
    }
    line = line_end + 1;
  }
  return false;
}

static bool WriteAllAt(int fd, const void *buffer, size_t length,
                       uint64_t offset) {
  const uint8_t *bytes = buffer;
  size_t written = 0;

  while (written < length) {
    ssize_t result = pwrite(fd, bytes + written, length - written,
                            (off_t)(offset + written));
    if (result < 0 && errno == EINTR) {
      continue;
    }
    if (result <= 0) {
      return false;
    }
    written += (size_t)result;
  }
  return true;
}

static int TryOpenOopsDeviceAt(int directory_fd, const char *path,
                               uint64_t *partition_size) {
  int fd = openat(directory_fd, path, O_RDWR | O_CLOEXEC);
  if (fd == -1) {
    return -1;
  }

  struct stat info;
  if (fstat(fd, &info) != 0 || !S_ISBLK(info.st_mode)) {
    close(fd);
    errno = ENOTBLK;
    return -1;
  }

  char uevent_path[128];
  int validation_root = sys_dev_block_fd;
  int path_length;
  if (validation_root >= 0) {
    path_length = snprintf(uevent_path, sizeof(uevent_path), "%u:%u/uevent",
                           (unsigned int)major(info.st_rdev),
                           (unsigned int)minor(info.st_rdev));
  } else {
    validation_root = AT_FDCWD;
    path_length = snprintf(
        uevent_path, sizeof(uevent_path), "/sys/dev/block/%u:%u/uevent",
        (unsigned int)major(info.st_rdev), (unsigned int)minor(info.st_rdev));
  }
  if (path_length < 0 || (size_t)path_length >= sizeof(uevent_path)) {
    close(fd);
    errno = ENAMETOOLONG;
    return -1;
  }

  char uevent[4096];
  // Never trust the fallback block-node number by itself. Variants may assign
  // UFS partitions differently, so the kernel's GPT name must also match.
  if (ReadSmallFileAt(validation_root, uevent_path, uevent, sizeof(uevent)) <
          0 ||
      !HasExactLine(uevent, "PARTNAME=oops")) {
    close(fd);
    errno = ENODEV;
    return -1;
  }

  uint64_t bytes = 0;
  if (ioctl(fd, BLKGETSIZE64, &bytes) != 0 || bytes < OOPS_RECORD_SIZE ||
      bytes > OOPS_MAX_PARTITION_SIZE || bytes % OOPS_RECORD_SIZE != 0) {
    close(fd);
    errno = EFBIG;
    return -1;
  }

  *partition_size = bytes;
  return fd;
}

static int TryOpenOopsDevice(const char *path, uint64_t *partition_size) {
  return TryOpenOopsDeviceAt(AT_FDCWD, path, partition_size);
}

static bool ParseDeviceNumber(const char *text, dev_t *device_number) {
  char *separator = NULL;
  errno = 0;
  unsigned long major_number = strtoul(text, &separator, 10);
  if (errno != 0 || separator == text || *separator != ':' ||
      major_number > UINT_MAX) {
    return false;
  }

  char *end = NULL;
  const char *minor_text = separator + 1;
  errno = 0;
  unsigned long minor_number = strtoul(minor_text, &end, 10);
  if (errno != 0 || end == minor_text || minor_number > UINT_MAX) {
    return false;
  }
  while (*end == '\n' || *end == '\r' || *end == ' ' || *end == '\t') {
    ++end;
  }
  if (*end != '\0') {
    return false;
  }

  dev_t parsed = makedev((unsigned int)major_number,
                         (unsigned int)minor_number);
  if ((unsigned long)major(parsed) != major_number ||
      (unsigned long)minor(parsed) != minor_number) {
    return false;
  }
  *device_number = parsed;
  return true;
}

static int TryOpenEarlyOopsDevice(int block_directory_fd,
                                  const char *block_name,
                                  uint64_t *partition_size,
                                  char *selected_path,
                                  size_t selected_path_size) {
  if (dev_root_fd < 0) {
    errno = ENOENT;
    return -1;
  }

  char dev_attribute[256];
  int length = snprintf(dev_attribute, sizeof(dev_attribute), "%s/dev",
                        block_name);
  if (length < 0 || (size_t)length >= sizeof(dev_attribute)) {
    errno = ENAMETOOLONG;
    return -1;
  }

  char device_text[64];
  if (ReadSmallFileAt(block_directory_fd, dev_attribute, device_text,
                      sizeof(device_text)) < 0) {
    return -1;
  }

  dev_t device_number;
  if (!ParseDeviceNumber(device_text, &device_number)) {
    errno = EINVAL;
    return -1;
  }

  bool created = false;
  struct stat existing;
  if (fstatat(dev_root_fd, EARLY_OOPS_NODE, &existing,
              AT_SYMLINK_NOFOLLOW) == 0) {
    if (!S_ISBLK(existing.st_mode) || existing.st_rdev != device_number) {
      errno = EEXIST;
      return -1;
    }
  } else if (errno == ENOENT) {
    if (mknodat(dev_root_fd, EARLY_OOPS_NODE, S_IFBLK | 0600,
                device_number) != 0) {
      return -1;
    }
    created = true;
  } else {
    return -1;
  }

  int fd =
      TryOpenOopsDeviceAt(dev_root_fd, EARLY_OOPS_NODE, partition_size);
  int saved_errno = errno;
  if (created && unlinkat(dev_root_fd, EARLY_OOPS_NODE, 0) != 0) {
    Log("failed to unlink private oops node: %s", strerror(errno));
  }
  errno = saved_errno;

  if (fd >= 0) {
    (void)snprintf(selected_path, selected_path_size,
                   "/dev/%s (sysfs %s)", EARLY_OOPS_NODE, block_name);
  }
  return fd;
}

static int ScanForOopsDevice(uint64_t *partition_size, char *selected_path,
                             size_t selected_path_size) {
  int block_directory_fd;
  if (sys_class_block_fd >= 0) {
    block_directory_fd = openat(sys_class_block_fd, ".",
                                O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  } else {
    block_directory_fd =
        open("/sys/class/block", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  }
  if (block_directory_fd < 0) {
    return -1;
  }

  DIR *directory = fdopendir(block_directory_fd);
  if (directory == NULL) {
    close(block_directory_fd);
    return -1;
  }

  int selected_fd = -1;
  struct dirent *entry;
  while ((entry = readdir(directory)) != NULL) {
    if (entry->d_name[0] == '.') {
      continue;
    }

    char uevent_path[256];
    int length = snprintf(uevent_path, sizeof(uevent_path),
                          "%s/uevent", entry->d_name);
    if (length < 0 || (size_t)length >= sizeof(uevent_path)) {
      continue;
    }

    char uevent[4096];
    if (ReadSmallFileAt(dirfd(directory), uevent_path, uevent,
                        sizeof(uevent)) < 0 ||
        !HasExactLine(uevent, "PARTNAME=oops")) {
      continue;
    }

    char device_path[256];
    length = snprintf(device_path, sizeof(device_path), "/dev/block/%s",
                      entry->d_name);
    if (length < 0 || (size_t)length >= sizeof(device_path)) {
      continue;
    }

    selected_fd = TryOpenOopsDevice(device_path, partition_size);
    if (selected_fd >= 0) {
      (void)snprintf(selected_path, selected_path_size, "%s", device_path);
      break;
    }

    selected_fd = TryOpenEarlyOopsDevice(
        dirfd(directory), entry->d_name, partition_size, selected_path,
        selected_path_size);
    if (selected_fd >= 0) {
      break;
    }
  }

  closedir(directory);
  return selected_fd;
}

static int OpenOopsPartition(uint64_t *partition_size, char *selected_path,
                             size_t selected_path_size) {
  static const char *const candidates[] = {
      "/dev/block/by-name/oops",
      "/dev/block/bootdevice/by-name/oops",
      "/dev/block/sda26",
  };

  for (int attempt = 0; attempt < OOPS_DISCOVERY_ATTEMPTS; ++attempt) {
    for (size_t index = 0; index < sizeof(candidates) / sizeof(candidates[0]);
         ++index) {
      int fd = TryOpenOopsDevice(candidates[index], partition_size);
      if (fd >= 0) {
        (void)snprintf(selected_path, selected_path_size, "%s",
                       candidates[index]);
        return fd;
      }
    }

    int fd =
        ScanForOopsDevice(partition_size, selected_path, selected_path_size);
    if (fd >= 0) {
      return fd;
    }
    usleep(OOPS_DISCOVERY_DELAY_US);
  }

  return -1;
}

struct MtdOopsHeader {
  uint32_t sequence;
  uint32_t magic;
  int64_t timestamp;
};

static bool IsNewerSequence(uint32_t candidate, uint32_t current) {
  if (candidate < UINT32_C(0x40000000) && current > UINT32_C(0xc0000000)) {
    return true;
  }
  if (candidate > current && candidate < UINT32_C(0xc0000000)) {
    return true;
  }
  return candidate > current && candidate > UINT32_C(0xc0000000) &&
         current > UINT32_C(0x80000000);
}

static void SelectNextOopsRecord(uint64_t partition_size) {
  unsigned int record_count = (unsigned int)(partition_size / OOPS_RECORD_SIZE);
  uint32_t newest_sequence = UINT32_MAX;
  unsigned int newest_index = 0;

  for (unsigned int index = 0; index < record_count; ++index) {
    struct MtdOopsHeader header;
    ssize_t bytes = pread(oops_fd, &header, sizeof(header),
                          (off_t)((uint64_t)index * OOPS_RECORD_SIZE));
    if (bytes != (ssize_t)sizeof(header) ||
        (header.magic != MTDOOPS_MAGIC_V1 &&
         header.magic != MTDOOPS_MAGIC_V2) ||
        header.sequence == UINT32_MAX) {
      continue;
    }

    if (newest_sequence == UINT32_MAX ||
        IsNewerSequence(header.sequence, newest_sequence)) {
      newest_sequence = header.sequence;
      newest_index = index;
    }
  }

  if (newest_sequence == UINT32_MAX) {
    oops_record_index = 0;
    oops_sequence = 1;
  } else {
    oops_record_index = (newest_index + 1U) % record_count;
    oops_sequence = newest_sequence + 1U;
    if (oops_sequence == UINT32_MAX) {
      oops_sequence = 0;
    }
  }
  // Use the same next-record policy as Xiaomi's mtdoops driver. Keeping a
  // valid sequence/magic header prevents a later stock logger from treating
  // this record as empty and immediately overwriting it.
  oops_record_base = (uint64_t)oops_record_index * OOPS_RECORD_SIZE;
}

static bool EraseSelectedOopsRecord(void) {
  uint8_t erased[4096];
  memset(erased, 0xff, sizeof(erased));

  for (uint64_t offset = 0; offset < OOPS_RECORD_SIZE;
       offset += sizeof(erased)) {
    if (!WriteAllAt(oops_fd, erased, sizeof(erased),
                    oops_record_base + offset)) {
      return false;
    }
  }
  return fdatasync(oops_fd) == 0;
}

static void UpdatePersistentHeader(const char *status, int elapsed) {
  if (oops_fd < 0) {
    return;
  }

  uint8_t header[OOPS_HEADER_SIZE];
  memset(header, 0, sizeof(header));

  struct timespec realtime = {0, 0};
  (void)clock_gettime(CLOCK_REALTIME, &realtime);
  int64_t timestamp = (int64_t)realtime.tv_sec * INT64_C(1000000000) +
                      (int64_t)realtime.tv_nsec;
  memcpy(header, &oops_sequence, sizeof(oops_sequence));
  uint32_t magic = MTDOOPS_MAGIC_V2;
  memcpy(header + sizeof(oops_sequence), &magic, sizeof(magic));
  memcpy(header + sizeof(oops_sequence) + sizeof(magic), &timestamp,
         sizeof(timestamp));

  (void)snprintf((char *)header + sizeof(struct MtdOopsHeader),
                 sizeof(header) - sizeof(struct MtdOopsHeader),
                 PERSISTENT_MARKER
                 "\nversion=9\nstatus=%s\nelapsed_seconds=%d\n"
                 "record_index=%u\nsequence=%u\nlog_bytes=%llu\n"
                 "truncated=%d\nsecond_stage_seen=%d\n",
                 status, elapsed, oops_record_index, oops_sequence,
                 (unsigned long long)(oops_write_offset - OOPS_HEADER_SIZE),
                 oops_log_truncated ? 1 : 0, second_stage_logged ? 1 : 0);

  if (!WriteAllAt(oops_fd, header, sizeof(header), oops_record_base)) {
    Log("failed to update persistent header: %s", strerror(errno));
  }
}

static void PersistentAppend(const void *buffer, size_t length) {
  if (oops_fd < 0 || length == 0 || oops_log_truncated) {
    return;
  }

  uint64_t remaining = OOPS_RECORD_SIZE - oops_write_offset;
  if ((uint64_t)length > remaining) {
    length = (size_t)remaining;
    oops_log_truncated = true;
  }
  if (length == 0) {
    return;
  }

  if (!WriteAllAt(oops_fd, buffer, length,
                  oops_record_base + oops_write_offset)) {
    oops_log_truncated = true;
    Log("persistent log write failed at %llu: %s",
        (unsigned long long)oops_write_offset, strerror(errno));
    return;
  }
  oops_write_offset += length;
}

static void PersistentAppendString(const char *message) {
  PersistentAppend(message, strlen(message));
}

static bool InitializePersistentLog(void) {
  uint64_t partition_size = 0;
  char selected_path[256] = {0};
  oops_fd =
      OpenOopsPartition(&partition_size, selected_path, sizeof(selected_path));
  if (oops_fd < 0) {
    Log("persistent oops partition unavailable after %d attempts",
        OOPS_DISCOVERY_ATTEMPTS);
    return false;
  }

  SelectNextOopsRecord(partition_size);
  if (!EraseSelectedOopsRecord()) {
    Log("failed to erase oops diagnostic record %u: %s", oops_record_index,
        strerror(errno));
    close(oops_fd);
    oops_fd = -1;
    return false;
  }

  oops_write_offset = OOPS_HEADER_SIZE;
  oops_log_truncated = false;
  UpdatePersistentHeader("initializing", 0);
  PersistentAppendString("\n--- FLOURITE V10 KMSG BEGIN ---\n");
  Log("persistent logger attached to %s, size=%llu, record=%u, sequence=%u",
      selected_path, (unsigned long long)partition_size, oops_record_index,
      oops_sequence);
  return true;
}

static void PrepareKernelLogReader(void) {
  kmsg_read_fd = open("/dev/kmsg", O_RDONLY | O_NONBLOCK | O_CLOEXEC);
  if (kmsg_read_fd < 0) {
    Log("cannot open /dev/kmsg for persistent capture: %s", strerror(errno));
    return;
  }
  if (lseek(kmsg_read_fd, 0, SEEK_SET) < 0) {
    Log("cannot rewind /dev/kmsg for full capture: %s", strerror(errno));
    close(kmsg_read_fd);
    kmsg_read_fd = -1;
  }
}

static void DrainKernelLog(void) {
  if (kmsg_read_fd < 0 || oops_fd < 0) {
    return;
  }

  for (int record = 0; record < 8192; ++record) {
    char buffer[8192];
    ssize_t bytes = read(kmsg_read_fd, buffer, sizeof(buffer));
    if (bytes > 0) {
      PersistentAppend(buffer, (size_t)bytes);
      continue;
    }
    if (bytes < 0 && errno == EINTR) {
      continue;
    }
    if (bytes < 0 && errno == EPIPE) {
      PersistentAppendString("\n--- /dev/kmsg overrun; records lost ---\n");
      continue;
    }
    if (bytes < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
      char message[256];
      (void)snprintf(message, sizeof(message),
                     "\n--- /dev/kmsg read failed: %s ---\n", strerror(errno));
      PersistentAppendString(message);
      close(kmsg_read_fd);
      kmsg_read_fd = -1;
    }
    break;
  }
}

static void SyncPersistentLog(const char *status, int elapsed) {
  if (oops_fd < 0) {
    return;
  }
  DrainKernelLog();
  UpdatePersistentHeader(status, elapsed);
  if (fdatasync(oops_fd) != 0) {
    Log("failed to sync persistent log: %s", strerror(errno));
  }
}

static void DumpFileAt(const char *label, int directory_fd, const char *path,
                       size_t limit) {
  int fd = openat(directory_fd, path, O_RDONLY | O_CLOEXEC);
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

static void DumpFile(const char *label, const char *path, size_t limit) {
  DumpFileAt(label, AT_FDCWD, path, limit);
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

static void DumpLinkAt(const char *label, int directory_fd,
                       const char *path) {
  char target[512];
  ssize_t length =
      readlinkat(directory_fd, path, target, sizeof(target) - 1);
  if (length < 0) {
    Log("%s unavailable: %s", label, strerror(errno));
    return;
  }
  target[length] = '\0';
  Log("%s: %s", label, target);
}

static void DumpLink(const char *label, const char *path) {
  DumpLinkAt(label, AT_FDCWD, path);
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

static bool IsInterestingProcess(const char *name) {
  static const char *const names[] = {
      "init",
      "vold",
      "vdc",
      "ueventd",
      "servicemanager",
      "vndservicemanag",
      "hwservicemanage",
      "logd",
      "apexd",
      "adbd",
      "e2fsck",
      "fsck.f2fs",
      "vold_prepare_su",
      "blkid",
      "sgdisk",
      "fsck_msdos",
      "fsck.exfat",
      "mount",
      "umount",
      "snapuserd",
      "keystore2",
  };

  for (size_t index = 0; index < sizeof(names) / sizeof(names[0]); ++index) {
    if (strcmp(name, names[index]) == 0) {
      return true;
    }
  }
  return false;
}

static void TrimLineEnd(char *buffer, ssize_t *length) {
  while (*length > 0 &&
         (buffer[*length - 1] == '\n' || buffer[*length - 1] == '\r' ||
          buffer[*length - 1] == ' ')) {
    --*length;
  }
  buffer[*length] = '\0';
}

static void DumpProcessThreadsAt(int process_fd, const char *pid,
                                 const char *process_name) {
  int scan_fd = openat(process_fd, "task", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (scan_fd < 0) {
    Log("process pid=%s comm=%s task directory unavailable: %s", pid,
        process_name, strerror(errno));
    return;
  }

  DIR *tasks = fdopendir(scan_fd);
  if (tasks == NULL) {
    close(scan_fd);
    return;
  }

  struct dirent *entry;
  while ((entry = readdir(tasks)) != NULL) {
    if (!IsNumericName(entry->d_name)) {
      continue;
    }

    char path[128];
    char label[160];
    char thread_name[64] = "unknown";
    int path_length = snprintf(path, sizeof(path), "%s/comm", entry->d_name);
    if (path_length >= 0 && (size_t)path_length < sizeof(path)) {
      ssize_t length =
          ReadSmallFileAt(dirfd(tasks), path, thread_name, sizeof(thread_name));
      if (length > 0) {
        TrimLineEnd(thread_name, &length);
      }
    }

    Log("task pid=%s tid=%s comm=%s", pid, entry->d_name, thread_name);

    path_length = snprintf(path, sizeof(path), "%s/wchan", entry->d_name);
    int label_length = snprintf(label, sizeof(label),
                                "task-%s-%s-%s-wchan", pid, entry->d_name,
                                thread_name);
    if (path_length >= 0 && (size_t)path_length < sizeof(path) &&
        label_length >= 0 && (size_t)label_length < sizeof(label)) {
      DumpFileAt(label, dirfd(tasks), path, 512);
    }

    path_length = snprintf(path, sizeof(path), "%s/stack", entry->d_name);
    label_length = snprintf(label, sizeof(label), "task-%s-%s-%s-stack", pid,
                            entry->d_name, thread_name);
    if (path_length >= 0 && (size_t)path_length < sizeof(path) &&
        label_length >= 0 && (size_t)label_length < sizeof(label)) {
      DumpFileAt(label, dirfd(tasks), path, 8192);
    }

    path_length = snprintf(path, sizeof(path), "%s/syscall", entry->d_name);
    label_length = snprintf(label, sizeof(label), "task-%s-%s-%s-syscall", pid,
                            entry->d_name, thread_name);
    if (path_length >= 0 && (size_t)path_length < sizeof(path) &&
        label_length >= 0 && (size_t)label_length < sizeof(label)) {
      DumpFileAt(label, dirfd(tasks), path, 1024);
    }
  }
  closedir(tasks);
}

static void DumpProcessStates(void) {
  bool previous_direct_persistent_log = direct_persistent_log;
  direct_persistent_log = oops_fd >= 0;

  int scan_fd;
  if (proc_root_fd >= 0) {
    scan_fd = openat(proc_root_fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  } else {
    scan_fd = open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  }
  if (scan_fd < 0) {
    Log("process scan unavailable: %s", strerror(errno));
    direct_persistent_log = previous_direct_persistent_log;
    return;
  }

  DIR *proc = fdopendir(scan_fd);
  if (proc == NULL) {
    Log("process fdopendir unavailable: %s", strerror(errno));
    close(scan_fd);
    direct_persistent_log = previous_direct_persistent_log;
    return;
  }

  Log("process scan begin");
  unsigned int scanned = 0;
  unsigned int interesting = 0;
  struct dirent *entry;
  while ((entry = readdir(proc)) != NULL) {
    if (!IsNumericName(entry->d_name)) {
      continue;
    }

    int process_fd =
        openat(dirfd(proc), entry->d_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (process_fd < 0) {
      continue;
    }

    char process_name[64];
    ssize_t length =
        ReadSmallFileAt(process_fd, "comm", process_name, sizeof(process_name));
    if (length <= 0) {
      close(process_fd);
      continue;
    }
    TrimLineEnd(process_name, &length);
    ++scanned;
    Log("process pid=%s comm=%s", entry->d_name, process_name);

    if (IsInterestingProcess(process_name)) {
      ++interesting;
      char label[128];
      int label_length = snprintf(label, sizeof(label), "process-%s-%s-cmdline",
                                  entry->d_name, process_name);
      if (label_length >= 0 && (size_t)label_length < sizeof(label)) {
        DumpFileAt(label, process_fd, "cmdline", 4096);
      }
      label_length = snprintf(label, sizeof(label), "process-%s-%s-status",
                              entry->d_name, process_name);
      if (label_length >= 0 && (size_t)label_length < sizeof(label)) {
        DumpFileAt(label, process_fd, "status", 8192);
      }
      DumpProcessThreadsAt(process_fd, entry->d_name, process_name);
    }
    close(process_fd);
  }
  Log("process scan end: scanned=%u interesting=%u", scanned, interesting);
  closedir(proc);
  direct_persistent_log = previous_direct_persistent_log;
}

static bool ProcessExists(const char *wanted_name) {
  int scan_fd;
  if (proc_root_fd >= 0) {
    scan_fd = openat(proc_root_fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  } else {
    scan_fd = open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  }
  if (scan_fd < 0) {
    return false;
  }

  DIR *proc = fdopendir(scan_fd);
  if (proc == NULL) {
    close(scan_fd);
    return false;
  }

  bool found = false;
  struct dirent *entry;
  while ((entry = readdir(proc)) != NULL) {
    if (!IsNumericName(entry->d_name)) {
      continue;
    }

    char path[128];
    int path_length = snprintf(path, sizeof(path), "%s/comm", entry->d_name);
    if (path_length < 0 || (size_t)path_length >= sizeof(path)) {
      continue;
    }

    int fd = openat(dirfd(proc), path, O_RDONLY | O_CLOEXEC);
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
  if (proc_root_fd >= 0) {
    DumpLinkAt("pid1-exe", proc_root_fd, "1/exe");
    DumpFileAt("pid1-wchan", proc_root_fd, "1/wchan", 512);
    DumpFileAt("pid1-stack", proc_root_fd, "1/stack", 8192);
    DumpFileAt("pid1-status", proc_root_fd, "1/status", 8192);
    DumpFileAt("mounts", proc_root_fd, "mounts", 16384);
    DumpFileAt("modules", proc_root_fd, "modules", 32768);
  } else {
    DumpLink("pid1-exe", "/proc/1/exe");
    DumpFile("pid1-wchan", "/proc/1/wchan", 512);
    DumpFile("pid1-stack", "/proc/1/stack", 8192);
    DumpFile("pid1-status", "/proc/1/status", 8192);
    DumpFile("mounts", "/proc/mounts", 16384);
    DumpFile("modules", "/proc/modules", 32768);
  }
  DumpDirectory("/dev/block/mapper", 128);
  DumpDirectory("/dev/block/by-name", 192);
  DumpDirectory("/dev/block/bootdevice/by-name", 192);
  DumpProcessStates();
}

static bool SecondStageStarted(void) {
  if (proc_root_fd >= 0) {
    char target[256];
    ssize_t length =
        readlinkat(proc_root_fd, "1/exe", target, sizeof(target) - 1);
    if (length >= 0) {
      target[length] = '\0';
      if (strcmp(target, "/system/bin/init") == 0) {
        return true;
      }
    }
  }

  struct stat info;
  return stat("/dev/socket/property_service", &info) == 0;
}

static void OpenPreservedNamespaceFds(void) {
  proc_root_fd = open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  dev_root_fd = open("/dev", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  sys_class_block_fd =
      open("/sys/class/block", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  sys_dev_block_fd =
      open("/sys/dev/block", O_RDONLY | O_DIRECTORY | O_CLOEXEC);

  if (proc_root_fd < 0 || dev_root_fd < 0 || sys_class_block_fd < 0 ||
      sys_dev_block_fd < 0) {
    Log("preserved namespace fds: proc=%d dev=%d class-block=%d dev-block=%d",
        proc_root_fd, dev_root_fd, sys_class_block_fd, sys_dev_block_fd);
  }
}

static void ClosePreservedNamespaceFds(void) {
  int *const descriptors[] = {
      &proc_root_fd,
      &dev_root_fd,
      &sys_class_block_fd,
      &sys_dev_block_fd,
  };

  for (size_t index = 0;
       index < sizeof(descriptors) / sizeof(descriptors[0]); ++index) {
    if (*descriptors[index] >= 0) {
      close(*descriptors[index]);
      *descriptors[index] = -1;
    }
  }
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
  SyncPersistentLog("timeout-rebooting", WATCHDOG_TIMEOUT_SECONDS);
  sync();

  for (;;) {
    errno = 0;
    long result = syscall(SYS_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2,
                          LINUX_REBOOT_CMD_RESTART2, "recovery");
    Log("reboot syscall returned %ld: %s", result, strerror(errno));
    SyncPersistentLog("reboot-failed", WATCHDOG_TIMEOUT_SECONDS);
    sleep(5);
  }
}

int main(void) {
  kmsg_fd = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
  if (kmsg_fd == -1) {
    return 1;
  }

  bool recovery_mode = RecoveryMode();
  if (!recovery_mode) {
    OpenPreservedNamespaceFds();
    PrepareKernelLogReader();
  }

  Log("diagnostic hook V10 started, pid=%d", getpid());
  DumpFile("cmdline", "/proc/cmdline", 16384);
  DumpFile("bootconfig", "/proc/bootconfig", 32768);
  DumpState("before first-stage mounts");

  if (recovery_mode) {
    Log("recovery mode detected; watchdog disabled");
    ReleaseFirstStageInit();
    close(kmsg_fd);
    return 0;
  }

  int ready_pipe[2] = {-1, -1};
  if (pipe(ready_pipe) != 0) {
    Log("failed to create watchdog readiness pipe: %s", strerror(errno));
  }

  pid_t watchdog = fork();
  if (watchdog < 0) {
    Log("failed to fork watchdog: %s", strerror(errno));
    if (ready_pipe[0] >= 0) {
      close(ready_pipe[0]);
      close(ready_pipe[1]);
    }
    ReleaseFirstStageInit();
    if (kmsg_read_fd >= 0) {
      close(kmsg_read_fd);
    }
    ClosePreservedNamespaceFds();
    close(kmsg_fd);
    return 1;
  }
  if (watchdog > 0) {
    if (ready_pipe[0] >= 0) {
      close(ready_pipe[1]);
      char ready = '\0';
      ssize_t bytes;
      do {
        bytes = read(ready_pipe[0], &ready, sizeof(ready));
      } while (bytes < 0 && errno == EINTR);
      close(ready_pipe[0]);
      if (bytes != 1 || ready != 'R') {
        Log("watchdog child did not confirm readiness");
      }
    }
    Log("watchdog armed for %d seconds, pid=%d", WATCHDOG_TIMEOUT_SECONDS,
        watchdog);
    ReleaseFirstStageInit();
    if (kmsg_read_fd >= 0) {
      close(kmsg_read_fd);
    }
    ClosePreservedNamespaceFds();
    close(kmsg_fd);
    return 0;
  }

  if (ready_pipe[0] >= 0) {
    close(ready_pipe[0]);
  }
  (void)signal(SIGHUP, SIG_IGN);
  if (setsid() < 0) {
    Log("watchdog setsid failed: %s", strerror(errno));
  }
  if (ready_pipe[1] >= 0) {
    const char ready = 'R';
    ssize_t bytes;
    do {
      bytes = write(ready_pipe[1], &ready, sizeof(ready));
    } while (bytes < 0 && errno == EINTR);
    close(ready_pipe[1]);
  }

  (void)InitializePersistentLog();
  DumpState("persistent logger online");
  SyncPersistentLog("watchdog-active", 0);

  for (int elapsed = 1; elapsed <= WATCHDOG_TIMEOUT_SECONDS; ++elapsed) {
    sleep(1);
    DrainKernelLog();
    if (!second_stage_logged && SecondStageStarted()) {
      Log("second-stage init detected after %d seconds", elapsed);
      second_stage_logged = true;
      SyncPersistentLog("second-stage-seen", elapsed);
    }
    if (!adbd_logged && ProcessExists("adbd")) {
      Log("adbd detected after %d seconds; capture remains armed", elapsed);
      adbd_logged = true;
    }
    if (!system_server_logged && ProcessExists("system_server")) {
      Log("system_server detected after %d seconds; capture remains armed",
          elapsed);
      system_server_logged = true;
    }
    if (elapsed == 10 || elapsed == 30 || elapsed == 60) {
      char phase[64];
      (void)snprintf(phase, sizeof(phase), "watchdog +%d seconds", elapsed);
      DumpState(phase);
    }
    // A fatal PID 1 path waits only five seconds before rebooting to the
    // configured target. Persist every second so the final LOG(FATAL), service
    // crash loop and SELinux denial survive that reboot.
    SyncPersistentLog("watchdog-active", elapsed);
  }

  DumpState("watchdog timeout");
  RebootToRecovery();
}
