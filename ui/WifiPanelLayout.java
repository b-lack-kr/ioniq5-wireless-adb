package neev.adb;

/** Fixed rows plus a scrollable credential column keep QR and buttons on screen. */
final class WifiPanelLayout {
    final int padding, heading, message, note, buttons, gap, qr, body;
    private WifiPanelLayout(int width, int height) {
        padding = 12; heading = 32; message = 42; note = 34; buttons = 48; gap = 12;
        body = Math.max(0, height - 2 * padding - heading - message - note - buttons);
        qr = Math.max(0, Math.min(280, Math.min(body, (width - 2 * padding - gap) / 3)));
    }
    static WifiPanelLayout forDisplay(int widthPixels, int heightPixels, float density) {
        if (!(density > 0)) density = 1;
        return new WifiPanelLayout(Math.round(widthPixels / density), Math.round(heightPixels / density));
    }
}
