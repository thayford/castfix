package com.thayford.castfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/**
 * The remote watchdog's reading of `dumpsys bluetooth_manager`, on a dump shaped like a Chromecast HD's
 * (Google TV 14, UTTC.250917.004), cut down to the sections it reads, with made-up addresses.
 */
public class RemoteWatchTest {

    /** Two LE remotes and BR/EDR headphones; the first remote has connected since Bluetooth started. */
    private static String dump(String adapter, String inputDevices, String nativeHid, int acceptList,
            String connector) {
        return "Bluetooth Status\n"
                + "  enabled: true\n"
                + "  state: " + adapter + "\n"
                + "  address: XX:XX:XX:XX:00:01\n"
                + "\n"
                + "  Bonded devices:\n"
                + "    00:11:22:33:AA:01 [  LE  ] Chromecast Remote\n"
                + "    00:11:22:33:BB:02 [  LE  ] Chromecast Remote\n"
                + "    00:11:22:33:CC:03 [BR/EDR] Headphones\n"
                + "\n"
                + "Profile: HidHostService\n"
                + "  mTargetDevice: null\n"
                + "  mInputDevices:\n"
                + inputDevices
                + "\n"
                + "Profile: BluetoothOppService\n"
                + "\n"
                + " ----- shim::legacy::hid -----\n"
                + nativeHid
                + "shim::acl Shadow le accept list              size:" + acceptList + "   controller_max_size:255\n"
                + "    le_connectability_state: \"ConnectabilityState::" + connector + "\",\n";
    }

    private static final String BOTH_REGISTERED =
            "shim::legacy::hid   0: addr:xx:xx:xx:xx:aa:01\n"
                    + "shim::legacy::hid   1: addr:xx:xx:xx:xx:bb:02\n";

    @Test
    public void bothConnectedIsHealthy() {
        RemoteWatch.Status status = RemoteWatch.parse(dump("ON",
                "    XX:XX:XX:XX:AA:01 : 2\n    XX:XX:XX:XX:BB:02 : 2\n", BOTH_REGISTERED, 0, "DISARMED"));
        assertEquals(Arrays.asList("Chromecast Remote AA:01", "Chromecast Remote BB:02"), status.remotes);
        assertTrue(status.idle.isEmpty());
        assertFalse(status.fault());
    }

    @Test
    public void idleRemoteBeingListenedForIsHealthy() {
        RemoteWatch.Status status = RemoteWatch.parse(dump("ON",
                "    XX:XX:XX:XX:AA:01 : 0\n    XX:XX:XX:XX:BB:02 : 2\n", BOTH_REGISTERED, 1, "ARMED"));
        assertEquals(Arrays.asList("Chromecast Remote AA:01"), status.idle);
        assertFalse(status.fault());
    }

    @Test
    public void emptyAcceptListIsTheFault() {
        // github.com/adn0liva/chromecast-hd-remote-fix
        RemoteWatch.Status status = RemoteWatch.parse(dump("ON",
                "    XX:XX:XX:XX:AA:01 : 0\n    XX:XX:XX:XX:BB:02 : 2\n", BOTH_REGISTERED, 0, "ARMED"));
        assertTrue(status.fault());
    }

    @Test
    public void connectorStuckDisarmingIsTheFault() {
        RemoteWatch.Status status = RemoteWatch.parse(dump("ON",
                "    XX:XX:XX:XX:AA:01 : 0\n    XX:XX:XX:XX:BB:02 : 2\n", BOTH_REGISTERED, 2, "DISARMING"));
        assertEquals("DISARMING", status.connector);
        assertEquals(Integer.valueOf(2), status.acceptList);
        assertTrue(status.fault());
    }

    @Test
    public void remoteNotConnectedSinceBluetoothStartedIsStillWatched() {
        // HidHostService only lists remotes that connected since Bluetooth started; the native list has both.
        RemoteWatch.Status status = RemoteWatch.parse(dump("ON",
                "    XX:XX:XX:XX:BB:02 : 2\n", BOTH_REGISTERED, 0, "DISARMED"));
        assertEquals(2, status.remotes.size());
        assertEquals(Arrays.asList("Chromecast Remote AA:01"), status.idle);
        assertTrue(status.fault());
    }

    @Test
    public void bluetoothOffIsNotAFault() {
        RemoteWatch.Status status = RemoteWatch.parse(dump("OFF",
                "    XX:XX:XX:XX:AA:01 : 0\n", BOTH_REGISTERED, 0, "DISARMED"));
        assertFalse(status.on);
        assertFalse(status.fault());
    }

    @Test
    public void classicDevicesAreIgnored() {
        RemoteWatch.Status status = RemoteWatch.parse(dump("ON",
                "    XX:XX:XX:XX:CC:03 : 0\n    XX:XX:XX:XX:AA:01 : 2\n    XX:XX:XX:XX:BB:02 : 2\n",
                BOTH_REGISTERED, 0, "DISARMED"));
        assertFalse(status.remotes.contains("Headphones CC:03"));
        assertFalse(status.fault());
    }

    @Test
    public void windowsLineEndingsParseTheSame() {
        RemoteWatch.Status status = RemoteWatch.parse(dump("ON",
                "    XX:XX:XX:XX:AA:01 : 0\n    XX:XX:XX:XX:BB:02 : 2\n", BOTH_REGISTERED, 0, "ARMED")
                .replace("\n", "\r\n"));
        assertTrue(status.fault());
    }

    @Test
    public void firmwareWithoutTheseSectionsNeverTriggers() {
        RemoteWatch.Status status = RemoteWatch.parse("Bluetooth Status\n  state: ON\n");
        assertNull(status.connector);
        assertNull(status.acceptList);
        assertFalse(status.fault());
    }
}
