package com.thayford.castfix;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Bluetooth LE remote disconnects whenever it idles (after 30 minutes here) and reconnects on the next
 * key press, because the TV keeps listening for it: the LE connector is ARMED with the remote on its
 * accept list. Two ways that listening has been seen to stop for good, with the remote still paired,
 * until Bluetooth restarts:
 *
 * - the accept list left empty (`Shadow le accept list size:0`), documented on a Chromecast HD in
 *   github.com/adn0liva/chromecast-hd-remote-fix (66 occurrences in 8 days there)
 * - the connector frozen in DISARMED or DISARMING (`le_connectability_state`), seen on this TV twice,
 *   for 3 days and for 26 hours
 *
 * Either one, while a paired LE input device is disconnected, counts as the fault. It has to show in two
 * checks in a row, then Bluetooth is restarted through adb (`cmd bluetooth_manager`): apps targeting
 * Android 13 or later may no longer switch Bluetooth themselves. The pairing is kept.
 *
 * `settings put global castfix_remote_watchdog 0` turns the watchdog off.
 */
final class RemoteWatch {
    static final String SETTING = "castfix_remote_watchdog";
    /** BluetoothProfile.STATE_CONNECTED, as HidHostService's dump prints it. */
    private static final int CONNECTED = 2;

    private static final Pattern ADAPTER = Pattern.compile("(?m)^  state: (\\S+)");
    private static final Pattern BONDED =
            Pattern.compile("(?m)^[ \\t]+([0-9A-Fa-f:]{17}) \\[[ \\t]*(LE|DUAL|BR/EDR)[ \\t]*\\] ?(.*)$");
    private static final Pattern INPUT = Pattern.compile("^[ \\t]+([0-9A-Fa-fXx:]{17}) : (\\d+)\\s*$");
    /** `shim::legacy::hid   1: addr:xx:xx:xx:xx:3d:63`, with ` ... state:BTHH_CONN_STATE_CONNECTED` once connected. */
    private static final Pattern NATIVE_HID = Pattern.compile(
            "(?m)^shim::legacy::hid\\s+\\d+: addr:([0-9A-Fa-fXx:]{17})(?:.*?state:BTHH_CONN_STATE_(\\w+))?");
    private static final Pattern CONNECTOR = Pattern.compile("le_connectability_state: \"(?:\\w+::)?(\\w+)\"");
    private static final Pattern ACCEPT_LIST = Pattern.compile("Shadow le accept list\\s+size:(\\d+)");

    static boolean enabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SETTING, 1) != 0;
    }

    /** What the dump says; null fields mean this firmware doesn't print them. */
    static final class Status {
        boolean on;
        String connector;
        Integer acceptList;
        /** Paired LE input devices, by name, and those of them not connected right now. */
        final List<String> remotes = new ArrayList<>();
        final List<String> idle = new ArrayList<>();

        boolean fault() {
            if (!on || idle.isEmpty()) return false;
            boolean emptyList = acceptList != null && acceptList == 0;
            boolean disarmed = "DISARMED".equals(connector) || "DISARMING".equals(connector);
            return emptyList || disarmed;
        }

        String describe() {
            if (!on) return "Bluetooth off";
            if (remotes.isEmpty()) return "no paired LE remote";
            String listening = (connector == null ? "" : connector.toLowerCase(Locale.US))
                    + (acceptList == null ? "" : (connector == null ? "" : ", ") + "accept list " + acceptList);
            if (idle.isEmpty()) return remotes.size() + " remote(s) connected";
            return "idle: " + String.join(", ", idle) + " (" + listening + ")";
        }
    }

    /** Reads `dumpsys bluetooth_manager` (needs DUMP); null if that failed. */
    static Status read() {
        try {
            Process process = new ProcessBuilder("dumpsys", "bluetooth_manager").redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroy();
            String dump = out.toString(StandardCharsets.UTF_8.name());
            if (dump.contains("Permission Denial")) return null;
            return parse(dump);
        } catch (Exception e) {
            return null;
        }
    }

    static Status parse(String dump) {
        dump = dump.replace("\r", "");
        Status status = new Status();
        Matcher adapter = ADAPTER.matcher(dump);
        status.on = adapter.find() && "ON".equals(adapter.group(1));

        // Paired LE devices by the last two bytes of the address: the profile dumps mask the rest.
        Map<String, String> leNames = new HashMap<>();
        int bonded = dump.indexOf("Bonded devices:");
        if (bonded >= 0) {
            Matcher device = BONDED.matcher(dump);
            device.region(bonded, dump.length());
            int expected = dump.indexOf('\n', bonded) + 1;
            while (device.find() && device.start() == expected) {
                // Only a genuine LE device uses the LE connector the way this fault describes. A DUAL device
                // is bonded for its classic profiles too (audio, or HID over BR/EDR) and idles by design, so
                // treating one as an LE remote restarted Bluetooth every few minutes for paired headphones.
                if ("LE".equals(device.group(2))) {
                    String name = device.group(3).trim();
                    leNames.put(suffix(device.group(1)), (name.isEmpty() ? "remote" : name) + " " + suffix(device.group(1)));
                }
                expected = device.end() + 1;
            }
        }

        // Input devices: HID host's list has their connection state, but only for those that connected since
        // Bluetooth started; the native HID list also names the paired ones that haven't yet.
        Map<String, Boolean> inputs = new LinkedHashMap<>();
        int hid = dump.indexOf("Profile: HidHostService");
        if (hid >= 0) {
            int list = dump.indexOf("mInputDevices:", hid);
            int end = dump.indexOf("\nProfile: ", hid + 1);
            if (list >= 0 && (end < 0 || list < end)) {
                String[] lines = dump.substring(dump.indexOf('\n', list) + 1, end < 0 ? dump.length() : end).split("\n");
                for (String line : lines) {
                    Matcher input = INPUT.matcher(line);
                    if (!input.matches()) break;
                    inputs.put(suffix(input.group(1)), Integer.parseInt(input.group(2)) == CONNECTED);
                }
            }
        }
        // HidHostService's state wins where it has one; the native list only fills in the rest.
        Map<String, Boolean> nativeHid = new LinkedHashMap<>();
        Matcher device = NATIVE_HID.matcher(dump);
        while (device.find()) {
            nativeHid.merge(suffix(device.group(1)), "CONNECTED".equals(device.group(2)), (a, b) -> a || b);
        }
        for (Map.Entry<String, Boolean> entry : nativeHid.entrySet()) inputs.putIfAbsent(entry.getKey(), entry.getValue());
        for (Map.Entry<String, Boolean> input : inputs.entrySet()) {
            String name = leNames.get(input.getKey());
            if (name == null) continue; // classic (BR/EDR) devices don't use the LE connector
            status.remotes.add(name);
            if (!input.getValue()) status.idle.add(name);
        }

        Matcher connector = CONNECTOR.matcher(dump);
        if (connector.find()) status.connector = connector.group(1);
        Matcher accept = ACCEPT_LIST.matcher(dump);
        if (accept.find()) status.acceptList = Integer.parseInt(accept.group(1));
        return status;
    }

    /** Restarts Bluetooth over adb and returns a line for the log. */
    static String restart(Context context, FixLog log) throws Exception {
        return SelfAdb.run(context, log, 15_000, shell -> {
            ContentResolver resolver = context.getContentResolver();
            shell.run("cmd bluetooth_manager disable");
            boolean off = waitFor(resolver, 0, 15_000);
            shell.run("cmd bluetooth_manager enable");
            boolean on = waitFor(resolver, 1, 20_000);
            return "Bluetooth restarted (" + (off ? "off" : "off not seen") + ", " + (on ? "on again" : "NOT on yet") + ")";
        });
    }

    private static boolean waitFor(ContentResolver resolver, int value, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, -1) == value) return true;
            SystemClock.sleep(500);
        }
        return false;
    }

    private static String suffix(String address) {
        String upper = address.toUpperCase(Locale.US);
        return upper.substring(upper.length() - 5);
    }
}
