package com.thayford.castfix;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Finds the port Wireless debugging listens on; it changes every time debugging is switched on. */
final class AdbPort {
    private static final String SERVICE = "_adb-tls-connect._tcp";

    private AdbPort() {
    }

    /** The port this TV (by its Wi-Fi address) advertises over mDNS, or -1 if none showed up in time. */
    @SuppressWarnings("deprecation") // resolveService: the API 34 replacement adds nothing for one lookup
    static int find(Context context, String ownIp, long timeoutMs) throws InterruptedException {
        NsdManager nsd = context.getSystemService(NsdManager.class);
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch found = new CountDownLatch(1);
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo service) {
                nsd.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        InetAddress host = resolved.getHost();
                        // Other phones and TVs on the network advertise the same service; only ours counts.
                        if (host != null && (host.isLoopbackAddress() || ownIp.equals(host.getHostAddress()))) {
                            port.set(resolved.getPort());
                            found.countDown();
                        }
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo service, int errorCode) {
                    }
                });
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                found.countDown();
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };
        nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener);
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (IllegalArgumentException alreadyStopped) {
                // discovery never started
            }
        }
        return port.get();
    }
}
