package ch.ninebot.f2lab;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal independent implementation of the Segway/Ninebot Encryption2 (Gen2)
 * transport used only for owner-authorized interoperability tests.
 */
public final class NinebotCrypto {
    private static final byte[] FW_DATA = new byte[] {
            (byte)0x97,(byte)0xCF,(byte)0xB8,0x02,(byte)0x84,0x41,0x43,(byte)0xDE,
            0x56,0x00,0x2B,0x3B,0x34,0x78,0x0A,0x5D
    };

    private byte[] aesKey = new byte[16];
    private byte[] auth = new byte[16];
    private int txCounter = 0;
    private int rxCounter = 0;
    private boolean snMode = false;
    private byte[] nonSnEcbInput = Arrays.copyOf(FW_DATA, 16);

    public void reset() {
        Arrays.fill(auth, (byte)0);
        txCounter = 0;
        rxCounter = 0;
        snMode = false;
        nonSnEcbInput = Arrays.copyOf(FW_DATA, 16);
    }

    public void setPreCommKey(String bleName) throws Exception {
        byte[] name = bleName == null ? new byte[0] : bleName.getBytes("UTF-8");
        // PRE_COMM key: SHA1(BLE-name || zeros). Gen2 uses FW_DATA only as
        // the non-SN ECB input block, not as the second key-derivation input.
        aesKey = deriveKey(name, null);
        snMode = false;
        nonSnEcbInput = Arrays.copyOf(FW_DATA, 16);
        txCounter = 0;
        rxCounter = 0;
    }

    public void setAuth(byte[] authParam) {
        auth = Arrays.copyOf(authParam, 16);
        snMode = true;
        // Native flow enters SN mode at counter=1; first encrypted frame is 2.
        txCounter = 1;
        rxCounter = 1;
    }

    public void setHandshakeKey(String bleName) throws Exception {
        byte[] name = bleName == null ? new byte[0] : bleName.getBytes("UTF-8");
        aesKey = deriveKey(name, auth);
    }

    public void setSessionKey(byte[] password) throws Exception {
        aesKey = deriveKey(password, auth);
    }

    public byte[] encrypt(byte[] plain) throws Exception {
        return snMode ? encryptSn(plain) : encryptNonSn(plain);
    }

    public byte[] decrypt(byte[] wire) throws Exception {
        if (wire == null || wire.length < 9 || wire[0] != 0x5A || (wire[1] & 0xFF) != 0xA5) {
            throw new IllegalArgumentException("Not a 5AA5 frame");
        }
        int ctr = ((wire[wire.length - 2] & 0xFF) << 8) | (wire[wire.length - 1] & 0xFF);
        return ctr == 0 ? decryptNonSn(wire) : decryptSn(wire, ctr);
    }

    private byte[] deriveKey(byte[] k1, byte[] k2) throws Exception {
        byte[] a = new byte[16];
        byte[] b = new byte[16];
        if (k1 != null) System.arraycopy(k1, 0, a, 0, Math.min(16, k1.length));
        if (k2 != null) System.arraycopy(k2, 0, b, 0, Math.min(16, k2.length));
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(a);
        sha1.update(b);
        return Arrays.copyOf(sha1.digest(), 16);
    }

    private byte[] aes(byte[] block) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
        return c.doFinal(block);
    }

    private byte[] encryptNonSn(byte[] plain) throws Exception {
        byte[] ks = aes(nonSnEcbInput);
        int bodyLen = plain.length - 3;
        byte[] out = new byte[plain.length + 6];
        System.arraycopy(plain, 0, out, 0, 3);
        for (int i = 0; i < bodyLen; i++) out[3 + i] = (byte)(plain[3 + i] ^ ks[i % 16]);
        int sum = 0;
        for (int i = 3; i < plain.length; i++) sum = (sum + (plain[i] & 0xFF)) & 0xFFFF;
        int chk = (~sum) & 0xFFFF;
        int p = plain.length;
        out[p] = 0;
        out[p + 1] = 0;
        out[p + 2] = (byte)(chk & 0xFF);
        out[p + 3] = (byte)((chk >>> 8) & 0xFF);
        out[p + 4] = 0;
        out[p + 5] = 0;
        return out;
    }

    private byte[] decryptNonSn(byte[] wire) throws Exception {
        if (wire.length < 13) throw new IllegalArgumentException("Short non-SN frame");
        int plainLen = wire.length - 6;
        byte[] out = new byte[plainLen];
        System.arraycopy(wire, 0, out, 0, 3);
        byte[] ks = aes(nonSnEcbInput);
        for (int i = 3; i < plainLen; i++) out[i] = (byte)(wire[i] ^ ks[(i - 3) % 16]);
        int sum = 0;
        for (int i = 3; i < plainLen; i++) sum = (sum + (out[i] & 0xFF)) & 0xFFFF;
        int calc = (~sum) & 0xFFFF;
        int got = (wire[plainLen + 2] & 0xFF) | ((wire[plainLen + 3] & 0xFF) << 8);
        if (calc != got) throw new IllegalArgumentException("Non-SN checksum mismatch");
        return out;
    }

    private byte[] encryptSn(byte[] plain) throws Exception {
        int ctr = ++txCounter;
        byte[] nonce = nonce(ctr);
        byte[] tag = mac(plain, nonce);
        byte[] body = Arrays.copyOfRange(plain, 3, plain.length);
        byte[] cryptBody = ctrXor(body, nonce, 1);
        byte[] a0 = ctrBlock(nonce, 0);
        byte[] a0ks = aes(a0);
        byte[] encTag = new byte[4];
        for (int i = 0; i < 4; i++) encTag[i] = (byte)(tag[i] ^ a0ks[i]);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(plain, 0, 3);
        os.write(cryptBody, 0, cryptBody.length);
        os.write(encTag, 0, 4);
        os.write((ctr >>> 8) & 0xFF);
        os.write(ctr & 0xFF);
        return os.toByteArray();
    }

    private byte[] decryptSn(byte[] wire, int ctr) throws Exception {
        if (ctr <= rxCounter) throw new IllegalArgumentException("Replay/out-of-order counter " + ctr);
        int bodyLen = wire.length - 3 - 6;
        if (bodyLen < 4) throw new IllegalArgumentException("Short SN frame");
        byte[] nonce = nonce(ctr);
        byte[] cryptBody = Arrays.copyOfRange(wire, 3, 3 + bodyLen);
        byte[] body = ctrXor(cryptBody, nonce, 1);
        byte[] plain = new byte[3 + body.length];
        System.arraycopy(wire, 0, plain, 0, 3);
        System.arraycopy(body, 0, plain, 3, body.length);

        byte[] a0ks = aes(ctrBlock(nonce, 0));
        byte[] rawTag = new byte[4];
        for (int i = 0; i < 4; i++) rawTag[i] = (byte)(wire[3 + bodyLen + i] ^ a0ks[i]);
        byte[] calcTag = mac(plain, nonce);
        if (!MessageDigest.isEqual(rawTag, calcTag)) throw new IllegalArgumentException("MAC mismatch");
        rxCounter = ctr;
        return plain;
    }

    private byte[] nonce(int ctr) {
        byte[] n = new byte[13];
        n[0] = (byte)((ctr >>> 24) & 0xFF);
        n[1] = (byte)((ctr >>> 16) & 0xFF);
        n[2] = (byte)((ctr >>> 8) & 0xFF);
        n[3] = (byte)(ctr & 0xFF);
        System.arraycopy(auth, 0, n, 4, 8);
        n[12] = 0;
        return n;
    }

    private byte[] ctrBlock(byte[] nonce, int i) {
        byte[] b = new byte[16];
        b[0] = 0x01;
        System.arraycopy(nonce, 0, b, 1, 13);
        b[14] = 0;
        b[15] = (byte)(i & 0xFF);
        return b;
    }

    private byte[] ctrXor(byte[] data, byte[] nonce, int startIndex) throws Exception {
        byte[] out = new byte[data.length];
        int off = 0;
        int idx = startIndex;
        while (off < data.length) {
            byte[] ks = aes(ctrBlock(nonce, idx++));
            int n = Math.min(16, data.length - off);
            for (int j = 0; j < n; j++) out[off + j] = (byte)(data[off + j] ^ ks[j]);
            off += n;
        }
        return out;
    }

    private byte[] mac(byte[] plain, byte[] nonce) throws Exception {
        int payloadLen = plain.length - 3;
        byte[] b0 = new byte[16];
        b0[0] = 0x59;
        System.arraycopy(nonce, 0, b0, 1, 13);
        b0[14] = 0;
        b0[15] = (byte)(payloadLen & 0xFF);
        byte[] x = aes(b0);

        byte[] aad = new byte[16];
        System.arraycopy(plain, 0, aad, 0, 3);
        x = aes(xor16(x, aad));

        byte[] body = Arrays.copyOfRange(plain, 3, plain.length);
        for (int off = 0; off < body.length; off += 16) {
            byte[] block = new byte[16];
            int n = Math.min(16, body.length - off);
            System.arraycopy(body, off, block, 0, n);
            x = aes(xor16(x, block));
        }
        return Arrays.copyOf(x, 4);
    }

    private byte[] xor16(byte[] a, byte[] b) {
        byte[] o = new byte[16];
        for (int i = 0; i < 16; i++) o[i] = (byte)(a[i] ^ b[i]);
        return o;
    }
}
