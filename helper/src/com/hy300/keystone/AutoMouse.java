package com.hy300.keystone;

import java.io.File;
import java.nio.file.Files;

/**
 * Turns the firmware's "virtual mouse mode" on while a touch-only app is in front, and off again after.
 *
 * The Netflix on this projector is the phone build: it has no arrow-key navigation. The firmware can make
 * the IR remote's arrows drive a pointer (MultiirService, toggled by the remote's mouse key = scancode 232
 * on the "sunxi-ir-uinput" device, which shell may write as a member of the input group). While that mode
 * is on, an input device named "VirtualMouse" exists.
 *
 * Only the moments of entering and leaving the app toggle the mode, so the mouse key on the remote still
 * works inside the app (e.g. to type a password with the arrow keys).
 *
 * "In front" is read from /proc (the app's process exists and its oom_score_adj is 0, which Android gives
 * the foreground app): a few file reads per poll, where asking the window manager cost ~120 ms of CPU.
 */
final class AutoMouse {
    static final String[] POINTER_APPS = {"com.netflix.mediaclient"};
    static final int MOUSE_SCANCODE = 232;
    static final long POLL_MS = 2000;

    static final int[] pids = new int[POINTER_APPS.length];
    static int polls;

    static void start() {
        Thread t = new Thread(AutoMouse::run, "auto-mouse");
        t.setDaemon(true);
        t.start();
    }

    static void run() {
        boolean wasPointerApp = false;
        while (true) {
            try {
                Thread.sleep(POLL_MS);
                boolean pointerApp = false;
                for (int i = 0; i < POINTER_APPS.length; i++) pointerApp |= inFront(i);
                if (pointerApp != wasPointerApp && mouseModeOn() != pointerApp) {
                    toggle();
                    Helper.log("auto-mouse " + (pointerApp ? "on" : "off"));
                }
                wasPointerApp = pointerApp;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Helper.log("auto-mouse: " + e);
            }
        }
    }

    static boolean inFront(int app) {
        if (pids[app] != 0 && !POINTER_APPS[app].equals(cmdline(pids[app]))) pids[app] = 0;
        // Looking for a process that isn't running reads all of /proc (~45 ms of CPU here): every 3rd poll.
        if (pids[app] == 0 && ++polls % 3 == 0) pids[app] = findPid(POINTER_APPS[app]);
        return pids[app] != 0 && "0".equals(read("/proc/" + pids[app] + "/oom_score_adj"));
    }

    static int findPid(String name) {
        String[] entries = new File("/proc").list();
        if (entries == null) return 0;
        for (String e : entries) {
            if (e.isEmpty() || e.charAt(0) < '1' || e.charAt(0) > '9') continue;
            try {
                int pid = Integer.parseInt(e);
                if (name.equals(cmdline(pid))) return pid;
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    static String cmdline(int pid) {
        String s = read("/proc/" + pid + "/cmdline");
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    static String read(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath())).trim();
        } catch (Exception e) {
            return "";
        }
    }

    static boolean mouseModeOn() {
        return read("/proc/bus/input/devices").contains("Name=\"VirtualMouse\"");
    }

    static void toggle() {
        String dev = irDevice();
        if (dev == null) { Helper.log("auto-mouse: no sunxi-ir-uinput device"); return; }
        Helper.sh("sendevent " + dev + " 1 " + MOUSE_SCANCODE + " 1; sendevent " + dev + " 0 0 0; "
                + "sendevent " + dev + " 1 " + MOUSE_SCANCODE + " 0; sendevent " + dev + " 0 0 0");
    }

    /** /dev/input/eventN of the IR remote's uinput device, from /proc/bus/input/devices. */
    static String irDevice() {
        boolean found = false;
        for (String line : read("/proc/bus/input/devices").split("\n")) {
            if (line.startsWith("N:")) found = line.contains("\"sunxi-ir-uinput\"");
            if (found && line.startsWith("H:")) {
                for (String h : line.substring(line.indexOf('=') + 1).trim().split(" "))
                    if (h.startsWith("event")) return "/dev/input/" + h;
            }
        }
        return null;
    }
}
