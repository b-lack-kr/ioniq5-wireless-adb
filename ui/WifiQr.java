package neev.adb;

import io.nayuki.qrcodegen.QrCode;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/** In-memory only. Called after an explicit reveal; never logs or exports credentials. */
final class WifiQr {
    static final int QUIET_MODULES = 4;
    static final int WHITE = 0xFFFFFFFF;
    static final int BLACK = 0xFF000000;

    static String payload(String ssid, String password) {
        if (ssid == null || password == null || ssid.length() == 0
            || ssid.length() > 32 || password.length() < 8 || password.length() > 63)
            throw new IllegalArgumentException("Invalid Wi-Fi fields");
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c < 32 || c > 126) throw new IllegalArgumentException("Invalid Wi-Fi fields");
        }
        try {
            if (ssid.getBytes("UTF-8").length > 32) throw new IllegalArgumentException("Invalid Wi-Fi fields");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 unavailable");
        }
        return "WIFI:T:WPA;S:" + escape(ssid) + ";P:" + escape(password) + ";;";
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127) throw new IllegalArgumentException("Invalid Wi-Fi fields");
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    static Image render(String ssid, String password, int maxPixels) {
        byte[] data;
        try { data = payload(ssid, password).getBytes("UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new IllegalStateException("UTF-8 unavailable"); }
        final QrCode qr;
        try { qr = QrCode.encodeBinary(data, QrCode.Ecc.MEDIUM); }
        finally { Arrays.fill(data, (byte) 0); }
        int modules = qr.size + QUIET_MODULES * 2;
        int scale = Math.min(maxPixels, 1024) / modules;
        if (scale < 2) throw new IllegalArgumentException("QR area too small");
        int side = modules * scale;
        int[] pixels = new int[side * side];
        Arrays.fill(pixels, WHITE);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (!qr.getModule(x, y)) continue;
                int left = (x + QUIET_MODULES) * scale;
                int top = (y + QUIET_MODULES) * scale;
                for (int yy = top; yy < top + scale; yy++)
                    Arrays.fill(pixels, yy * side + left, yy * side + left + scale, BLACK);
            }
        }
        return new Image(side, scale, qr.size, pixels);
    }

    static final class Image {
        final int size, modulePixels, modules;
        private int[] pixels;
        Image(int size, int modulePixels, int modules, int[] pixels) {
            this.size = size; this.modulePixels = modulePixels; this.modules = modules; this.pixels = pixels;
        }
        int[] pixels() {
            if (pixels == null) throw new IllegalStateException("QR image cleared");
            return pixels;
        }
        void clear() { if (pixels != null) Arrays.fill(pixels, WHITE); pixels = null; }
    }
}
