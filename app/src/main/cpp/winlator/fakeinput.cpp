#include <cstring>
#include <string>
#include <vector>
#include <iostream>
#include <unordered_map>
#include <memory>
#include <fstream>
#include <algorithm>
#include <mutex>

#include <fcntl.h>
#include <dirent.h>
#include <stdio.h>
#include <sys/types.h>
#include <unistd.h>
#include <dlfcn.h>
#include <poll.h>
#include <stdarg.h>
#include <string.h>
#include <stdbool.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/uio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <linux/input.h>

// DroidDeck-deck: header untuk emulasi pad Steam Deck
#include <string>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <dirent.h>
#include <unordered_map>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <pthread.h>
#include <time.h>
#include <limits.h>
#include <linux/hidraw.h>
#include <cerrno>
#define EXPORT __attribute__((visibility("default"))) extern "C"

std::unordered_map<int, const char *> controller_map;
static bool initialized = false;
static const char *hook_dir = nullptr;
static bool vibration_enabled = true;
volatile sig_atomic_t stop_flag = 0;

static int (*my_open)(const char *, int, ...) = nullptr;
static int (*my_openat)(int, const char *, int, ...) = nullptr;
static int (*my_stat)(const char *, struct stat *) = nullptr;
static int (*my_fstat)(int fd, struct stat *buf) = nullptr;
static int (*my_scandir)(const char *, struct dirent***, int(*)(const struct dirent *), int(*)(const struct dirent**, const struct dirent**));
static int (*my_inotify_add_watch)(int, const char *, uint32_t);
static int (*my_close)(int);
static ssize_t (*my_write)(int, const void *, size_t) = nullptr;

namespace Logger {
	int log_enabled;

	void init() {
		log_enabled = getenv("FAKE_EVDEV_LOG") && atoi(getenv("FAKE_EVDEV_LOG"));
	}

	void log(const char *message, ...) {
		if (!log_enabled)
			return;

		va_list args;
		va_start(args, message);
		vfprintf(stderr, message, args);
		va_end(args);

		std::cerr.flush();
	}
}

void handle_sigint(int sig)  { 
    stop_flag = 1;
} 

void setup_signal_handler() {
    if (!initialized) {
        signal(SIGINT, handle_sigint);
        initialized = true;
    }
}

static std::unordered_map<int, struct ff_effect> ff_effects;
static int next_ff_id = 0;

void send_vibration(int strong, int weak, uint16_t duration_ms, uint16_t slot) {
  if (!vibration_enabled)
    return;

  int sock = socket(AF_UNIX, SOCK_STREAM, 0);
  if (sock < 0)
    return;

  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  const char *name = "winlator_vibration";
  memcpy(addr.sun_path + 1, name, strlen(name));
  socklen_t addrlen = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name);

  if (connect(sock, (struct sockaddr *)&addr, addrlen) < 0) {
    syscall(SYS_close, sock);
    return;
  }

  uint16_t data[4];
  data[0] = (uint16_t)strong;
  data[1] = (uint16_t)weak;
  data[2] = duration_ms;
  data[3] = slot;
  send(sock, data, sizeof(data), 0);
  syscall(SYS_close, sock);
}

static void deck_snapshot_env();

__attribute__((constructor))
static void library_init() {
	if (!hook_dir)
		hook_dir = getenv("FAKE_EVDEV_DIR") ? getenv("FAKE_EVDEV_DIR") : "/data/data/com.termux/files/home/fake-input";

	vibration_enabled = getenv("FAKE_EVDEV_VIBRATION") && atoi(getenv("FAKE_EVDEV_VIBRATION"));
	Logger::init();
	deck_snapshot_env();  // salin env Deck sekarang; environ proses Wine bisa berubah/ dikosongkan setelah ini
	// Diagnostik: tampilkan apakah env Deck sampai ke proses ini (tiap proses Wine mencetak sekali)
	Logger::log("deck: init pid=%d FAKE_EVDEV_DECK=%s STATE=%s DEVDIR=%s HIDRAW=%s\n", (int) getpid(),
		getenv("FAKE_EVDEV_DECK") ? getenv("FAKE_EVDEV_DECK") : "(unset)",
		getenv("FAKE_DECK_STATE") ? "set" : "(unset)",
		getenv("FAKE_DECK_DEVDIR") ? "set" : "(unset)",
		getenv("PROTON_ENABLE_HIDRAW") ? getenv("PROTON_ENABLE_HIDRAW") : "(unset)");
}
  
__attribute__((visibility("hidden"))) 
char *from_real_to_fake_path(const char *pathname) {
	const char *event = strrchr(pathname, '/') + 1;
	char *fake_path;
	asprintf(&fake_path, "%s/%s", hook_dir, event);
	return fake_path;
}

__attribute__((visibility("hidden")))
const char *get_event(const char *pathname) {
	const char *event = strrchr(pathname, '/') + 1;
	return event;
}

__attribute__((visibility("hidden")))
int get_event_number(const char *event) {
    int event_number = atoi(event + strlen(event) - 1);
    return event_number;
}

// ---- DroidDeck-deck: pad sebagai Steam Deck controller (28DE:1205) lewat /dev/hidraw16 ----
//
// Port dari fakeinput_steam.cpp DroidDeck. Perbedaan: laporan dibangun dari file state kecil
// (FAKE_DECK_STATE, ditulis DDDeck.java di bawah seqlock), bukan dari ring evdev; pohon sysfs
// ada di FAKE_DECK_SYSFS_DIR dan dilayani lewat hook path, bukan bind proot.
static constexpr const char *DECK_NODE = "/dev/hidraw16";
static constexpr unsigned DECK_MAJOR = 240, DECK_MINOR = 16;
static constexpr int DECK_REPORT_BYTES = 64;
static constexpr int DECK_INTERVAL_US = 4000;
static constexpr const char *DECK_NAME = "Valve Software Steam Deck Controller";
static constexpr const char *DECK_SERIAL = "DROIDDECK0001";
static constexpr uint32_t DECK_STATE_MAGIC = 0x31534B44;  // "DKS1"
static constexpr size_t DECK_STATE_SIZE = 64;
static constexpr long DECK_SYSFS_MAGIC = 0x62656572;

// InputPlumber CONTROLLER_DESCRIPTOR: satu laporan input vendor 64 byte, satu feature report 64 byte.
static const uint8_t kDeckDesc[] = {
    0x06, 0xff, 0xff, 0x09, 0x01, 0xa1, 0x01, 0x09, 0x02, 0x09, 0x03, 0x15, 0x00,
    0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x81, 0x02, 0x09, 0x06, 0x09, 0x07,
    0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0};

struct DeckFd {
    int peer;
    const uint8_t *state;
    uint8_t pending;
};

static std::mutex &deck_mutex() { static auto *m = new std::mutex(); return *m; }
static std::unordered_map<int, DeckFd *> &deck_fds() { static auto *m = new std::unordered_map<int, DeckFd *>(); return *m; }

// Snapshot env Deck yang diambil saat library dimuat. Log menunjukkan FAKE_EVDEV_DECK=1 saat constructor
// berjalan, tetapi deck_enabled()=0 ketika winebus memanggil opendir(/dev): environ proses Wine tidak
// bisa dipercaya setelah startup, jadi nilai awal itulah yang dipakai.
static const char *const DECK_ENV_NAMES[] = {"FAKE_EVDEV_DECK", "FAKE_DECK_STATE", "FAKE_DECK_SYSFS_DIR",
                                              "FAKE_DECK_DEVDIR", "FAKE_DECK_STATUS"};
static constexpr int DECK_ENV_COUNT = sizeof(DECK_ENV_NAMES) / sizeof(DECK_ENV_NAMES[0]);
static char *deck_env_snapshot[DECK_ENV_COUNT];
static bool deck_env_snapped = false;

static void deck_snapshot_env() {
    for (int i = 0; i < DECK_ENV_COUNT; i++) {
        const char *v = getenv(DECK_ENV_NAMES[i]);
        deck_env_snapshot[i] = (v && *v) ? strdup(v) : nullptr;
    }
    deck_env_snapped = true;
}

static const char *deck_env(const char *name) {
    if (deck_env_snapped) {
        for (int i = 0; i < DECK_ENV_COUNT; i++)
            if (!strcmp(name, DECK_ENV_NAMES[i]) && deck_env_snapshot[i]) return deck_env_snapshot[i];
    }
    const char *v = getenv(name);
    return v && *v ? v : nullptr;
}

// ---- DroidDeck-bp: penghitung status untuk layar uji (FAKE_DECK_STATUS, 16 x u32) ----
enum { DS_SYSFS = 1, DS_OPEN = 2, DS_CLOSE = 3, DS_FSET = 4, DS_FGET = 5, DS_UNHANDLED = 6, DS_REPORTS = 7, DS_LASTFEATURE = 8 };

static uint32_t *deck_status_map() {
    static uint32_t *map = []() -> uint32_t * {
        const char *path = deck_env("FAKE_DECK_STATUS");
        if (!path || !*path) return nullptr;
        int fd = syscall(SYS_openat, AT_FDCWD, path, O_RDWR | O_CLOEXEC);
        if (fd < 0) return nullptr;
        void *m = mmap(nullptr, 64, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        syscall(SYS_close, fd);
        return m == MAP_FAILED ? nullptr : static_cast<uint32_t *>(m);
    }();
    return map;
}

static void deck_stat(int idx) {
    if (uint32_t *m = deck_status_map()) __atomic_fetch_add(&m[idx], 1u, __ATOMIC_RELAXED);
}

static void deck_stat_set(int idx, uint32_t value) {
    if (uint32_t *m = deck_status_map()) __atomic_store_n(&m[idx], value, __ATOMIC_RELAXED);
}
// ---- end DroidDeck-bp status ----

static bool deck_enabled() {
    static int on = -1;
    if (on == 1) return true;
    const char *v = deck_env("FAKE_EVDEV_DECK");
    const int now = (v && atoi(v)) ? 1 : 0;
    if (deck_env_snapped) on = now;  // cache hanya setelah snapshot ada; sebelumnya jangan membekukan nilai 0
    return now == 1;
}

static inline void deck_put16(uint8_t *at, int v) { at[0] = v & 0xff; at[1] = (v >> 8) & 0xff; }
static inline void deck_put32(uint8_t *at, uint32_t v) { for (int i = 0; i < 4; i++) at[i] = (uint8_t)(v >> (8 * i)); }
static inline int deck_get16(const uint8_t *at) { return (int16_t)(at[0] | (at[1] << 8)); }
static inline uint32_t deck_get32(const uint8_t *at) { return at[0] | (at[1] << 8) | (at[2] << 16) | ((uint32_t)at[3] << 24); }

// Laporan state Deck (SDL controller_structs.h: ValveInReportHeader_t + SteamDeckStatePacket_t) dari
// file state di bawah seqlock. Tata letak file state (little endian):
//   0 u32 magic | 8 u64 seq (ganjil = sedang ditulis) | 16 u32 tombol rendah | 20 u32 tombol tinggi
//   24 i16 pad[4] (kiri X,Y kanan X,Y) | 32 i16 stik[4] (LX,LY,RX,RY; Y ke atas) | 40 u16 trigger[2]
//   44 u16 tekanan pad[2] | 48 i16 accel[3] | 54 i16 gyro[3]
static void deck_build_report(const uint8_t *state, uint8_t *report, uint32_t packet) {
    uint8_t s[DECK_STATE_SIZE];
    bool have = false;
    for (int attempt = 0; attempt < 4 && !have; attempt++) {
        uint64_t seq = __atomic_load_n((const uint64_t *)(state + 8), __ATOMIC_ACQUIRE);
        if (seq & 1) continue;
        memcpy(s, state, DECK_STATE_SIZE);
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (seq == __atomic_load_n((const uint64_t *)(state + 8), __ATOMIC_RELAXED)) have = true;
    }
    if (!have || deck_get32(s) != DECK_STATE_MAGIC) memset(s, 0, sizeof(s));
    memset(report, 0, DECK_REPORT_BYTES);
    report[0] = 0x01;                 // versi laporan
    report[2] = 0x09;                 // ID_CONTROLLER_DECK_STATE
    report[3] = DECK_REPORT_BYTES;
    deck_put32(report + 4, packet);
    deck_put32(report + 8, deck_get32(s + 16));   // tombol rendah
    deck_put32(report + 12, deck_get32(s + 20));  // tombol tinggi
    for (int i = 0; i < 4; i++) deck_put16(report + 16 + i * 2, deck_get16(s + 24 + i * 2));  // pad
    for (int i = 0; i < 3; i++) {
        deck_put16(report + 24 + i * 2, deck_get16(s + 48 + i * 2));  // accel
        deck_put16(report + 30 + i * 2, deck_get16(s + 54 + i * 2));  // gyro
    }
    deck_put16(report + 44, deck_get16(s + 40));  // trigger kiri
    deck_put16(report + 46, deck_get16(s + 42));  // trigger kanan
    for (int i = 0; i < 4; i++) deck_put16(report + 48 + i * 2, deck_get16(s + 32 + i * 2));  // stik
    deck_put16(report + 56, deck_get16(s + 44));  // tekanan pad kiri
    deck_put16(report + 58, deck_get16(s + 46));  // tekanan pad kanan
}

static void *deck_report_thread(void *arg) {
    DeckFd *deck = static_cast<DeckFd *>(arg);
    uint32_t packet = 0;
    for (;;) {
        uint8_t report[DECK_REPORT_BYTES];
        deck_build_report(deck->state, report, ++packet);
        if ((packet & 63) == 0) deck_stat_set(DS_REPORTS, packet);  // DroidDeck-bp
        // Pembaca yang tertinggal kehilangan laporan, bukan menerima yang basi; pembaca yang menutup mengakhiri aliran.
        if (send(deck->peer, report, sizeof(report), MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
            errno != EAGAIN && errno != EWOULDBLOCK)
            break;
        struct timespec interval = {0, DECK_INTERVAL_US * 1000L};
        nanosleep(&interval, nullptr);
    }
    syscall(SYS_close, deck->peer);
    return nullptr;
}

static DeckFd *deck_find(int fd) {
    std::lock_guard<std::mutex> guard(deck_mutex());
    auto it = deck_fds().find(fd);
    return it == deck_fds().end() ? nullptr : it->second;
}

static void deck_forget(int fd) {
    std::lock_guard<std::mutex> guard(deck_mutex());
    if (deck_fds().erase(fd)) deck_stat(DS_CLOSE);  // DroidDeck-bp; DeckFd sengaja bocor kecil: thread laporan masih memakainya sampai peer menutup
}

static int open_deck_hidraw(int flags) {
    const char *state_path = deck_env("FAKE_DECK_STATE");
    if (!state_path) {
        Logger::log("deck: %s refused: FAKE_DECK_STATE not set\n", DECK_NODE);
        errno = ENODEV;
        return -1;
    }
    int state_fd = syscall(SYS_openat, AT_FDCWD, state_path, O_RDONLY | O_CLOEXEC);
    if (state_fd < 0) {
        Logger::log("deck: %s refused: state file %s not openable (%s)\n", DECK_NODE, state_path, strerror(errno));
        errno = ENODEV;
        return -1;
    }
    void *map = mmap(nullptr, DECK_STATE_SIZE, PROT_READ, MAP_SHARED, state_fd, 0);
    syscall(SYS_close, state_fd);
    if (map == MAP_FAILED) {
        Logger::log("deck: %s refused: state file could not be mapped\n", DECK_NODE);
        errno = ENODEV;
        return -1;
    }
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) < 0) {
        munmap(map, DECK_STATE_SIZE);
        return -1;
    }
    int buffer = DECK_REPORT_BYTES * 4;  // hanya beberapa laporan, agar yang dibaca tak pernah lama
    setsockopt(pair[1], SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer));
    if (!(flags & O_CLOEXEC)) fcntl(pair[0], F_SETFD, 0);
    if (flags & O_NONBLOCK) fcntl(pair[0], F_SETFL, fcntl(pair[0], F_GETFL) | O_NONBLOCK);
    DeckFd *deck = new DeckFd{pair[1], static_cast<const uint8_t *>(map), 0};
    {
        std::lock_guard<std::mutex> guard(deck_mutex());
        deck_fds()[pair[0]] = deck;
    }
    pthread_t thread;
    if (pthread_create(&thread, nullptr, deck_report_thread, deck) != 0) {
        deck_forget(pair[0]);
        syscall(SYS_close, pair[0]);
        syscall(SYS_close, pair[1]);
        munmap(map, DECK_STATE_SIZE);
        delete deck;
        errno = ENOMEM;
        return -1;
    }
    pthread_detach(thread);
    Logger::log("deck: %s opened as fd %d\n", DECK_NODE, pair[0]);
    deck_stat(DS_OPEN);  // DroidDeck-bp
    return pair[0];
}

static int deck_copy_string(unsigned op, void *argp, const char *value) {
    size_t size = _IOC_SIZE(op);
    if (!argp || !size) return 0;
    snprintf(static_cast<char *>(argp), size, "%s", value);
    return (int)std::min(size, strlen(value) + 1);
}

// Feature report: id 0 dulu, lalu pesan Valve (tipe, panjang, payload); jawaban seperti InputPlumber untuk Deck virtual.
static int deck_feature(DeckFd &deck, unsigned op, uint8_t *buf, bool set) {
    size_t size = _IOC_SIZE(op);
    if (!buf || size < 2) {
        errno = EINVAL;
        return -1;
    }
    if (set) {
        deck.pending = buf[1];
        Logger::log("deck: feature 0x%02x set\n", buf[1]);
        deck_stat(DS_FSET);  // DroidDeck-bp
        deck_stat_set(DS_LASTFEATURE, buf[1]);
        return (int)size;
    }
    uint8_t reply[DECK_REPORT_BYTES + 1] = {};
    switch (deck.pending) {
    case 0x83: {  // GET_ATTRIBUTES_VALUES (tangkapan dari Deck asli)
        static const uint8_t kAttributes[] = {0x00, 0x83, 0x2d, 0x01, 0x05, 0x12, 0x00, 0x00, 0x02,
                                              0x00, 0x00, 0x00, 0x00, 0x0a, 0x2b, 0x12, 0xa9, 0x62,
                                              0x04, 0xad, 0xf1, 0xe4, 0x65, 0x09, 0x2e, 0x00, 0x00,
                                              0x00, 0x0b, 0xa0, 0x0f, 0x00, 0x00, 0x0d, 0x00, 0x00,
                                              0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x0e};
        memcpy(reply, kAttributes, sizeof(kAttributes));
        break;
    }
    case 0xAE:  // GET_STRING_ATTRIBUTE
        reply[1] = 0xAE;
        reply[2] = 0x14;
        reply[3] = 0x01;
        memcpy(reply + 4, DECK_SERIAL, strlen(DECK_SERIAL));
        break;
    case 0xBA:  // GET_CHIP_ID
        reply[1] = 0xBA;
        reply[2] = 0x11;
        memcpy(reply + 4, "DROIDDECKCHIP01", 15);
        break;
    default:
        reply[1] = deck.pending;
        break;
    }
    Logger::log("deck: feature 0x%02x read\n", deck.pending);
    deck_stat(DS_FGET);  // DroidDeck-bp
    size_t length = std::min(size, sizeof(reply));
    memcpy(buf, reply, length);
    return (int)length;
}

static int ioctl_deck(DeckFd &deck, unsigned op, void *argp) {
    if (_IOC_TYPE(op) != 'H') {
        errno = ENOTTY;
        return -1;
    }
    switch (_IOC_NR(op)) {
    case 0x01:  // HIDIOCGRDESCSIZE
        *static_cast<int *>(argp) = sizeof(kDeckDesc);
        return 0;
    case 0x02: {  // HIDIOCGRDESC: { __u32 size; __u8 value[4096]; }
        auto *desc = static_cast<uint8_t *>(argp);
        uint32_t size;
        memcpy(&size, desc, sizeof(size));
        memcpy(desc + 4, kDeckDesc, std::min<size_t>(size, sizeof(kDeckDesc)));
        return 0;
    }
    case 0x03: {  // HIDIOCGRAWINFO: { __u32 bustype; __s16 vendor, product; }
        struct { uint32_t bustype; int16_t vendor; int16_t product; } info = {3 /*BUS_USB*/, (int16_t)0x28de, (int16_t)0x1205};
        memcpy(argp, &info, sizeof(info));
        return 0;
    }
    case 0x04: return deck_copy_string(op, argp, DECK_NAME);                 // HIDIOCGRAWNAME
    case 0x05: return deck_copy_string(op, argp, "usb-droiddeck-1/input2");  // HIDIOCGRAWPHYS
    case 0x08: return deck_copy_string(op, argp, DECK_SERIAL);               // HIDIOCGRAWUNIQ
    case 0x06: return deck_feature(deck, op, static_cast<uint8_t *>(argp), true);   // HIDIOCSFEATURE
    case 0x07: return deck_feature(deck, op, static_cast<uint8_t *>(argp), false);  // HIDIOCGFEATURE
    default:
        Logger::log("deck: unhandled hidraw ioctl 0x%02x\n", _IOC_NR(op));
        deck_stat(DS_UNHANDLED);  // DroidDeck-bp
        errno = EINVAL;
        return -1;
    }
}

static bool deck_is_node(const char *path) {
    return path && deck_enabled() && !strcmp(path, DECK_NODE);
}

// v2: menjadi true setelah ada akses ke /sys atau /run/udev; hanya sesudah itu path relatif terhadap dirfd diresolusi
// lewat /proc/self/fd (libudev/sd-device menelusuri sysfs per komponen dengan openat/fstatat/readlinkat).
static std::atomic<bool> deck_hot{false};

// Pohon sysfs Deck (ditulis DDDeck.java) mencerminkan path absolutnya di bawah FAKE_DECK_SYSFS_DIR.
static bool deck_redirect(const char *path, std::string &out) {
    if (!path || path[0] != '/' || !deck_enabled()) return false;
    const char *root = deck_env("FAKE_DECK_SYSFS_DIR");
    if (!root) return false;
    if (!deck_hot.load(std::memory_order_relaxed) && (!strncmp(path, "/sys", 4) || !strncmp(path, "/run/udev", 9)))
        deck_hot.store(true, std::memory_order_relaxed);
    static const char *kPrefixes[] = {"/sys/devices/droiddeck", "/sys/class/hidraw", "/sys/bus/hid",
                                      "/sys/bus/usb", "/sys/dev/char/240:16", "/run/udev/data/c240:16"};
    for (const char *prefix : kPrefixes) {
        size_t n = strlen(prefix);
        if (!strncmp(path, prefix, n) && (path[n] == '\0' || path[n] == '/')) {
            static std::atomic<bool> noted{false};
            deck_stat(DS_SYSFS);  // DroidDeck-bp
            if (!noted.exchange(true)) Logger::log("deck: sysfs checked (%s)\n", path);
            out.assign(root);
            out += path;
            return true;
        }
    }
    return false;
}

static bool deck_under_root(const char *path) {
    const char *root = deck_env("FAKE_DECK_SYSFS_DIR");
    return root && path && !strncmp(path, root, strlen(root));
}

// Hasil yang memuat folder pohon dikembalikan ke bentuk /sys/... agar libudev menerimanya.
static size_t deck_strip_root(char *buf, size_t length) {
    const char *root = deck_env("FAKE_DECK_SYSFS_DIR");
    if (!root) return length;
    size_t n = strlen(root);
    if (length >= n && !strncmp(buf, root, n)) {
        memmove(buf, buf + n, length - n);
        return length - n;
    }
    return length;
}

// ---- DroidDeck-deck v2: /dev/hidraw16 sebagai char device, listing /dev, resolusi dirfd ----
// Mengapa: libudev/hidapi mencocokkan perangkat lewat st_rdev (240:16) dan mode S_IFCHR, dan mode
// DisableUdevd di winebus memindai direktori /dev untuk "hidraw*". Node palsu ini tidak ada di rootfs.
static void deck_fill_stat(struct stat *st) {
    memset(st, 0, sizeof(*st));
    st->st_mode = S_IFCHR | 0660;
    st->st_rdev = makedev(DECK_MAJOR, DECK_MINOR);
    st->st_nlink = 1;
    st->st_uid = getuid();
    st->st_gid = getgid();
    st->st_blksize = 4096;
    st->st_ino = 0x44444b01;
    struct timespec now;
    clock_gettime(CLOCK_REALTIME, &now);
    st->st_atim = now;
    st->st_mtim = now;
    st->st_ctim = now;
    static std::atomic<bool> noted{false};
    if (!noted.exchange(true))
        Logger::log("deck: stat %s answered as char device %u:%u\n", DECK_NODE, DECK_MAJOR, DECK_MINOR);
}

// Path absolut untuk (dirfd, path). Path relatif hanya diresolusi bila deck_hot dan dirfd bukan AT_FDCWD.
static bool deck_abs_path(int dirfd, const char *path, std::string &out) {
    if (!path) return false;
    if (path[0] == '/') { out = path; return true; }
    if (dirfd == AT_FDCWD || !deck_hot.load(std::memory_order_relaxed)) return false;
    char link[64], dir[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
    ssize_t n = syscall(SYS_readlinkat, AT_FDCWD, link, dir, sizeof(dir) - 1);
    if (n <= 0) return false;
    dir[n] = '\0';
    size_t kept = deck_strip_root(dir, (size_t)n);  // fd di pohon palsu dikembalikan ke bentuk /sys/...
    dir[kept] = '\0';
    if (dir[0] != '/') return false;
    out = dir;
    if (out.back() != '/') out += '/';
    out += path;
    return true;
}

// Listing /dev: sisipkan "hidraw16" di akhir tiap DIR* yang dibuka dari /dev.
static std::mutex &deck_dir_mutex() { static auto *m = new std::mutex(); return *m; }
static std::unordered_map<DIR *, bool> &deck_dev_dirs() { static auto *m = new std::unordered_map<DIR *, bool>(); return *m; }

static bool deck_is_dev_dir(const char *path) { return path && (!strcmp(path, "/dev") || !strcmp(path, "/dev/")); }

static void deck_dev_track(DIR *d) {
    if (!d) return;
    {
        std::lock_guard<std::mutex> guard(deck_dir_mutex());
        deck_dev_dirs()[d] = false;
    }
    static std::atomic<bool> noted{false};
    if (!noted.exchange(true)) Logger::log("deck: /dev listing will include %s\n", strrchr(DECK_NODE, '/') + 1);
}

static bool deck_dev_take(DIR *d) {
    std::lock_guard<std::mutex> guard(deck_dir_mutex());
    auto it = deck_dev_dirs().find(d);
    if (it == deck_dev_dirs().end() || it->second) return false;
    it->second = true;
    return true;
}

static void deck_fill_dirent(struct dirent *e) {
    memset(e, 0, sizeof(*e));
    e->d_ino = 0x44444b01;
    e->d_type = DT_CHR;
    e->d_reclen = sizeof(*e);
    strncpy(e->d_name, strrchr(DECK_NODE, '/') + 1, sizeof(e->d_name) - 1);
}

static int deck_scandir_inject(struct dirent ***namelist, int count, int (*filter)(const struct dirent *),
                               int (*compar)(const struct dirent **, const struct dirent **)) {
    struct dirent probe;
    deck_fill_dirent(&probe);
    if (filter && !filter(&probe)) return count;
    for (int i = 0; i < count; i++)
        if (!strcmp((*namelist)[i]->d_name, probe.d_name)) return count;
    struct dirent *copy = (struct dirent *)malloc(sizeof(probe));
    if (!copy) return count;
    memcpy(copy, &probe, sizeof(probe));
    struct dirent **grown = (struct dirent **)realloc(*namelist, sizeof(*grown) * (size_t)(count + 1));
    if (!grown) { free(copy); return count; }
    grown[count] = copy;
    *namelist = grown;
    if (compar) qsort(grown, (size_t)count + 1, sizeof(*grown), (int (*)(const void *, const void *))compar);
    return count + 1;
}

// Folder kosong pengganti /dev. Di Android (SELinux untrusted_app) opendir("/dev") dan
// inotify_add_watch("/dev") ditolak EACCES, sehingga winebus ("Unable to open /dev: Permission
// denied") tidak pernah melihat hidraw16 walau node-nya disuntikkan ke listing.
static const char *deck_dev_fallback() {
    static std::string path;
    static std::once_flag once;
    std::call_once(once, []() {
        const char *dir = deck_env("FAKE_DECK_DEVDIR");
        if (dir) {
            path = dir;
        } else if (const char *root = deck_env("FAKE_DECK_SYSFS_DIR")) {
            path = std::string(root) + "/../devdir";
        }
        if (!path.empty()) syscall(SYS_mkdirat, AT_FDCWD, path.c_str(), 0755);
    });
    return path.empty() ? nullptr : path.c_str();
}

static bool deck_dev_denied() { return errno == EACCES || errno == EPERM || errno == ENOENT; }

EXPORT DIR *opendir(const char *name) {
    static auto real = reinterpret_cast<DIR *(*)(const char *)>(dlsym(RTLD_NEXT, "opendir"));
    std::string redirected;
    // DroidDeck-devinput: winebus (direct mode) memakai opendir("/dev/input"); di Android ditolak EACCES.
    // Samakan dengan scandir/open/inotify: arahkan ke folder node evdev palsu.
    if (name && hook_dir && (!strcmp(name, "/dev/input") || !strcmp(name, "/dev/input/"))) {
        static std::atomic<bool> noted{false};
        if (!noted.exchange(true)) Logger::log("deck: opendir(/dev/input) -> %s\n", hook_dir);
        name = hook_dir;
    }
    if (deck_redirect(name, redirected)) name = redirected.c_str();
    const bool dev_dir = deck_enabled() && deck_is_dev_dir(name);
    DIR *d = real(name);
    if (deck_is_dev_dir(name)) {
        static std::atomic<int> seen{0};
        if (seen.fetch_add(1) < 3)
            Logger::log("deck: opendir(%s) -> %s (errno %d) deck_enabled=%d live_env=%s\n", name, d ? "ok" : "FAIL", d ? 0 : errno, (int) deck_enabled(), getenv("FAKE_EVDEV_DECK") ? getenv("FAKE_EVDEV_DECK") : "(unset)");
    }
    if (!d && dev_dir && deck_dev_denied()) {
        if (const char *fallback = deck_dev_fallback()) {
            d = real(fallback);
            static std::atomic<bool> noted{false};
            if (d && !noted.exchange(true)) Logger::log("deck: opendir(/dev) denied, using %s\n", fallback);
        }
    }
    if (d && dev_dir) deck_dev_track(d);
    return d;
}

EXPORT ssize_t readlink(const char *path, char *buf, size_t size) {
    static auto real = reinterpret_cast<ssize_t (*)(const char *, char *, size_t)>(dlsym(RTLD_NEXT, "readlink"));
    std::string redirected;
    bool hit = deck_redirect(path, redirected);
    ssize_t result = real(hit ? redirected.c_str() : path, buf, size);
    if (result > 0 && deck_enabled()) result = (ssize_t)deck_strip_root(buf, (size_t)result);
    return result;
}

EXPORT int lstat(const char *path, struct stat *st) {
    static auto real = reinterpret_cast<int (*)(const char *, struct stat *)>(dlsym(RTLD_NEXT, "lstat"));
    std::string redirected;
    if (st && deck_is_node(path)) { deck_fill_stat(st); return 0; }
    if (deck_redirect(path, redirected)) path = redirected.c_str();
    return real(path, st);
}

EXPORT int access(const char *path, int mode) {
    static auto real = reinterpret_cast<int (*)(const char *, int)>(dlsym(RTLD_NEXT, "access"));
    std::string redirected;
    if (deck_is_node(path)) return 0;
    if (deck_redirect(path, redirected)) path = redirected.c_str();
    return real(path, mode);
}

EXPORT char *realpath(const char *path, char *resolved) {
    static auto real = reinterpret_cast<char *(*)(const char *, char *)>(dlsym(RTLD_NEXT, "realpath"));
    std::string redirected;
    bool hit = deck_redirect(path, redirected);
    char *result = real(hit ? redirected.c_str() : path, resolved);
    if (result && hit) {
        size_t length = strlen(result);
        size_t kept = deck_strip_root(result, length);
        result[kept] = '\0';
    }
    return result;
}

// libudev (sd-device) menerima perangkat hanya bila direktorinya ada di sysfs (diperiksa dengan fstatfs).
EXPORT int statfs(const char *path, struct statfs *buf) {
    static auto real = reinterpret_cast<int (*)(const char *, struct statfs *)>(dlsym(RTLD_NEXT, "statfs"));
    std::string redirected;
    bool hit = deck_redirect(path, redirected);
    int result = real(hit ? redirected.c_str() : path, buf);
    if (hit && result == 0) buf->f_type = DECK_SYSFS_MAGIC;
    return result;
}

EXPORT int fstatfs(int fd, struct statfs *buf) {
    static auto real = reinterpret_cast<int (*)(int, struct statfs *)>(dlsym(RTLD_NEXT, "fstatfs"));
    int result = real(fd, buf);
    if (result == 0 && deck_enabled()) {
        char link[64], path[PATH_MAX];
        snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
        ssize_t length = syscall(SYS_readlinkat, AT_FDCWD, link, path, sizeof(path) - 1);
        if (length > 0) {
            path[length] = '\0';
            if (deck_under_root(path)) buf->f_type = DECK_SYSFS_MAGIC;
        }
    }
    return result;
}
// ---- end DroidDeck-deck ----

// Hook tambahan v2. Nama C++ unik + asm label = nama simbol asli, jadi tidak bentrok dengan prototipe di header.
#define DECK_HOOK extern "C" __attribute__((visibility("default")))

struct DeckStatxTs { int64_t tv_sec; uint32_t tv_nsec; int32_t reserved; };
struct DeckStatx {  // tata letak struct statx kernel (256 byte)
    uint32_t stx_mask, stx_blksize;
    uint64_t stx_attributes;
    uint32_t stx_nlink, stx_uid, stx_gid;
    uint16_t stx_mode, spare0;
    uint64_t stx_ino, stx_size, stx_blocks, stx_attributes_mask;
    DeckStatxTs stx_atime, stx_btime, stx_ctime, stx_mtime;
    uint32_t stx_rdev_major, stx_rdev_minor, stx_dev_major, stx_dev_minor;
    uint64_t stx_mnt_id;
    uint32_t stx_dio_mem_align, stx_dio_offset_align;
    uint64_t spare3[12];
};
static_assert(sizeof(DeckStatx) == 256, "DeckStatx harus 256 byte");

static void deck_fill_statx(DeckStatx *sx) {
    struct stat st;
    deck_fill_stat(&st);
    memset(sx, 0, sizeof(*sx));
    sx->stx_mask = 0x7ff;  // STATX_BASIC_STATS
    sx->stx_blksize = (uint32_t)st.st_blksize;
    sx->stx_nlink = (uint32_t)st.st_nlink;
    sx->stx_uid = st.st_uid;
    sx->stx_gid = st.st_gid;
    sx->stx_mode = (uint16_t)st.st_mode;
    sx->stx_ino = st.st_ino;
    sx->stx_rdev_major = DECK_MAJOR;
    sx->stx_rdev_minor = DECK_MINOR;
    sx->stx_atime.tv_sec = sx->stx_mtime.tv_sec = sx->stx_ctime.tv_sec = st.st_atim.tv_sec;
    sx->stx_atime.tv_nsec = sx->stx_mtime.tv_nsec = sx->stx_ctime.tv_nsec = (uint32_t)st.st_atim.tv_nsec;
}

DECK_HOOK int deck_hook_stat64(const char *path, void *buf) __asm__("stat64");
int deck_hook_stat64(const char *path, void *buf) { return stat(path, (struct stat *)buf); }

DECK_HOOK int deck_hook_lstat64(const char *path, void *buf) __asm__("lstat64");
int deck_hook_lstat64(const char *path, void *buf) { return lstat(path, (struct stat *)buf); }

DECK_HOOK int deck_hook_fstat64(int fd, void *buf) __asm__("fstat64");
int deck_hook_fstat64(int fd, void *buf) { return fstat(fd, (struct stat *)buf); }

static int deck_fstatat_impl(int dirfd, const char *path, void *buf, int flags) {
    if (deck_enabled() && buf) {
        if (path && !*path && (flags & AT_EMPTY_PATH) && deck_find(dirfd)) {
            deck_fill_stat((struct stat *)buf);
            return 0;
        }
        std::string abs, red;
        if (deck_abs_path(dirfd, path, abs)) {
            if (abs == DECK_NODE) { deck_fill_stat((struct stat *)buf); return 0; }
            if (deck_redirect(abs.c_str(), red))
                return (int)syscall(SYS_newfstatat, AT_FDCWD, red.c_str(), buf, flags);
        }
    }
    return (int)syscall(SYS_newfstatat, dirfd, path, buf, flags);
}

DECK_HOOK int deck_hook_fstatat(int dirfd, const char *path, void *buf, int flags) __asm__("fstatat");
int deck_hook_fstatat(int dirfd, const char *path, void *buf, int flags) { return deck_fstatat_impl(dirfd, path, buf, flags); }

DECK_HOOK int deck_hook_fstatat64(int dirfd, const char *path, void *buf, int flags) __asm__("fstatat64");
int deck_hook_fstatat64(int dirfd, const char *path, void *buf, int flags) { return deck_fstatat_impl(dirfd, path, buf, flags); }

DECK_HOOK int deck_hook_statx(int dirfd, const char *path, int flags, unsigned int mask, void *buf) __asm__("statx");
int deck_hook_statx(int dirfd, const char *path, int flags, unsigned int mask, void *buf) {
    if (deck_enabled() && buf) {
        if (path && !*path && (flags & AT_EMPTY_PATH) && deck_find(dirfd)) {
            deck_fill_statx((DeckStatx *)buf);
            return 0;
        }
        std::string abs, red;
        if (deck_abs_path(dirfd, path, abs)) {
            if (abs == DECK_NODE) { deck_fill_statx((DeckStatx *)buf); return 0; }
            if (deck_redirect(abs.c_str(), red))
                return (int)syscall(SYS_statx, AT_FDCWD, red.c_str(), flags, mask, buf);
        }
    }
    return (int)syscall(SYS_statx, dirfd, path, flags, mask, buf);
}

DECK_HOOK int deck_hook_faccessat(int dirfd, const char *path, int mode, int flags) __asm__("faccessat");
int deck_hook_faccessat(int dirfd, const char *path, int mode, int flags) {
    std::string abs, red;
    if (deck_enabled() && deck_abs_path(dirfd, path, abs)) {
        if (abs == DECK_NODE) return 0;
        if (deck_redirect(abs.c_str(), red)) { dirfd = AT_FDCWD; path = red.c_str(); }
    }
    if (flags) {
        long r = syscall(SYS_faccessat2, dirfd, path, mode, flags);
        if (r == 0 || errno != ENOSYS) return (int)r;
    }
    return (int)syscall(SYS_faccessat, dirfd, path, mode);
}

DECK_HOOK ssize_t deck_hook_readlinkat(int dirfd, const char *path, char *buf, size_t size) __asm__("readlinkat");
ssize_t deck_hook_readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    std::string abs, red;
    if (deck_enabled() && deck_abs_path(dirfd, path, abs) && deck_redirect(abs.c_str(), red)) {
        dirfd = AT_FDCWD;
        path = red.c_str();
    }
    ssize_t result = syscall(SYS_readlinkat, dirfd, path, buf, size);
    if (result > 0 && deck_enabled()) result = (ssize_t)deck_strip_root(buf, (size_t)result);
    return result;
}

DECK_HOOK struct dirent *deck_hook_readdir(DIR *d) __asm__("readdir");
struct dirent *deck_hook_readdir(DIR *d) {
    static auto real = reinterpret_cast<struct dirent *(*)(DIR *)>(dlsym(RTLD_NEXT, "readdir"));
    struct dirent *e = real(d);
    if (e || !deck_enabled() || !deck_dev_take(d)) return e;
    static thread_local struct dirent fake;
    deck_fill_dirent(&fake);
    return &fake;
}

DECK_HOOK struct dirent64 *deck_hook_readdir64(DIR *d) __asm__("readdir64");
struct dirent64 *deck_hook_readdir64(DIR *d) {
    static auto real = reinterpret_cast<struct dirent64 *(*)(DIR *)>(dlsym(RTLD_NEXT, "readdir64"));
    struct dirent64 *e = real(d);
    if (e || !deck_enabled() || !deck_dev_take(d)) return e;
    static thread_local struct dirent64 fake;
    deck_fill_dirent(reinterpret_cast<struct dirent *>(&fake));
    return &fake;
}

DECK_HOOK void deck_hook_rewinddir(DIR *d) __asm__("rewinddir");
void deck_hook_rewinddir(DIR *d) {
    static auto real = reinterpret_cast<void (*)(DIR *)>(dlsym(RTLD_NEXT, "rewinddir"));
    real(d);
    if (!deck_enabled()) return;
    std::lock_guard<std::mutex> guard(deck_dir_mutex());
    auto it = deck_dev_dirs().find(d);
    if (it != deck_dev_dirs().end()) it->second = false;
}

DECK_HOOK int deck_hook_closedir(DIR *d) __asm__("closedir");
int deck_hook_closedir(DIR *d) {
    static auto real = reinterpret_cast<int (*)(DIR *)>(dlsym(RTLD_NEXT, "closedir"));
    if (deck_enabled()) {
        std::lock_guard<std::mutex> guard(deck_dir_mutex());
        deck_dev_dirs().erase(d);
    }
    return real(d);
}

DECK_HOOK int deck_hook_open64(const char *path, int flags, ...) __asm__("open64");
int deck_hook_open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list va; va_start(va, flags); mode = va_arg(va, mode_t); va_end(va); }
    return open(path, flags, mode);
}

DECK_HOOK int deck_hook_openat64(int dirfd, const char *path, int flags, ...) __asm__("openat64");
int deck_hook_openat64(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list va; va_start(va, flags); mode = va_arg(va, mode_t); va_end(va); }
    return openat(dirfd, path, flags, mode);
}
// ---- end DroidDeck-deck v2 ----

EXPORT int open(const char *pathname, int flags, ...) {
    va_list va;
    mode_t mode;
    int fd;
    bool hasMode;
    bool isFromInput;

    va_start(va, flags);

    hasMode = flags & O_CREAT;
    isFromInput = false;
    
    if (hasMode) {
        mode = va_arg(va, mode_t);
    }
    
    va_end(va);

	if (!my_open)
    	*(void **)&my_open = dlsym(RTLD_NEXT, "open");
	// DroidDeck-deck
	std::string deck_path;
	if (pathname) {
		if (deck_is_node(pathname))
			return open_deck_hidraw(flags);
		if (deck_redirect(pathname, deck_path))
			pathname = deck_path.c_str();
	}


	if (pathname) {
		if (strstr(pathname, "/dev/input/event")) {
		    pathname = from_real_to_fake_path(pathname);
		    isFromInput = true;
		}
		else if (!strcmp(pathname, "/dev/input")) {
			pathname = hook_dir;
		}
	}
	    
	if (hasMode)
	    fd = my_open(pathname, flags, mode);
	else
	    fd = my_open(pathname, flags);

	if (isFromInput) {
		Logger::log("Adding controller, fd %d event %s\n", fd, get_event(pathname));
		controller_map[fd] = strdup(get_event(pathname));
    }
	    
	return fd;
}

EXPORT int openat(int dirfd, const char *pathname, int flags, ...) {
    va_list va;
    mode_t mode;
    int fd;
    bool hasMode;
    bool isFromInput;
    
    va_start(va, flags);

    isFromInput = false;
    hasMode = flags & O_CREAT;
    
    if (hasMode) {
        mode = va_arg(va, mode_t);
    }
    
    va_end(va);

    if (!my_openat)
    	*(void **)&my_openat = dlsym(RTLD_NEXT, "openat");
	// DroidDeck-deck
	std::string deck_path, deck_abs;
	if (pathname) {
		if (deck_is_node(pathname))
			return open_deck_hidraw(flags);
		if (deck_redirect(pathname, deck_path)) {
			pathname = deck_path.c_str();
		} else if (pathname[0] != '/' && deck_enabled() && deck_abs_path(dirfd, pathname, deck_abs)) {
			if (deck_is_node(deck_abs.c_str()))
				return open_deck_hidraw(flags);
			if (deck_redirect(deck_abs.c_str(), deck_path)) {
				pathname = deck_path.c_str();
				dirfd = AT_FDCWD;
			}
		}
	}

    
    if (pathname) {
        if (strstr(pathname, "/dev/input/event")) {
            pathname = from_real_to_fake_path(pathname);
            isFromInput = true;
        }
        else if (!strcmp(pathname, "/dev/input")) {                                    
            pathname = hook_dir;             
        }
    }
    
    if (hasMode)
        fd = my_openat(dirfd, pathname, flags, mode);
    else
        fd = my_openat(dirfd, pathname, flags);

    if (isFromInput) {
        Logger::log("Adding controller, fd %d event %s\n", fd, get_event(pathname));
        controller_map[fd] = strdup(get_event(pathname));
    }

    return fd;
}

EXPORT int stat(const char *pathname, struct stat *statbuf) {
	if (!my_stat)
		*(void **)&my_stat = dlsym(RTLD_NEXT, "stat");
	// DroidDeck-deck
	std::string deck_path;
	if (statbuf && deck_is_node(pathname)) {
		deck_fill_stat(statbuf);
		return 0;
	}
	if (deck_redirect(pathname, deck_path))
		pathname = deck_path.c_str();


     const char *event = nullptr;
     int event_number = -1;

	if (pathname) {
		if (strstr(pathname, "/dev/input/event")) {
		    pathname = from_real_to_fake_path(pathname);
		    event = get_event(pathname);
		    event_number = get_event_number(event);
		}
		else if (!strcmp(pathname, "/dev/input")) {                                    
		    pathname = hook_dir;             
		}
	}

	int ret = my_stat(pathname, statbuf);
    
    if (event && event_number >= 0) {
		statbuf->st_rdev = makedev(1, event_number);
	}

	return ret;
}

EXPORT int fstat(int fd, struct stat *buf) {
	if (!my_fstat)
    	*(void **)&my_fstat = dlsym(RTLD_NEXT, "fstat");

    int ret = my_fstat(fd, buf);
    if (ret == 0 && deck_enabled() && deck_find(fd)) {  // fd Deck adalah socketpair; tampilkan sebagai char device 240:16
        deck_fill_stat(buf);
        return ret;
    }

    auto controller = controller_map.find(fd);
    if (controller != controller_map.end()) {
    	buf->st_rdev = makedev(1, get_event_number(controller->second));
    }

    return ret;
  }

EXPORT int scandir(const char *dirp, struct dirent ***namelist, int(*filter)(const struct dirent *), int(*compar)(const struct dirent **, const struct dirent **)) {
	if (!my_scandir)
		*(void **)&my_scandir = dlsym(RTLD_NEXT, "scandir");

	const bool deck_dev = deck_enabled() && deck_is_dev_dir(dirp);
	
	if (dirp) {
	    if (!strcmp(dirp, "/dev/input")) {
	        dirp = hook_dir;
	    }
    }

	int result = my_scandir(dirp, namelist, filter, compar);
	if (result >= 0 && deck_dev) {
		result = deck_scandir_inject(namelist, result, filter, compar);
		static std::atomic<bool> noted{false};
		if (!noted.exchange(true)) Logger::log("deck: scandir(/dev) now includes hidraw16\n");
	}
	return result;
}

EXPORT int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
	if (!my_inotify_add_watch)
		*(void **)&my_inotify_add_watch = dlsym(RTLD_NEXT, "inotify_add_watch");

    if (pathname) {
        if (strstr(pathname, "/dev/input/event")) {
            pathname = from_real_to_fake_path(pathname);
        }
        else if (!strcmp(pathname, "/dev/input")) {
            pathname = hook_dir;
    	}
    }

    int watch = my_inotify_add_watch(fd, pathname, mask);
    if (watch < 0 && pathname && deck_enabled() && deck_is_dev_dir(pathname) && deck_dev_denied()) {
        // winebus: create_inotify() memantau /dev untuk hidraw baru; arahkan ke folder pengganti.
        if (const char *fallback = deck_dev_fallback()) {
            watch = my_inotify_add_watch(fd, fallback, mask);
            static std::atomic<bool> noted{false};
            if (watch >= 0 && !noted.exchange(true)) Logger::log("deck: inotify(/dev) denied, watching %s\n", fallback);
        }
    }
    return watch;
}

EXPORT int ioctl(int fd, int op, ...) {
	va_list va;
	void *argp;
	
	va_start(va, op);
	argp = va_arg(va, void *);
	va_end(va);

		// DroidDeck-deck: ioctl hidraw milik pad Deck
	if (DeckFd *deck_fd = deck_find(fd))
		return ioctl_deck(*deck_fd, (unsigned)op, argp);

	auto controller = controller_map.find(fd);
	if (controller == controller_map.end()) {
		return syscall(SYS_ioctl, fd, op, argp);
	}

	int type = (op >> 8 & 0xFF);
	int number = (op >> 0 & 0xFF);
	const char *event = controller->second;
	int event_number = get_event_number(event);

    if (type == 0x45 && number == 0x1) {
        Logger::log("Hooking ioctl EVIOCGVERSION for event %s\n", event);
        int version = 65536;
        memcpy(argp, (void *)&version, sizeof(int));
        return 0;
    }
    else if (type == 0x45 && number == 0x2) {
        Logger::log("Hooking ioctl EVIOCGID for event %s\n", event);
        struct input_id id;
        memset(&id, 0, sizeof(id));
        id.bustype = 0x03;
        id.vendor = 0x1234 + event_number;
        id.product = 0x5678 + event_number;
        id.version = 0x0110;
        memcpy(argp, (void *)&id, sizeof(id));
        return 0;
    }
    else if (type == 0x45 && number == 0x6) {
    	Logger::log("Hooking ioctl EVIOCGNAME for event %s\n", event);
    	char *name;
    	
    	asprintf(&name, "Generic HID Gamepad %d", event_number);
    	
    	strcpy((char *)argp, name);
    	return 0;
    }
    else if (type == 0x45 && number == 0x9) {
        Logger::log("Hooking ioctl EVIOCGPROP for event %s\n", event);
        return 0;
    }
    else if (type == 0x45 && number == 0x18) {
    	Logger::log("Hooking ioctl EVIOCGKEY(len) for event %s\n", event);
    	char bitmask[KEY_MAX / 8] = {0};
        memcpy(argp, (void *)&bitmask, sizeof(bitmask));
        return 0;
    }
    else if (type == 0x45 && number == 0x20) {
    	Logger::log("Hooking ioctl EVIOCGBIT(0, len) for event %s\n", event);
        char bitmask[EV_MAX / 8] = {0};
        bitmask[EV_SYN / 8] |= (1 << (EV_SYN % 8));
        bitmask[EV_KEY / 8] |= (1 << (EV_KEY % 8));
        bitmask[EV_ABS / 8] |= (1 << (EV_ABS % 8));
    	memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    	return 0;	
    }
    else if (type == 0x45 && number == 0x21) {
        Logger::log("Hooking ioctl EVIOCGBIT(EV_KEY, len) for event %s\n", event);
        char bitmask[KEY_MAX / 8] = {0};
        for (int i = 0x130; i <= 0x13e; i++) {
            if (i == 0x130)
                bitmask[BTN_A / 8] |= (1 << (BTN_A % 8));
            else if (i == 0x131)
                bitmask[BTN_B / 8] |= (1 << (BTN_B % 8));
            else if (i == 0x132)
                continue;
            else if (i == 0x133)
                bitmask[BTN_X / 8] |= (1 << (BTN_X % 8));
            else if (i == 0x134)
                bitmask[BTN_Y / 8] |= (1 << (BTN_Y % 8));
            else if (i == 0x135)
                continue;
            else
                bitmask[i / 8] |= (1 << (i % 8));
        }
        memcpy(argp, (void *)&bitmask, sizeof(bitmask));
        return 0;
    }
    else if (type == 0x45 && number == 0x22) {
    	Logger::log("Hooking ioctl EVIOCGBIT(EV_REL, len) for event %s\n", event);
    	char bitmask[REL_MAX / 8] = {0};
    	memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    	return 0;
    }
    else if (type == 0x45 && number == 0x23) {
    	Logger::log("Hooking ioctl EVIOCGBIT(EV_ABS, len) for event %s\n", event);
    	char bitmask[ABS_MAX / 8] = {0};
    	bitmask[ABS_X / 8] |= (1 << (ABS_X % 8));
    	bitmask[ABS_Y / 8] |= (1 << (ABS_Y % 8));
    	bitmask[ABS_RX / 8] |= (1 << (ABS_RX % 8));
    	bitmask[ABS_RY / 8] |= (1 << (ABS_RY % 8));
    	bitmask[ABS_GAS / 8] |= (1 << (ABS_GAS % 8));
    	bitmask[ABS_BRAKE / 8] |= (1 << (ABS_BRAKE % 8));
    	bitmask[ABS_HAT0X / 8] |= (1 << (ABS_HAT0X % 8));
    	bitmask[ABS_HAT0Y / 8] |= (1 << (ABS_HAT0Y % 8));
    	memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    	return 0;
    }
    else if (type == 0x45 && number == 0x35) {
        Logger::log("Hooking ioctl EVIOCGBIT(EV_FF, len) for event %s\n", event);
        char bitmask[FF_MAX / 8] = {0};
        bitmask[FF_RUMBLE / 8] |= (1 << (FF_RUMBLE % 8));
        bitmask[FF_PERIODIC / 8] |= (1 << (FF_PERIODIC % 8));
        memcpy(argp, (void *)&bitmask, sizeof(bitmask));
        return 0;
    }
    else if (type == 0x45 && number == 0x80) {
        struct ff_effect *effect = (struct ff_effect *)argp;
        if (effect->id == -1) {
            effect->id = next_ff_id++;
        }
        ff_effects[effect->id] = *effect;

        uint16_t duration = effect->replay.length;
        if (effect->type == FF_RUMBLE) {
            send_vibration(effect->u.rumble.strong_magnitude, effect->u.rumble.weak_magnitude, duration, (uint16_t)event_number);
        } else if (effect->type == FF_PERIODIC) {
            send_vibration(effect->u.periodic.magnitude, effect->u.periodic.magnitude, duration, (uint16_t)event_number);
        }
        return 0;
    }
    else if (type == 0x45 && number == 0x81) {
        int id = (intptr_t)argp;
        ff_effects.erase(id);
        return 0;
    }
    else if (type == 0x45 && number == 0x84) {
        int max_effects = 16;
        memcpy(argp, &max_effects, sizeof(int));
        return 0;
    }
    else if (type == 0x45 && number >= 0x40 && number <= 0x51) {
    	Logger::log("Hooking ioctl EVIOCGABS(ABS) for event %s\n", event);
    	struct input_absinfo abs_info;
    	memset(&abs_info, 0, sizeof(abs_info));
    	if (number >= 0x40 && number <= 0x44) {
    		abs_info.value = 0;
    		abs_info.minimum = -32768;
    		abs_info.maximum = 32767;
    	}
    	else if (number >= 0x49 && number <= 0x4A) {
    		abs_info.value = 0;
    		abs_info.minimum = 0;
    		abs_info.maximum = 255;
    	}
    	else if (number >= 0x50 && number <= 0x51) {
    		abs_info.value = 0;
    		abs_info.minimum = -1;
    		abs_info.maximum = 1;
    	}
    	memcpy(argp, (void *)&abs_info, sizeof(abs_info));
    	return 0;
    }
    else if (type == 0x45 && number == 0x90) {
    	Logger::log("Hooking ioctl EVIOCGRAB for event %s\n", event);
    	/* Always pretend this succeeds */
    	return 0;
    }
    else if (type == 0x6A && number == 0x13) {
    	Logger::log("Hooking ioctl JSIOCGNAME(len) for event %s\n", event);
    	char *name;
        asprintf(&name, "Generic HID Gamepad %d", event_number);
    	strcpy((char *)argp, name);
    	return 0;
    }
    else {
    	Logger::log("Unhandled evdev ioctl, type %d number %d\n", type, number);
    	return syscall(SYS_ioctl, fd, op, argp);
    }
}

EXPORT int close(int fd) {
	if (!my_close)
		*(void **)&my_close = dlsym(RTLD_NEXT, "close");
	// DroidDeck-deck
	deck_forget(fd);


	auto controller = controller_map.find(fd);
	if (controller != controller_map.end()) {
	    Logger::log("Removing controller, fd %d event %s\n", controller->first, controller->second);
	    free((void *)controller->second);
		controller_map.erase(fd);
	}

	return my_close(fd);
}

EXPORT ssize_t read(int fd, void *buf, size_t count) {
    auto controller = controller_map.find(fd);
    
    if (controller != controller_map.end()) {
        ssize_t bytes_read = 0;
        int flags = fcntl(fd, F_GETFL);
        bool isNonBlock = flags & O_NONBLOCK;
        bytes_read = syscall(SYS_read, fd, buf, count);
        while(bytes_read == 0) {
            struct stat statbuf;
            if (!my_fstat) *(void **)&my_fstat = dlsym(RTLD_NEXT, "fstat");
            if (my_fstat && my_fstat(fd, &statbuf) == 0 && statbuf.st_nlink == 0) {
                errno = ENODEV;
                return -1;
            }
            if (isNonBlock) {
                break;
            }
            setup_signal_handler();
            if (stop_flag) {
            	bytes_read = -1;
            	errno = EINTR;
            	return bytes_read;
            }
            struct pollfd pfd;
            pfd.fd = fd;
            pfd.events = POLLIN;
            if (poll(&pfd, 1, 1000) > 0) {
                // If it's a disconnected pipe, poll returns immediately with POLLHUP.
                // We fallback to usleep to prevent a 100% CPU busy-spin loop.
                if (pfd.revents & POLLHUP) {
                    usleep(1000); 
                }
            }
            bytes_read = syscall(SYS_read, fd, buf, count);
        }
        
    	return bytes_read;
    }
    return syscall(SYS_read, fd, buf, count);
}

static void check_ff_event(const struct input_event *ev, uint16_t slot) {
  if (ev->type == EV_FF) {
    int id = ev->code;
    int value = ev->value;
    if (value > 0) {
      auto it = ff_effects.find(id);
      if (it != ff_effects.end()) {
        uint16_t duration = it->second.replay.length;
        if (it->second.type == FF_RUMBLE) {
          send_vibration(it->second.u.rumble.strong_magnitude,
                         it->second.u.rumble.weak_magnitude, duration, slot);
        } else if (it->second.type == FF_PERIODIC) {
          send_vibration(it->second.u.periodic.magnitude,
                         it->second.u.periodic.magnitude, duration, slot);
        }
      }
    } else {
      send_vibration(0, 0, 0, slot);
    }
  }
}

EXPORT ssize_t write(int fd, const void *buf, size_t count) {
  if (!my_write)
    *(void **)&my_write = dlsym(RTLD_NEXT, "write");

  // Output report ke Deck (haptik/rumble) dibuang: tak ada pembaca di sisi peer, buffer socketpair akan penuh lalu write memblokir.
  if (deck_enabled() && deck_find(fd)) return (ssize_t)count;

  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    if (count == sizeof(struct input_event)) {
      const struct input_event *ev = (const struct input_event *)buf;
      uint16_t slot = (uint16_t)get_event_number(controller->second);
      check_ff_event(ev, slot);
      // EV_FF events are FF control commands sent by Wine to the fake device.
      // Writing them to the fake evdev file causes Wine to read them back as
      // input events, corrupting controller state and blocking input. Consume
      // them here and return success without writing to the file.
      if (ev->type == EV_FF)
        return (ssize_t)count;
    }
  }
  return my_write(fd, buf, count);
}

EXPORT ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {
  if (deck_enabled() && deck_find(fd)) {
    ssize_t total = 0;
    for (int i = 0; i < iovcnt; i++) total += (ssize_t)iov[i].iov_len;
    return total;
  }
  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    uint16_t slot = (uint16_t)get_event_number(controller->second);
    // Separate FF control events from regular input events.
    // FF events must not be written to the fake evdev file (see write() above).
    struct iovec filtered[iovcnt];
    int filtered_count = 0;
    for (int i = 0; i < iovcnt; i++) {
      if (iov[i].iov_len == sizeof(struct input_event)) {
        const struct input_event *ev = (const struct input_event *)iov[i].iov_base;
        check_ff_event(ev, slot);
        if (ev->type == EV_FF)
          continue;
      }
      filtered[filtered_count++] = iov[i];
    }
    if (filtered_count == 0)
      return (ssize_t)(iovcnt * sizeof(struct input_event));
    return syscall(SYS_writev, fd, filtered, filtered_count);
  }
  return syscall(SYS_writev, fd, iov, iovcnt);
}