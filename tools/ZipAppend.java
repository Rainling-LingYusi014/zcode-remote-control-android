import java.io.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把 classes.dex 追加进 aapt2 产出的 APK。
 * 逐条目复制并保留原压缩方式（resources.arsc 保持 STORED），新增条目用 DEFLATED。
 * 用法：java ZipAppend <apk> <file> <entryName>
 */
public class ZipAppend {
    public static void main(String[] args) throws Exception {
        String apkPath = args[0], fileToAdd = args[1], entryName = args[2];
        File apk = new File(apkPath), add = new File(fileToAdd);
        File tmp = new File(apkPath + ".tmp");
        byte[] buf = new byte[65536];
        try (ZipFile src = new ZipFile(apk);
             ZipOutputStream dst = new ZipOutputStream(new FileOutputStream(tmp))) {
            Enumeration<? extends ZipEntry> es = src.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                ZipEntry n = new ZipEntry(e.getName());
                if (e.getMethod() == ZipEntry.STORED) {
                    long size = e.getSize();
                    byte[] data = new byte[(int) size];
                    try (InputStream in = src.getInputStream(e)) {
                        int off = 0;
                        while (off < size) {
                            int r = in.read(data, off, (int) (size - off));
                            if (r < 0) break;
                            off += r;
                        }
                    }
                    n.setSize(size);
                    n.setCompressedSize(size);
                    n.setCrc(e.getCrc());
                    n.setMethod(ZipEntry.STORED);
                    dst.putNextEntry(n);
                    dst.write(data, 0, (int) size);
                    dst.closeEntry();
                } else {
                    n.setMethod(ZipEntry.DEFLATED);
                    dst.putNextEntry(n);
                    try (InputStream in = src.getInputStream(e)) {
                        int r;
                        while ((r = in.read(buf)) > 0) dst.write(buf, 0, r);
                    }
                    dst.closeEntry();
                }
            }
            ZipEntry n = new ZipEntry(entryName);
            n.setMethod(ZipEntry.DEFLATED);
            dst.putNextEntry(n);
            try (InputStream in = new FileInputStream(add)) {
                int r;
                while ((r = in.read(buf)) > 0) dst.write(buf, 0, r);
            }
            dst.closeEntry();
        }
        if (!apk.delete() || !tmp.renameTo(apk)) throw new IOException("替换 APK 失败: " + apkPath);
        System.out.println("dex injected: " + entryName);
    }
}
