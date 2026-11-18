package neev.adb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.util.Arrays;

/** Explicit on-screen read only. Never logs, exports, executes or changes AP settings. */
final class WifiInfo {
    static final String CONFIG = "/data/nemesis/neev-usbcp/config/hotspot.conf";
    static final int LIMIT = 8192;
    final String ssid;
    private char[] password;

    private WifiInfo(String ssid, String password) {
        this.ssid = ssid;
        this.password = password.toCharArray();
    }

    static final class Unavailable extends Exception {
        final String code;
        Unavailable(String code) { super(code); this.code = code; }
        String userMessage() {
            if ("MISSING".equals(code)) {
                return "Wi-Fi 설정이 없습니다. 무선 ADB를 시작한 뒤 다시 확인하세요.";
            }
            if ("UNTRUSTED".equals(code)) {
                return "Wi-Fi 설정 파일의 소유자 또는 경로를 확인하지 못했습니다. 무선 ADB의 연결 상태를 확인하세요.";
            }
            if ("INVALID".equals(code)) {
                return "저장된 Wi-Fi 이름이나 암호 형식이 올바르지 않습니다. 무선 ADB의 연결 상태를 확인하세요.";
            }
            return "Wi-Fi 접속 정보를 읽지 못했습니다. 무선 ADB를 시작한 뒤 다시 눌러 주세요.";
        }
    }

    interface Ownership {
        void verify(FileInputStream input) throws Exception;
    }

    private static final class AndroidOwnership implements Ownership {
        public void verify(FileInputStream input) throws Exception {
            // Android 4.4 libcore: inspect the opened descriptor, not a second
            // path lookup, so replacement after opening cannot change ownership.
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Class<?> os = Class.forName("libcore.io.Os");
            Object instance = libcore.getField("os").get(null);
            Object stat = os.getMethod("fstat", FileDescriptor.class).invoke(instance, input.getFD());
            int uid = stat.getClass().getField("st_uid").getInt(stat);
            int mode = stat.getClass().getField("st_mode").getInt(stat);
            verifyOwnership(uid, mode);
        }
    }

    static void verifyOwnership(int uid, int mode) throws Unavailable {
        if (uid != 0 || (mode & 0170000) != 0100000 || (mode & 0022) != 0) {
            throw new Unavailable("UNTRUSTED");
        }
    }

    static WifiInfo load() throws Unavailable {
        if (android.os.Process.myUid() == 0) {
            File marker = new File("/data/nemesis/blackr-wireless-adb-session/ap-mode");
            try {
                if (marker.isFile() && marker.getCanonicalFile().equals(marker.getAbsoluteFile())) {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(marker));
                    String mode; try { mode=r.readLine(); } finally { r.close(); }
                    if ("OWN".equals(mode)) return read(new File("/data/nemesis/blackr-wireless-adb-ap/config/hotspot.conf"), new AndroidOwnership());
                }
            } catch (Exception e) { throw new Unavailable("READ_FAILED"); }
            return read(new File(CONFIG), new AndroidOwnership());
        }
        java.lang.Process child=null;
        java.util.Timer timer=null;
        try {
            child = new ProcessBuilder("/system/xbin/nemesis", "root", "-c",
                "CLASSPATH=/data/nemesis/apps/blackr.wireless.adb/runtime/wifi-reader.jar /system/bin/app_process /system/bin neev.adb.WifiReadMain")
                .redirectErrorStream(true).start();
            final java.lang.Process watched=child;
            timer=new java.util.Timer(true);
            timer.schedule(new java.util.TimerTask(){public void run(){watched.destroy();}},5000L);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] part=new byte[512]; int n;
            while((n=child.getInputStream().read(part))!=-1) {
                if(out.size()+n>LIMIT) throw new Unavailable("INVALID");
                out.write(part,0,n);
            }
            if(child.waitFor()!=0) throw new Unavailable("READ_FAILED");
            return parse(out.toString("US-ASCII"));
        } catch (Unavailable failure) { throw failure; }
        catch (Exception failure) { throw new Unavailable("READ_FAILED"); }
        finally { if(timer!=null)timer.cancel(); if(child!=null) child.destroy(); }
    }

    // Package-private seam permits host regression tests with disposable files.
    static WifiInfo read(File file, Ownership owner) throws Unavailable {
        try {
            if (!file.getAbsolutePath().equals(file.getCanonicalPath())) {
                throw new Unavailable("UNTRUSTED");
            }
            if (!file.exists()) throw new Unavailable("MISSING");
            if (!file.isFile() || file.length() <= 0 || file.length() > LIMIT) {
                throw new Unavailable("INVALID");
            }
            FileInputStream input = new FileInputStream(file);
            try {
                owner.verify(input);
                if (!file.getAbsolutePath().equals(file.getCanonicalPath())) {
                    throw new Unavailable("UNTRUSTED");
                }
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] part = new byte[512];
                int n;
                try {
                    while ((n = input.read(part)) != -1) {
                        if (data.size() + n > LIMIT) throw new Unavailable("INVALID");
                        data.write(part, 0, n);
                    }
                    return parse(data.toString("US-ASCII"));
                } finally { Arrays.fill(part, (byte) 0); }
            } finally { input.close(); }
        } catch (Unavailable unavailable) {
            throw unavailable;
        } catch (Exception unavailable) {
            // Do not retain a cause: no config values or filesystem details may
            // reach the generic UI exception logger.
            throw new Unavailable("READ_FAILED");
        }
    }

    static WifiInfo parse(String config) throws Unavailable {
        if (config.length() > LIMIT) throw new Unavailable("INVALID");
        String ssid = null, password = null;
        for (String raw : config.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("AP_SSID=")) {
                if (ssid != null) throw new Unavailable("INVALID");
                ssid = line.substring(8);
            } else if (line.startsWith("AP_PASS=")) {
                if (password != null) throw new Unavailable("INVALID");
                password = line.substring(8);
            }
        }
        if (ssid == null || !ssid.matches("Hyundai_[0-9]{6}") || password == null
            || !password.matches("[ -~]{8,63}")) throw new Unavailable("INVALID");
        return new WifiInfo(ssid, password);
    }

    String passwordLabel(boolean revealed) {
        if (password == null) return "";
        return revealed ? new String(password) : "••••••••••••";
    }

    void clear() {
        if (password != null) Arrays.fill(password, '\0');
        password = null;
    }
}
