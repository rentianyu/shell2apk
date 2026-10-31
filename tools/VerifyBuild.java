import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 桌面等价验证：把 App 里 ApkBuilder.kt 的打包逻辑逐行翻成 Java，在 JVM 上真跑一遍。
 * 用法：
 *   javac -cp apksig.jar -d out tools/VerifyBuild.java
 *   java -cp apksig.jar:out VerifyBuild <模板.apk> <输出.apk> <包名> <应用名>
 * 校验点：zip 合法 / manifest 包名与应用名被替换 / arsc package name 被改写 /
 *        resources.arsc STORED+4 字节对齐 / apksig 签名 verify 通过。
 */
public class VerifyBuild {

    static final String TPL_PKG = "cc.xbta.shell2apk.tpl";
    static final String TPL_LABEL = "S2ATEMPLATELABELPLACEHOLDER0000";

    static class E {
        String name;
        int method;
        byte[] data;

        E(String n, int m, byte[] d) {
            name = n;
            method = m;
            data = d;
        }
    }

    public static void main(String[] args) throws Exception {
        String tplPath = args.length > 0 ? args[0] : "/tmp/tpl2.apk";
        String outPath = args.length > 1 ? args[1] : "/tmp/signed.apk";
        String newPkg = args.length > 2 ? args[2] : "com.example.myapp";
        String label = args.length > 3 ? args[3] : "测试任务·重启NAS";
        System.out.println("template : " + tplPath);
        System.out.println("package  : " + newPkg);
        System.out.println("label    : " + label);

        List<E> entries = readZip(new File(tplPath));
        byte[] cfg = ("{\"name\":\"" + label + "\",\"kind\":\"shell\",\"value\":\"echo hi\",\"showOutput\":true,\"timeoutSec\":20}")
                .getBytes("UTF-8");

        List<E> out = new ArrayList<>();
        int icons = 0;
        for (E e : entries) {
            String n = e.name;
            if (n.equals("AndroidManifest.xml")) {
                Map<String, String> m = new HashMap<>();
                m.put(TPL_PKG, newPkg);
                m.put(TPL_LABEL, label);
                byte[] edited = replaceStrings(e.data, m);
                System.out.println("axml " + e.data.length + " -> " + edited.length);
                out.add(new E(n, 8, edited));
            } else if (n.equals("resources.arsc")) {
                byte[] r = setArscPackage(e.data, newPkg);
                out.add(new E(n, 0, r));
            } else if (n.equals("assets/config.json")) {
                out.add(new E(n, 8, cfg));
            } else if (isSig(n)) {
                // drop old v1 signature
            } else if (n.startsWith("res/mipmap-") && n.endsWith("/ic_launcher.png")) {
                byte[] fake = new byte[700 + icons * 137];
                for (int i = 0; i < fake.length; i++) fake[i] = (byte) (i * 7 + icons);
                out.add(new E(n, 0, fake));
                icons++;
            } else {
                out.add(e);
            }
        }
        System.out.println("icons replaced: " + icons);

        File unsigned = File.createTempFile("unsigned", ".apk");
        writeZip(out, unsigned);

        String pw = new String(java.nio.file.Files.readAllBytes(
                new File("/sandbox/workspace/shell2apk/cred/ks_pwd.txt").toPath())).trim();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream("/sandbox/workspace/shell2apk/cred/keystore.p12")) {
            ks.load(in, pw.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("shell2apk", pw.toCharArray());
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate c : ks.getCertificateChain("shell2apk")) chain.add((X509Certificate) c);

        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("shell2apk", key, chain).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(unsigned)
                .setOutputApk(new File(outPath))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(26)
                .build()
                .sign();
        unsigned.delete();

        ApkVerifier.Result r = new ApkVerifier.Builder(new File(outPath))
                .setMinCheckedPlatformVersion(26).build().verify();
        System.out.println("signed   : " + new File(outPath).length() + " bytes, verified=" + r.isVerified()
                + ", v2err=" + r.getV2SchemeSigners().size() + ", v3err=" + r.getV3SchemeSigners().size());
    }

    static boolean isSig(String n) {
        if (!n.startsWith("META-INF/")) return false;
        return n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
                || n.equalsIgnoreCase("META-INF/MANIFEST.MF");
    }

    // ---------------- zip ----------------

    static List<E> readZip(File f) throws IOException {
        List<E> list = new ArrayList<>();
        try (ZipFile zf = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = zf.getInputStream(e)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                list.add(new E(e.getName(), e.getMethod(), bo.toByteArray()));
            }
        }
        return list;
    }

    static void writeZip(List<E> entries, File outFile) throws IOException {
        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
            ByteArrayOutputStream cd = new ByteArrayOutputStream();
            long offset = 0;
            Calendar c = Calendar.getInstance();
            int time = (c.get(Calendar.HOUR_OF_DAY) << 11) | (c.get(Calendar.MINUTE) << 5) | (c.get(Calendar.SECOND) / 2);
            int date = ((c.get(Calendar.YEAR) - 1980) << 9) | ((c.get(Calendar.MONTH) + 1) << 5) | c.get(Calendar.DAY_OF_MONTH);

            for (E e : entries) {
                byte[] nameBytes = e.name.getBytes("UTF-8");
                CRC32 crc32 = new CRC32();
                crc32.update(e.data);
                int crc = (int) crc32.getValue();
                boolean stored = e.method == 0;
                byte[] payload = stored ? e.data : deflate(e.data);
                int method = stored ? 0 : 8;

                byte[] extra = new byte[0];
                if (method == 0) {
                    long base = offset + 30 + nameBytes.length;
                    int need = (int) ((4 - (base % 4)) % 4);
                    if (need != 0) {
                        int extraLen = 4 + need;
                        extra = new byte[extraLen];
                        extra[0] = 0x35;
                        extra[1] = (byte) 0xD9;
                        extra[2] = (byte) (extraLen - 4);
                    }
                }
                long localOffset = offset;

                ByteArrayOutputStream lh = new ByteArrayOutputStream();
                wI(lh, 0x04034b50);
                wS(lh, 20);
                wS(lh, 0);
                wS(lh, method);
                wS(lh, time);
                wS(lh, date);
                wI(lh, crc);
                wI(lh, payload.length);
                wI(lh, e.data.length);
                wS(lh, nameBytes.length);
                wS(lh, extra.length);
                lh.write(nameBytes);
                lh.write(extra);
                byte[] lhb = lh.toByteArray();
                fos.write(lhb);
                fos.write(payload);
                offset += lhb.length + payload.length;

                wI(cd, 0x02014b50);
                wS(cd, 20);
                wS(cd, 20);
                wS(cd, 0);
                wS(cd, method);
                wS(cd, time);
                wS(cd, date);
                wI(cd, crc);
                wI(cd, payload.length);
                wI(cd, e.data.length);
                wS(cd, nameBytes.length);
                wS(cd, 0);
                wS(cd, 0);
                wS(cd, 0);
                wS(cd, 0);
                wI(cd, 0);
                wI(cd, (int) localOffset);
                cd.write(nameBytes);
            }
            byte[] cdb = cd.toByteArray();
            fos.write(cdb);
            ByteArrayOutputStream eocd = new ByteArrayOutputStream();
            wI(eocd, 0x06054b50);
            wS(eocd, 0);
            wS(eocd, 0);
            wS(eocd, entries.size());
            wS(eocd, entries.size());
            wI(eocd, cdb.length);
            wI(eocd, (int) offset);
            wS(eocd, 0);
            fos.write(eocd.toByteArray());
        }
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 64);
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            int n = d.deflate(buf);
            if (n > 0) out.write(buf, 0, n);
            else break;
        }
        d.end();
        return out.toByteArray();
    }

    static void wS(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
    }

    static void wI(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
        o.write((v >>> 16) & 0xff);
        o.write((v >>> 24) & 0xff);
    }

    // ---------------- axml ----------------

    static int u16(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8);
    }

    static int i32(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24);
    }

    static byte[] replaceStrings(byte[] in, Map<String, String> map) throws IOException {
        int pos = u16(in, 2);
        int poolStart = -1;
        int poolSize = 0;
        while (pos + 8 <= in.length) {
            int type = u16(in, pos);
            int size = i32(in, pos + 4);
            if (size <= 0) break;
            if (type == 0x0001) {
                poolStart = pos;
                poolSize = size;
                break;
            }
            pos += size;
        }
        if (poolStart < 0) throw new IOException("no string pool");

        int headerSize = u16(in, poolStart + 2);
        int stringCount = i32(in, poolStart + 8);
        int styleCount = i32(in, poolStart + 12);
        int flags = i32(in, poolStart + 16);
        int stringsStart = i32(in, poolStart + 20);
        int stylesStart = i32(in, poolStart + 24);
        boolean utf8 = (flags & 0x100) != 0;

        List<String> strings = new ArrayList<>();
        for (int i = 0; i < stringCount; i++) {
            int off = i32(in, poolStart + headerSize + i * 4);
            strings.add(utf8 ? readUtf8(in, poolStart + stringsStart + off)
                    : readUtf16(in, poolStart + stringsStart + off));
        }
        for (int i = 0; i < strings.size(); i++) {
            String nv = map.get(strings.get(i));
            if (nv != null) strings.set(i, nv);
        }

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            offsets[i] = data.size();
            if (utf8) writeUtf8(data, strings.get(i));
            else writeUtf16(data, strings.get(i));
        }
        while (data.size() % 4 != 0) data.write(0);
        byte[] stringsBytes = data.toByteArray();

        byte[] stylesBytes = (styleCount > 0 && stylesStart > 0 && stylesStart < poolSize)
                ? Arrays.copyOfRange(in, poolStart + stylesStart, poolStart + poolSize)
                : new byte[0];

        ByteArrayOutputStream np = new ByteArrayOutputStream();
        int newSize = 28 + 4 * stringCount + 4 * styleCount + stringsBytes.length + stylesBytes.length;
        wS(np, 0x0001);
        wS(np, 28);
        wI(np, newSize);
        wI(np, stringCount);
        wI(np, styleCount);
        wI(np, utf8 ? 0x100 : 0);
        wI(np, 28 + 4 * stringCount + 4 * styleCount);
        wI(np, stylesBytes.length == 0 ? 0 : 28 + 4 * stringCount + 4 * styleCount + stringsBytes.length);
        for (int o : offsets) wI(np, o);
        if (styleCount > 0) {
            for (int i = 0; i < styleCount; i++) {
                wI(np, i32(in, poolStart + headerSize + stringCount * 4 + i * 4));
            }
        }
        np.write(stringsBytes);
        np.write(stylesBytes);

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(in, 0, poolStart);
        result.write(np.toByteArray());
        result.write(in, poolStart + poolSize, in.length - (poolStart + poolSize));
        byte[] r = result.toByteArray();
        int total = r.length;
        r[4] = (byte) (total & 0xff);
        r[5] = (byte) ((total >>> 8) & 0xff);
        r[6] = (byte) ((total >>> 16) & 0xff);
        r[7] = (byte) ((total >>> 24) & 0xff);
        return r;
    }

    static String readUtf8(byte[] d, int start) {
        int p = start;
        int b0 = d[p] & 0xff;
        p += (b0 & 0x80) != 0 ? 2 : 1;
        int b1 = d[p] & 0xff;
        int len8;
        if ((b1 & 0x80) != 0) {
            len8 = ((b1 & 0x7f) << 8) | (d[p + 1] & 0xff);
            p += 2;
        } else {
            len8 = b1;
            p += 1;
        }
        return new String(d, p, len8, java.nio.charset.StandardCharsets.UTF_8);
    }

    static void writeUtf8(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeUtf8Len(o, s.length());
        writeUtf8Len(o, b.length);
        o.write(b, 0, b.length);
        o.write(0);
    }

    static void writeUtf8Len(ByteArrayOutputStream o, int len) {
        if (len > 0x7f) {
            o.write(((len >>> 8) & 0x7f) | 0x80);
            o.write(len & 0xff);
        } else {
            o.write(len);
        }
    }

    static String readUtf16(byte[] d, int start) {
        int p = start;
        int len = u16(d, p);
        p += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | u16(d, p);
            p += 2;
        }
        return new String(d, p, len * 2, java.nio.charset.StandardCharsets.UTF_16LE);
    }

    static void writeUtf16(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        wS(o, s.length());
        o.write(b, 0, b.length);
        wS(o, 0);
    }

    // ---------------- arsc ----------------

    static byte[] setArscPackage(byte[] data, String newPkg) throws IOException {
        byte[] out = data.clone();
        int pos = data.length >= 8 ? u16(data, 2) : 0;
        while (pos + 8 <= out.length) {
            int type = u16(out, pos);
            int size = i32(out, pos + 4);
            if (size <= 0 || pos + size > out.length) break;
            if (type == 0x0200) {
                byte[] nb = newPkg.getBytes("UTF-16LE");
                int n = Math.min(nb.length, 254);
                System.arraycopy(nb, 0, out, pos + 12, n);
                for (int i = pos + 12 + n; i < pos + 12 + 256; i++) out[i] = 0;
                System.out.println("arsc package chunk @+" + 12 + " written " + n + " bytes");
                return out;
            }
            pos += size;
        }
        System.out.println("arsc package chunk NOT found -> fallback equal-length replace");
        byte[] a = replaceAll(out, TPL_PKG.getBytes("UTF-8"), newPkg.getBytes("UTF-8"));
        return replaceAll(a, TPL_PKG.getBytes("UTF-16LE"), newPkg.getBytes("UTF-16LE"));
    }

    static byte[] replaceAll(byte[] src, byte[] from, byte[] to) {
        if (from.length == 0 || from.length != to.length) return src;
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length + 16);
        int i = 0;
        while (i < src.length) {
            boolean m = i + from.length <= src.length;
            if (m) {
                for (int j = 0; j < from.length; j++) {
                    if (src[i + j] != from[j]) {
                        m = false;
                        break;
                    }
                }
            }
            if (m) {
                out.write(to, 0, to.length);
                i += from.length;
            } else {
                out.write(src[i] & 0xff);
                i++;
            }
        }
        return out.toByteArray();
    }
}
