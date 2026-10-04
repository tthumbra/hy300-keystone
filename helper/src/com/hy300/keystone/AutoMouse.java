package com.hy300.keystone;

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
 */
final class AutoMouse {
    static final String[] POINTER_APPS = {"com.netflix.mediaclient"};
    static final int MOUSE_SCANCODE = 232;
    static final long POLL_MS = 2000;

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
                String focus = Helper.sh("dumpsys window | grep -m1 mCurrentFocus");
                if (!focus.contains("/")) continue;   // no focused app window right now (transition)
                boolean pointerApp = false;
                for (String app : POINTER_APPS) pointerApp |= focus.contains(" " + app + "/");
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

    static boolean mouseModeOn() {
        return Helper.sh("cat /proc/bus/input/devices").contains("Name=\"VirtualMouse\"");
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
        for (String line : Helper.sh("cat /proc/bus/input/devices").split("\n")) {
            if (line.startsWith("N:")) found = line.contains("\"sunxi-ir-uinput\"");
            if (found && line.startsWith("H:")) {
                for (String h : line.substring(line.indexOf('=') + 1).trim().split(" "))
                    if (h.startsWith("event")) return "/dev/input/" + h;
            }
        }
        return null;
    }
}
