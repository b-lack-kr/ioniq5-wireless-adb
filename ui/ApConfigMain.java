package neev.adb;
import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
/** Root-only generation; never writes credentials to stdout. */
public final class ApConfigMain {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid()!=0 || args.length!=0) System.exit(1);
        File base=new File("/data/nemesis/blackr-wireless-adb-ap/config");
        if (!base.getCanonicalFile().equals(base.getAbsoluteFile()) || !base.isDirectory()) System.exit(2);
        File conf=new File(base,"hotspot.conf"), tmp=new File(base,"hotspot.conf.new");
        if (conf.exists()) { WifiInfo.read(conf,new WifiInfo.Ownership(){public void verify(java.io.FileInputStream in) throws Exception { Class<?> c=Class.forName("libcore.io.Libcore"); Object os=c.getField("os").get(null); Object stat=Class.forName("libcore.io.Os").getMethod("fstat",java.io.FileDescriptor.class).invoke(os,in.getFD()); WifiInfo.verifyOwnership(stat.getClass().getField("st_uid").getInt(stat),stat.getClass().getField("st_mode").getInt(stat)); }}); return; }
        if (tmp.exists() || !tmp.getCanonicalFile().equals(tmp.getAbsoluteFile())) System.exit(3);
        SecureRandom random=new SecureRandom(); String alphabet="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder pw=new StringBuilder(); for(int i=0;i<20;i++) pw.append(alphabet.charAt(random.nextInt(alphabet.length())));
        String data=String.format(java.util.Locale.US,"AP_SSID=Hyundai_%06d\nAP_PASS=%s\n",random.nextInt(1000000),pw.toString());
        FileOutputStream out=new FileOutputStream(tmp); try { out.write(data.getBytes("US-ASCII")); out.getFD().sync(); } finally { out.close(); }
        if (!tmp.setReadable(false,false) || !tmp.setReadable(true,true) || !tmp.setWritable(false,false) || !tmp.setWritable(true,true) || !tmp.renameTo(conf)) { tmp.delete(); System.exit(4); }
    }
}
