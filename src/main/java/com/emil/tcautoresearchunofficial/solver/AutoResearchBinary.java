package com.emil.tcautoresearchunofficial.solver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.emil.tcautoresearchunofficial.Config;
import com.emil.tcautoresearchunofficial.TCAutoResearchUnofficial;

/**
 * 求解器可执行文件管理。
 *
 * <p>老版本把 {@code AutoResearch.dll} 解压到<b>游戏工作目录</b>，并且每帧/每次操作都靠
 * {@code tasklist} 猜 PID；这里改成解压到 {@code config/solver/}，
 * 用 {@link Process} 句柄直接管理生命周期。</p>
 *
 * <p>求解器是作者提供的 Windows 原生程序（PE x64），算法保持不变，
 * 仅把它隔离成一个可替换的后端。</p>
 */
public final class AutoResearchBinary {

    private static final String ZIP_RESOURCE = "/AutoResearch.zip";
    private static final String FILE_NAME = "AutoResearch.dll";
    /** 原 jar 内置文件的字节数，用于判断是否需要重新解压。 */
    private static final long EXPECTED_SIZE = 2222592L;

    private static File cached;

    private AutoResearchBinary() {
    }

    public static boolean isSupportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    public static synchronized File ensureExtracted() throws IOException {
        if (cached != null && cached.isFile() && cached.length() == EXPECTED_SIZE) {
            return cached;
        }
        File dir = new File(Config.directory(), "solver");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir);
        }
        File out = new File(dir, FILE_NAME);
        if (out.isFile() && out.length() == EXPECTED_SIZE) {
            cached = out;
            return out;
        }

        InputStream raw = AutoResearchBinary.class.getResourceAsStream(ZIP_RESOURCE);
        if (raw == null) {
            throw new IOException("模组内置的 " + ZIP_RESOURCE + " 缺失");
        }
        ZipInputStream zis = new ZipInputStream(raw);
        try {
            ZipEntry entry;
            boolean found = false;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".dll")) {
                    continue;
                }
                copy(zis, out);
                found = true;
                break;
            }
            if (!found) {
                throw new IOException("压缩包里没有可执行文件");
            }
        } finally {
            closeQuietly(zis);
            closeQuietly(raw);
        }

        if (!out.setExecutable(true) && !out.canExecute()) {
            TCAutoResearchUnofficial.LOG.warn("无法给求解器设置可执行权限: {}", out);
        }
        TCAutoResearchUnofficial.LOG.info("求解器已解压到 {} ({} 字节)", out, out.length());
        cached = out;
        return out;
    }

    private static void copy(InputStream in, File out) throws IOException {
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                os.write(buffer, 0, read);
            }
            os.flush();
        } finally {
            closeQuietly(os);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
