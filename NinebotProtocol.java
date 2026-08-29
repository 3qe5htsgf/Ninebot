package ch.ninebot.f2lab;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class NinebotProtocol {
    public static final int PHONE = 0x3E;
    public static final int ESC = 0x20;
    public static final int BLE_LEGACY = 0x21;
    public static final int BLE_MODERN = 0x04;

    public static final int CMD_READ = 0x01;
    public static final int CMD_WRITE = 0x02;
    public static final int CMD_READ_ACK = 0x04;

    public static final int IDX_SPEED_LIMIT = 0x93;

    private NinebotProtocol() {}

    /** Plaintext frame used as input to Encryption2 (no transport checksum). */
    public static byte[] inner(int target, int cmd, int index, byte[] data) {
        byte[] d = data == null ? new byte[0] : data;
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(0x5A);
        os.write(0xA5);
        os.write(d.length & 0xFF);
        os.write(PHONE);
        os.write(target & 0xFF);
        os.write(cmd & 0xFF);
        os.write(index & 0xFF);
        os.write(d, 0, d.length);
        return os.toByteArray();
    }

    /** Protocol-2 unencrypted frame with the 15-bit inverted-sum checksum. */
    public static byte[] plain(int target, int cmd, int index, byte[] data) {
        byte[] p = inner(target, cmd, index, data);
        int sum = 0;
        for (int i = 2; i < p.length; i++) sum += p[i] & 0xFF;
        int crc = (~sum) & 0x7FFF;
        byte[] out = Arrays.copyOf(p, p.length + 2);
        out[out.length - 2] = (byte)(crc & 0xFF);
        out[out.length - 1] = (byte)((crc >>> 8) & 0xFF);
        return out;
    }

    public static byte[] read2(int target, int index) {
        // Protocol-2 READ carries the requested byte count as a single byte.
        return inner(target, CMD_READ, index, new byte[] {0x02});
    }

    public static byte[] writeU16(int target, int index, int rawValue) {
        return inner(target, CMD_WRITE, index,
                new byte[] {(byte)(rawValue & 0xFF), (byte)((rawValue >>> 8) & 0xFF)});
    }

    public static boolean looksLikeFrame(byte[] p) {
        return p != null && p.length >= 7 && p[0] == 0x5A && (p[1] & 0xFF) == 0xA5;
    }

    public static int source(byte[] p) { return p.length > 3 ? p[3] & 0xFF : -1; }
    public static int target(byte[] p) { return p.length > 4 ? p[4] & 0xFF : -1; }
    public static int cmd(byte[] p) { return p.length > 5 ? p[5] & 0xFF : -1; }
    public static int index(byte[] p) { return p.length > 6 ? p[6] & 0xFF : -1; }
    public static byte[] data(byte[] p) { return p.length > 7 ? Arrays.copyOfRange(p, 7, p.length) : new byte[0]; }

    public static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte v : b) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", v & 0xFF));
        }
        return sb.toString();
    }
}
