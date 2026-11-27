package com.thayford.castfix;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Asks this TV's Cast receiver to sign an authentication challenge, the first thing a phone does before
 * casting. Without its device credentials the receiver still answers, with an empty signature, and the
 * phone then reports "Untrusted device". Needs no adb, so it is cheap enough to run every few minutes.
 */
final class CastProbe {
    private static final int CAST_PORT = 8009;
    private static final int TIMEOUT_MS = 10_000;
    private static final String DEVICE_AUTH = "urn:x-cast:com.google.cast.tp.deviceauth";

    private CastProbe() {
    }

    /** True if the answer was signed, false if it was unsigned or an error, null if no answer came. */
    static Boolean signed() {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress("127.0.0.1", CAST_PORT), TIMEOUT_MS);
            raw.setSoTimeout(TIMEOUT_MS);
            // The receiver's certificate is self-signed, and nothing secret is sent: only its answer is read.
            try (SSLSocket socket = (SSLSocket) trustAll().getSocketFactory()
                    .createSocket(raw, "127.0.0.1", CAST_PORT, true)) {
                OutputStream out = socket.getOutputStream();
                out.write(challenge());
                out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                for (int i = 0; i < 4; i++) {
                    int length = in.readInt();
                    if (length < 0 || length > 65_536) return null;
                    byte[] message = new byte[length];
                    in.readFully(message);
                    // CastMessage: 4 namespace, 7 payload_binary
                    byte[] namespace = field(message, 4);
                    if (namespace == null || !DEVICE_AUTH.equals(new String(namespace, StandardCharsets.UTF_8))) {
                        continue;
                    }
                    byte[] auth = field(message, 7);
                    // DeviceAuthMessage: 2 response, 3 error; AuthResponse: 1 signature
                    byte[] response = auth == null ? null : field(auth, 2);
                    if (response == null) return false;
                    byte[] signature = field(response, 1);
                    return signature != null && signature.length > 0;
                }
                return null;
            }
        } catch (IOException | GeneralSecurityException e) {
            return null;
        }
    }

    /** A framed CastMessage carrying DeviceAuthMessage{challenge: {}}, sender to receiver. */
    private static byte[] challenge() {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        varint(message, 1 << 3);
        varint(message, 0);                                     // protocol_version CASTV2_1_0
        bytes(message, 2, "sender-0".getBytes(StandardCharsets.UTF_8));
        bytes(message, 3, "receiver-0".getBytes(StandardCharsets.UTF_8));
        bytes(message, 4, DEVICE_AUTH.getBytes(StandardCharsets.UTF_8));
        varint(message, 5 << 3);
        varint(message, 1);                                     // payload_type BINARY
        bytes(message, 7, new byte[]{1 << 3 | 2, 0});           // payload_binary: an empty challenge
        byte[] body = message.toByteArray();
        return ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
    }

    private static void bytes(ByteArrayOutputStream out, int number, byte[] value) {
        varint(out, number << 3 | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** The first length-delimited field with this number in a protobuf message, or null. */
    private static byte[] field(byte[] message, int number) {
        int[] at = {0};
        while (at[0] < message.length) {
            long key = readVarint(message, at);
            int wire = (int) (key & 7);
            long length;
            switch (wire) {
                case 0: readVarint(message, at); continue;
                case 1: length = 8; break;
                case 2: length = readVarint(message, at); break;
                case 5: length = 4; break;
                default: return null;
            }
            if (length < 0 || at[0] + length > message.length) return null;
            int start = at[0];
            at[0] += (int) length;
            if (wire == 2 && key >>> 3 == number) return Arrays.copyOfRange(message, start, at[0]);
        }
        return null;
    }

    private static long readVarint(byte[] message, int[] at) {
        long value = 0;
        for (int shift = 0; shift < 64 && at[0] < message.length; shift += 7) {
            byte b = message[at[0]++];
            value |= (long) (b & 0x7F) << shift;
            if (b >= 0) return value;
        }
        return -1;
    }

    private static SSLContext trustAll() throws GeneralSecurityException {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        return tls;
    }
}
