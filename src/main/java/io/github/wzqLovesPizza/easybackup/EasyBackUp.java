package io.github.wzqLovesPizza.easybackup;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class EasyBackUp extends JavaPlugin {

    private Logger log;

    private BukkitTask scheduledTask;
    private long nextRunAtMillis = -1L;
    private volatile LastBackupInfo lastBackupInfo;
    private final Object backupLock = new Object();
    private volatile boolean isBackingUp = false;

    public static class LastBackupInfo {
        public final long timestampMillis;
        public final boolean success;
        public final long filesCount;
        public final long totalBytes;
        public final long durationMillis;
        public final String message;

        public LastBackupInfo(long timestampMillis, boolean success, long filesCount, long totalBytes, long durationMillis, String message) {
            this.timestampMillis = timestampMillis;
            this.success = success;
            this.filesCount = filesCount;
            this.totalBytes = totalBytes;
            this.durationMillis = durationMillis;
            this.message = message;
        }
    }

    @Override
    public void onEnable() {
        log = getLogger();
        saveDefaultConfig();
        scheduleFromConfig();
        log.info("EasyBackUp 插件已启用。");
    }

    @Override
    public void onDisable() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
    }

    private void scheduleFromConfig() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }

        int intervalSeconds = getIntervalSecondsFromConfig();
        if (intervalSeconds <= 0) {
            log.warning("自动备份已关闭（未设置有效 interval 或为 0S）。可使用 /ebu now 手动备份。");
            nextRunAtMillis = -1L;
            return;
        }

        long periodTicks = Math.max(20L, 20L * intervalSeconds);
        nextRunAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(intervalSeconds);
        scheduledTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            // 每次开始前更新下一次时间（近似）
            int sec = getIntervalSecondsFromConfig();
            nextRunAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, sec));
            runBackupNow(null);
        }, 0L, periodTicks);

        List<String> targets = getConfig().getStringList("target-save-paths");
        if (targets == null || targets.isEmpty()) {
            // 兼容旧配置键名（可保留）
            targets = getConfig().getStringList("target-save-dir");
        }
        log.info("计划每隔 " + intervalSeconds + " 秒自动备份：" + targets);
    }

    public void setLastBackupInfo(LastBackupInfo info) {
        this.lastBackupInfo = info;
    }

    private static String bytesToHuman(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return new DecimalFormat("0.0").format(kb) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return new DecimalFormat("0.0").format(mb) + " MB";
        double gb = mb / 1024.0;
        return new DecimalFormat("0.00").format(gb) + " GB";
    }

    public void runBackupNow(CommandSender initiator) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (isBackingUp) {
                if (initiator != null) initiator.sendMessage("§e[EasyBackUp] 备份正在进行中，请稍候...");
                return;
            }
            synchronized (backupLock) {
                if (isBackingUp) {
                    if (initiator != null) initiator.sendMessage("§e[EasyBackUp] 备份正在进行中，请稍候...");
                    return;
                }
                isBackingUp = true;
            }
            if (initiator != null) initiator.sendMessage("§a[EasyBackUp] 开始备份...");
            BackupTask task = new BackupTask(this);
            long start = System.currentTimeMillis();
            BackupTask.Result result = task.runOnce();
            long dur = System.currentTimeMillis() - start;
            setLastBackupInfo(new LastBackupInfo(System.currentTimeMillis(), result.success, result.filesCount, result.totalBytes, dur, result.message));

            String summary = (result.success ? "§a备份完成" : "§c备份失败") +
                    "，文件数: " + result.filesCount +
                    "，压缩包大小: " + bytesToHuman(result.totalBytes) +
                    "，耗时: " + (dur / 1000.0) + "s";
            if (initiator != null) initiator.sendMessage("§a[EasyBackUp] " + summary);
            log.info("[EasyBackUp] " + summary);
            isBackingUp = false;
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!Objects.equals(command.getName(), "ebu")) return false;

        if (args.length == 0) {
            sender.sendMessage("§e用法: /ebu now|status|reload|set <key> <value>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "backup": // 兼容旧用法
            case "now":
                if (!sender.hasPermission("ebu.now")) {
                    sender.sendMessage("§c你没有权限执行此命令。");
                    return true;
                }
                runBackupNow(sender);
                return true;
            case "status":
                if (!sender.hasPermission("ebu.status")) {
                    sender.sendMessage("§c你没有权限。");
                    return true;
                }
                if (lastBackupInfo == null) {
                    sender.sendMessage("§e暂无备份记录。");
                } else {
                    sender.sendMessage("§a上次备份: " + (lastBackupInfo.success ? "成功" : "失败"));
                    sender.sendMessage("§7文件数: " + lastBackupInfo.filesCount + ", 大小: " + bytesToHuman(lastBackupInfo.totalBytes) + ", 用时: " + (lastBackupInfo.durationMillis/1000.0) + "s");
                }
                if (isBackingUp) {
                    sender.sendMessage("§6当前状态: 正在备份中...");
                }
                if (nextRunAtMillis > 0) {
                    long left = nextRunAtMillis - System.currentTimeMillis();
                    if (left < 0) left = 0;
                    long h = TimeUnit.MILLISECONDS.toHours(left);
                    long m = TimeUnit.MILLISECONDS.toMinutes(left - TimeUnit.HOURS.toMillis(h));
                    long s = TimeUnit.MILLISECONDS.toSeconds(left - TimeUnit.HOURS.toMillis(h) - TimeUnit.MINUTES.toMillis(m));
                    sender.sendMessage("§7距离下次自动备份还有: " + h + "h " + m + "m " + s + "s");
                } else {
                    sender.sendMessage("§7自动备份: 已关闭");
                }
                return true;
            case "reload":
                if (!sender.hasPermission("ebu.reload")) {
                    sender.sendMessage("§c你没有权限。");
                    return true;
                }
                reloadConfig();
                scheduleFromConfig();
                sender.sendMessage("§a配置已重载并应用。");
                return true;
            case "set":
                if (!sender.hasPermission("ebu.set")) {
                    sender.sendMessage("§c你没有权限。");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("§e用法: /ebu set <key> <value>");
                    sender.sendMessage("§7可用key: interval, output-dir, max-backups, notify-players");
                    return true;
                }
                String key = args[1];
                String value = args[2];
                switch (key.toLowerCase()) {
                    case "interval":
                        try {
                            int secs = parseDurationSeconds(value);
                            getConfig().set("interval", value);
                            saveConfig();
                            scheduleFromConfig();
                            sender.sendMessage("§a已设置自动备份间隔为 " + secs + " 秒。");
                        } catch (IllegalArgumentException e) {
                            sender.sendMessage("§c格式错误：支持 xDxHxMxS，如 1D2H30M，或 3H / 5M / 45S。");
                        }
                        return true;
                    case "output-dir":
                        getConfig().set("output-dir", value);
                        saveConfig();
                        sender.sendMessage("§a已设置输出目录为: " + value);
                        return true;
                    case "max-backups":
                        try {
                            int n = Integer.parseInt(value);
                            getConfig().set("max-backups", n);
                            saveConfig();
                            sender.sendMessage("§a已设置最大备份数为 " + n);
                        } catch (NumberFormatException e) {
                            sender.sendMessage("§c请输入数字。");
                        }
                        return true;
                    case "notify-players":
                        boolean flag = Boolean.parseBoolean(value);
                        getConfig().set("notify-players", flag);
                        saveConfig();
                        sender.sendMessage("§a已设置广播开关为 " + flag);
                        return true;
                    default:
                        sender.sendMessage("§c未知 key。可用: interval, output-dir, max-backups, notify-players");
                        return true;
                }
            default:
                sender.sendMessage("§e未知子命令。用法: /ebu now|status|reload|set <key> <value>");
                return true;
        }
    }

    // 解析间隔字符串为秒，支持: 1D2H30M45S / 3H / 5M / 45S / 大小写均可；允许空格分段
    private static int parseDurationSeconds(String input) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("empty");
        String s = input.trim().toLowerCase().replaceAll("\\s+", "");
        int total = 0;
        int num = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                int d = c - '0';
                num = (num < 0) ? d : (num * 10 + d);
            } else {
                if (num < 0) throw new IllegalArgumentException("missing number before unit");
                switch (c) {
                    case 'd': total += num * 86400; break;
                    case 'h': total += num * 3600; break;
                    case 'm': total += num * 60; break;
                    case 's': total += num; break;
                    default: throw new IllegalArgumentException("unknown unit: " + c);
                }
                num = -1;
            }
        }
        if (num >= 0) {
            // 没有单位的尾数，视为秒不安全；这里直接报错更清晰
            throw new IllegalArgumentException("unit required for trailing number");
        }
        if (total < 0) total = 0;
        return total;
    }

    private int getIntervalSecondsFromConfig() {
        String iv = getConfig().getString("interval", "6H");
        try {
            int sec = parseDurationSeconds(iv);
            return Math.max(0, sec);
        } catch (Exception e) {
            return 0;
        }
    }
}