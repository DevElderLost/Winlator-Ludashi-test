package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Hemat RAM saat game dijalankan dari Steam (port logika SaveMemoryTask Winlator resmi).
 * Tiap 500 ms: bila ada proses setelah steam.exe yang memakai > 500 MB dan baris perintahnya memuat "steamapps",
 * steamwebhelper.exe (UI Steam berbasis CEF) dimatikan. Membaca /proc langsung, jadi tidak bergantung pada
 * emulator (Box64 / FEX) maupun varian Wine (glibc / bionic / arm64ec).
 */
public class SteamSaveMemoryTask {
    private static final long MEMORY_LIMIT = 500000000L;
    private static final int PAGE_SIZE = 4096;

    private Timer timer;
    private String cachedProcessName = null;

    public synchronized void start() {
        if (timer != null) return;
        timer = new Timer("steam-save-memory", true);
        timer.schedule(new TimerTask() {
            @Override public void run() {
                try {
                    tick();
                } catch (Throwable ignored) {
                }
            }
        }, 0, 500);
    }

    public synchronized void stop() {
        cachedProcessName = null;
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private static final class Proc {
        final int pid;
        final String name;
        Proc(int pid, String name) { this.pid = pid; this.name = name; }
    }

    private void tick() {
        ArrayList<Proc> processes = listProcesses();
        int steamPID = 0;
        boolean saveMemory = false;
        for (Proc process : processes) {
            if (process.name.equals("steam.exe")) {
                steamPID = process.pid;
            } else if (steamPID > 0 && process.name.equals(cachedProcessName)) {
                saveMemory = true;
            } else if (steamPID > 0 && process.pid > steamPID && memoryUsage(process.pid) > MEMORY_LIMIT) {
                for (String cmd : cmdLine(process.pid)) {
                    if (cmd.contains("steamapps")) {
                        cachedProcessName = process.name;
                        saveMemory = true;
                        break;
                    }
                }
            }
            if (saveMemory) break;
        }

        if (saveMemory) {
            for (Proc process : processes) {
                if (process.name.startsWith("steamwebhelper")) ProcessHelper.killProcess(process.pid);
            }
        } else {
            cachedProcessName = null;
        }
    }

    /** Proses yang namanya berakhiran .exe (nama Windows), urut menurut pid. */
    private static ArrayList<Proc> listProcesses() {
        ArrayList<Proc> out = new ArrayList<>();
        String[] names = new File("/proc").list();
        if (names == null) return out;
        ArrayList<Integer> pids = new ArrayList<>();
        for (String n : names) {
            if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
            try {
                pids.add(Integer.parseInt(n));
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.Collections.sort(pids);
        for (int pid : pids) {
            String name = exeName(pid);
            if (name != null) out.add(new Proc(pid, name));
        }
        return out;
    }

    /** Nama exe Windows dari baris perintah proses (Wine menulis nama exe di argv[0]); null bila bukan proses .exe. */
    private static String exeName(int pid) {
        for (String arg : cmdLine(pid)) {
            String base = arg;
            int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
            if (slash >= 0) base = base.substring(slash + 1);
            base = base.toLowerCase(java.util.Locale.ROOT);
            if (base.endsWith(".exe")) return base;
            break;  // hanya argv[0]
        }
        return null;
    }

    private static java.util.List<String> cmdLine(int pid) {
        byte[] data = readFile("/proc/" + pid + "/cmdline", 8192);
        if (data == null || data.length == 0) return java.util.Collections.emptyList();
        ArrayList<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                if (i > start) parts.add(new String(data, start, i - start));
                start = i + 1;
            }
        }
        if (start < data.length) parts.add(new String(data, start, data.length - start));
        return parts;
    }

    private static long memoryUsage(int pid) {
        byte[] data = readFile("/proc/" + pid + "/statm", 256);
        if (data == null) return 0L;
        String[] f = new String(data).trim().split("\\s+");
        if (f.length < 2) return 0L;
        try {
            return Long.parseLong(f[1]) * PAGE_SIZE;  // halaman resident
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static byte[] readFile(String path, int max) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[max];
            int n = in.read(buf);
            return n <= 0 ? null : Arrays.copyOf(buf, n);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
