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
        if (nsd == null) return -1;
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch found = new CountDownLatch(1);
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo service) {
                try {
                    nsd.resolveService(service, new NsdManager.ResolveListener() {
                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            InetAddress host = resolved.getHost();
                            // Other phones and TVs advertise the same service; only ours counts.
                            if (host != null && (host.isLoopbackAddress()
                                    || ownIp.equals(host.getHostAddress()))) {
                                port.set(resolved.getPort());
                                found.countDown();
                            }
                        }

                        @Override
                        public void onResolveFailed(NsdServiceInfo service, int errorCode) {
                            // Another advertisement will arrive; the caller retries the whole lookup.
                        }
                    });
                } catch (IllegalArgumentException alreadyResolving) {
                    // NsdManager allows one resolve at a time. Dropping this candidate is right: the next
                    // discovery pass will offer it again, and throwing here would kill the callback thread.
                }
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
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return -1; // discovery already running, or NSD unavailable: the caller retries
        }
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (IllegalArgumentException | IllegalStateException alreadyStopped) {
                // discovery never started, or was already stopped
            }
        }
        return port.get();
    }
}
