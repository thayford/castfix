package com.thayford.castfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The protobuf walk behind the device-auth probe. A reply it cannot follow must count as "no signature", not
 * as "signed": claiming signed when the reply was unreadable would hide the exact failure it exists to catch.
 */
public class ProbeParsingTest {

    /** A CastMessage wrapping a DeviceAuthMessage whose response carries a signature of this length. */
    private static byte[] messageWithSignature(int signatureLength) {
        byte[] signature = new byte[signatureLength];
        for (int i = 0; i < signatureLength; i++) signature[i] = (byte) i;
        byte[] response = bytes(1, signature);              // DeviceAuthResponse.signature
        byte[] deviceAuth = bytes(2, response);             // DeviceAuthMessage.response
        return bytes(7, deviceAuth);                        // CastMessage.binaryPayload
    }

    /** field: length-delimited */
    private static byte[] bytes(int field, byte[] value) {
        byte[] key = varint((field << 3) | 2);
        byte[] len = varint(value.length);
        byte[] out = new byte[key.length + len.length + value.length];
        System.arraycopy(key, 0, out, 0, key.length);
        System.arraycopy(len, 0, out, key.length, len.length);
        System.arraycopy(value, 0, out, key.length + len.length, value.length);
        return out;
    }

    private static byte[] varint(long value) {
        byte[] buffer = new byte[10];
        int i = 0;
        while (true) {
            long b = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                buffer[i++] = (byte) (b | 0x80);
            } else {
                buffer[i++] = (byte) b;
                byte[] out = new byte[i];
                System.arraycopy(buffer, 0, out, 0, i);
                return out;
            }
        }
    }

    @Test
    public void zeroLengthSignatureReadsAsEmpty() {
        assertTrue(CastProbe.signatureIsEmpty(messageWithSignature(0)));
    }

    @Test
    public void presentSignatureReadsAsSigned() {
        // 256 bytes is what a provisioned kirkwood actually returned.
        assertFalse(CastProbe.signatureIsEmpty(messageWithSignature(256)));
    }

    @Test
    public void unreadableReplyIsTreatedAsEmptyNotAsSigned() {
        assertTrue(CastProbe.signatureIsEmpty(new byte[]{1, 2, 3, 4, 5}));
        assertTrue(CastProbe.signatureIsEmpty(new byte[0]));
    }

    @Test
    public void replyWithoutAResponseFieldIsTreatedAsEmpty() {
        // A DeviceAuthMessage carrying only a challenge: it answered, but said nothing about signing.
        assertTrue(CastProbe.signatureIsEmpty(bytes(7, bytes(1, new byte[]{9, 9}))));
    }

    @Test
    public void extraFieldsBeforeTheSignatureAreSkipped() {
        // Real replies carry the signer's certificate and extras; the walk has to get past them.
        byte[] withExtras = concat(
                bytes(1, new byte[256]),                    // signature
                bytes(2, new byte[956]),                    // certificate chain
                bytes(5, new byte[16]));                    // nonce echo
        assertFalse(CastProbe.signatureIsEmpty(bytes(7, bytes(2, withExtras))));
    }

    @Test
    public void signatureAfterTheCertificateIsStillFound() {
        byte[] response = concat(
                bytes(1, new byte[0]),                      // empty signature first
                bytes(2, new byte[956]));
        assertTrue(CastProbe.signatureIsEmpty(bytes(7, bytes(2, response))));
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
