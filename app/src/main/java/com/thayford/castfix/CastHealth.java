package com.thayford.castfix;

import android.annotation.SuppressLint;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Asks the Cast receiver on this TV to sign a device-auth challenge, the same request every phone sends before it
 * casts. Without its device credentials the receiver still answers, but with an empty signature ("No private key,
 * hence empty signature" in its log), and phones then refuse it as an untrusted device. Needs no adb, so it can run
 * as often as needed without switching Wireless debugging on.
 */
final class CastHealth {
    enum State { SIGNED, UNSIGNED, UNREACHABLE }

    private static final String NAMESPACE = "urn:x-cast:com.google.cast.tp.deviceauth";
    private static final int PORT = 8009;
    private static final int MAX_MESSAGE = 64 * 1024;

    private CastHealth() {
    }

    /** SIGNED when the receiver holds its credentials, UNSIGNED when it doesn't, UNREACHABLE when it didn't answer. */
    static State check(int timeoutMs) {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress("127.0.0.1", PORT), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{TRUST_ANY}, null);
            try (SSLSocket socket = (SSLSocket) tls.getSocketFactory()
                    .createSocket(raw, "127.0.0.1", PORT, false)) {
                socket.startHandshake();
                // DeviceAuthMessage { challenge: AuthChallenge {} } inside a CastMessage with a binary payload.
                byte[] challenge = field(1, new byte[0]);
                ByteArrayOutputStream message = new ByteArrayOutputStream();
                writeVarintField(message, 1, 0);                                   // protocol_version CASTV2_1_0
                message.write(field(2, "sender-0".getBytes(StandardCharsets.UTF_8)));
                message.write(field(3, "receiver-0".getBytes(StandardCharsets.UTF_8)));
                message.write(field(4, NAMESPACE.getBytes(StandardCharsets.UTF_8)));
                writeVarintField(message, 5, 1);                                   // payload_type BINARY
                message.write(field(7, challenge));
                byte[] body = message.toByteArray();
                OutputStream out = socket.getOutputStream();
                out.write(new byte[]{(byte) (body.length >>> 24), (byte) (body.length >>> 16),
                        (byte) (body.length >>> 8), (byte) body.length});
                out.write(body);
                out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int length = in.readInt();
                if (length <= 0 || length > MAX_MESSAGE) return State.UNREACHABLE;
                byte[] reply = new byte[length];
                in.readFully(reply);
                byte[] payload = lengthDelimited(reply, 7);                         // CastMessage.payload_binary
                if (payload == null) return State.UNREACHABLE;
                if (lengthDelimited(payload, 3) != null) return State.UNSIGNED;     // DeviceAuthMessage.error
                byte[] response = lengthDelimited(payload, 2);                     // DeviceAuthMessage.response
                if (response == null) return State.UNREACHABLE;
                byte[] signature = lengthDelimited(response, 1);                   // AuthResponse.signature
                return signature != null && signature.length > 0 ? State.SIGNED : State.UNSIGNED;
            }
        } catch (Exception e) {
            return State.UNREACHABLE;
        }
    }

    /** The receiver's TLS certificate is self-signed; what is checked here is its signature, not the channel. */
    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    private static final X509TrustManager TRUST_ANY = new X509TrustManager() {
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
    };

    private static byte[] field(int number, byte[] value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, (number << 3) | 2);
        writeVarint(out, value.length);
        out.write(value);
        return out.toByteArray();
    }

    private static void writeVarintField(ByteArrayOutputStream out, int number, long value) {
        writeVarint(out, number << 3);
        writeVarint(out, value);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    /** The first length-delimited field with this number in a protobuf message, or null if there is none. */
    private static byte[] lengthDelimited(byte[] message, int wanted) {
        int[] pos = {0};
        while (pos[0] < message.length) {
            long key = readVarint(message, pos);
            if (key < 0) return null;
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 0) {
                if (readVarint(message, pos) < 0) return null;
            } else if (wire == 2) {
                long length = readVarint(message, pos);
                if (length < 0 || pos[0] + length > message.length) return null;
                if (number == wanted) {
                    byte[] value = new byte[(int) length];
                    System.arraycopy(message, pos[0], value, 0, (int) length);
                    return value;
                }
                pos[0] += (int) length;
            } else {
                return null; // no fixed-width fields in these messages
            }
        }
        return null;
    }

    private static long readVarint(byte[] buffer, int[] pos) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= buffer.length) return -1;
            int b = buffer[pos[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        return -1;
    }
}
