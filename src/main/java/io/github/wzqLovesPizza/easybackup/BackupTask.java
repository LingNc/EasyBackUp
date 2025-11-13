package io.github.wzqLovesPizza.easybackup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupTask {

    private final EasyBackUp plugin;
    private final FileConfiguration config;
    private volatile boolean broadcastProgress = false;

    public static class Result {
        public final boolean success;
        public final long filesCount;
        public final long totalBytes;
        public final String message;

        public Result(boolean success, long filesCount, long totalBytes, String message) {
            this.success = success;
            this.filesCount = filesCount;
            this.totalBytes = totalBytes;
            this.message = message;
        }
    }

    public BackupTask(EasyBackUp plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    public Result runOnce() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());

        boolean ifBroadcast = config.getBoolean("notify-players", true);
        this.broadcastProgress = ifBroadcast; // 进度广播跟随 notify-players
        List<String> targetsCfg = config.getStringList("target-save-paths");
        if (targetsCfg == null || targetsCfg.isEmpty()) {
            // 兼容旧版键名
            targetsCfg = config.getStringList("target-save-dir");
        }
        if (targetsCfg == null || targetsCfg.isEmpty()) {
            plugin.getLogger().warning("配置 target-save-paths 为空，未执行备份。");
            return new Result(false, 0, 0, "无目标");
        }

    File serverRoot = resolveServerRoot();

        // 解析输出目录
        String outPath = config.getString("output-dir", "backups");
        File outputDir = new File(outPath);
        if (!outputDir.isAbsolute()) {
            outputDir = new File(serverRoot, outPath);
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            plugin.getLogger().severe("无法创建备份输出目录: " + outputDir.getAbsolutePath());
            return new Result(false, 0, 0, "输出目录创建失败");
        }

        File zipFile = new File(outputDir, "EasyBackUp_" + timestamp + ".zip");

        // 解析排除
        Set<String> excludeDirs = toLowerCaseSet(config.getStringList("exclude-dirs"));
        Set<String> excludeFiles = toLowerCaseSet(config.getStringList("exclude-files"));
        Set<String> excludeExts = toLowerCaseSet(config.getStringList("exclude-extensions"));
        excludeFiles.add("session.lock"); // 总是排除

        int progressEvery = Math.max(1, config.getInt("progress-every-files", 500));
        int bufferKB = Math.max(16, config.getInt("buffer-size-kb", 64));

        // 收集有效目标
        List<File> targets = new ArrayList<>();
        for (String p : targetsCfg) {
            if (p == null || p.trim().isEmpty()) continue;
            File f = new File(serverRoot, p);
            if (f.exists()) {
                targets.add(f);
            } else {
                plugin.getLogger().warning("目标不存在: " + p);
            }
        }
        if (targets.isEmpty()) {
            return new Result(false, 0, 0, "无有效目标");
        }

        long totalFiles = countFiles(serverRoot, targets, excludeDirs, excludeFiles, excludeExts);

        // 广播开始
        if (ifBroadcast) {
            String start = ChatColor.translateAlternateColorCodes('&', "&a[EasyBackUp] &3正在备份，可能引起短时间卡顿...");
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(start));
        }

        // 主线程: save-all + save-off
        try {
            runSyncCommand("save-all flush");
            runSyncCommand("save-off");
        } catch (Exception e) {
            plugin.getLogger().warning("调用 save-all/save-off 失败: " + e.getMessage());
        }

        long processed = 0;
        boolean success = false;
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(zipFile), bufferKB * 1024);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            byte[] buffer = new byte[bufferKB * 1024];
            for (File t : targets) {
                processed = zipAny(serverRoot, t, zos, excludeDirs, excludeFiles, excludeExts, buffer, processed, totalFiles, progressEvery);
            }
            success = true;
        } catch (IOException e) {
            plugin.getLogger().severe("备份失败: " + e.getMessage());
        } finally {
            // 主线程: save-on
            try {
                runSyncCommand("save-on");
            } catch (Exception e) {
                plugin.getLogger().warning("调用 save-on 失败: " + e.getMessage());
            }
        }

        long zipSize = zipFile.exists() ? zipFile.length() : 0L;

        // 清理历史
        cleanOldBackups(outputDir);

        if (ifBroadcast) {
            final boolean ok = success;
            Bukkit.getScheduler().runTask(plugin, () -> {
                String end = ChatColor.translateAlternateColorCodes('&', ok ? "&a[EasyBackUp] &3备份完成." : "&c[EasyBackUp] &3备份失败.");
                Bukkit.broadcastMessage(end);
            });
        }

        return new Result(success, totalFiles, zipSize, success ? "OK" : "FAILED");
    }

    private long countFiles(File serverRoot, List<File> targets, Set<String> excludeDirs, Set<String> excludeFiles, Set<String> excludeExts) {
        long count = 0;
        for (File t : targets) {
            count += countRec(t, excludeDirs, excludeFiles, excludeExts);
        }
        return count;
    }

    private long countRec(File f, Set<String> excludeDirs, Set<String> excludeFiles, Set<String> excludeExts) {
        if (!f.exists()) return 0;
        if (f.isDirectory()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (excludeDirs.contains(name)) return 0;
            long c = 0;
            File[] list = f.listFiles();
            if (list != null) {
                for (File x : list) c += countRec(x, excludeDirs, excludeFiles, excludeExts);
            }
            return c;
        } else {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (excludeFiles.contains(name)) return 0;
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String ext = name.substring(dot + 1);
                if (excludeExts.contains(ext)) return 0;
            }
            return 1;
        }
    }

    private long zipAny(File serverRoot, File f, ZipOutputStream zos, Set<String> excludeDirs, Set<String> excludeFiles, Set<String> excludeExts,
                        byte[] buffer, long processed, long totalFiles, int progressEvery) throws IOException {
        if (!f.exists()) return processed;
        if (f.isDirectory()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (excludeDirs.contains(name)) return processed;
            File[] list = f.listFiles();
            if (list != null) {
                for (File x : list) {
                    processed = zipAny(serverRoot, x, zos, excludeDirs, excludeFiles, excludeExts, buffer, processed, totalFiles, progressEvery);
                }
            }
        } else {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (excludeFiles.contains(name)) return processed;
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String ext = name.substring(dot + 1);
                if (excludeExts.contains(ext)) return processed;
            }
            String entryName;
            try {
                entryName = serverRoot != null ? serverRoot.toURI().relativize(f.toURI()).getPath() : f.getName();
                if (entryName == null || entryName.isEmpty()) {
                    entryName = f.getName();
                }
            } catch (Exception ex) {
                entryName = f.getName();
            }
            try {
                zos.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f), buffer.length)) {
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
                processed++;
                if (processed % progressEvery == 0) {
                    String msg = "备份进度: " + processed + (totalFiles > 0 ? ("/" + totalFiles + " (" + percent(processed, totalFiles) + ")") : "") + " 文件...";
                    plugin.getLogger().info(msg);
                    if (broadcastProgress) {
                        final String bmsg = org.bukkit.ChatColor.translateAlternateColorCodes('&', "&a[EasyBackUp] &3" + msg);
                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(bmsg));
                    }
                }
            } catch (IOException e) {
                if (!f.getName().equals("session.lock")) {
                    plugin.getLogger().warning("跳过文件 " + f.getName() + ": " + e.getMessage());
                }
            }
        }
        return processed;
    }

    private void runSyncCommand(String command) throws ExecutionException, InterruptedException {
        Future<?> future = Bukkit.getScheduler().callSyncMethod(plugin, (Callable<Object>) () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return null;
        });
        future.get();
    }

    private void cleanOldBackups(File outputDir) {
        File[] files = outputDir.listFiles((dir, name) -> name.startsWith("EasyBackUp_") && name.endsWith(".zip"));
        int maxBackups = Math.max(0, config.getInt("max-backups", 10));
        if (maxBackups == 0) return; // 0 表示不清理
        if (files != null && files.length > maxBackups) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < files.length - maxBackups; i++) {
                if (!files[i].delete()) {
                    plugin.getLogger().warning("无法删除旧备份：" + files[i].getName());
                } else {
                    plugin.getLogger().info("已删除旧备份：" + files[i].getName());
                }
            }
        }
    }

    private static Set<String> toLowerCaseSet(List<String> list) {
        Set<String> set = new HashSet<>();
        if (list != null) {
            for (String s : list) if (s != null) set.add(s.toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private static String percent(long a, long b) {
        if (b <= 0) return "";
        double p = (a * 100.0) / b;
        if (p >= 100) return "100%";
        if (p <= 0) return "0%";
        return String.format(java.util.Locale.ROOT, "%.1f%%", p);
    }

    private File resolveServerRoot() {
        try {
            File wc = Bukkit.getWorldContainer();
            if (wc != null) return wc.getAbsoluteFile();
        } catch (Throwable ignored) {}

        try {
            File df = plugin.getDataFolder();
            if (df != null) {
                File parent = df.getParentFile(); // plugins/
                if (parent != null) {
                    File root = parent.getParentFile(); // server root
                    if (root != null) return root.getAbsoluteFile();
                    return parent.getAbsoluteFile();
                }
                return df.getAbsoluteFile();
            }
        } catch (Throwable ignored) {}

        return new File(".").getAbsoluteFile();
    }
}
