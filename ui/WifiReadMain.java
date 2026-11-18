package neev.adb;
/** Root-only one-shot; writes credentials only to the caller's private process pipe. */
public final class WifiReadMain {
    public static void main(String[] args) {
        if(args.length!=0 || android.os.Process.myUid()!=0) System.exit(1);
        WifiInfo value=null;
        try {
            value=WifiInfo.load();
            System.out.print("AP_SSID="+value.ssid+"\nAP_PASS="+value.passwordLabel(true)+"\n");
        } catch(Throwable failure) { System.exit(1); }
        finally { if(value!=null)value.clear(); }
    }
}
