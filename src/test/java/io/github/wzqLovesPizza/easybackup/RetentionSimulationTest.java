package io.github.wzqLovesPizza.easybackup;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快速模拟备份生成 & 分层保留行为，便于开发阶段观察清理前后的差异。
 */
class RetentionSimulationTest {

    private static final SimpleDateFormat NAME_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    @Test
    void tieredRetentionProducesLayeredResult() throws Exception {
        TimeZone originalTz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        NAME_FORMAT.setTimeZone(TimeZone.getDefault());
        Path tempDir = Files.createTempDirectory("easybackup-retention-sim");
        try {
            Instant base = Instant.parse("2024-11-15T12:00:00Z");
            long[] hoursAgo = {1, 3, 8, 20, 26, 40, 55, 72, 96, 120, 168, 220};
            List<File> created = new ArrayList<>();
            for (long h : hoursAgo) {
                created.add(createBackup(tempDir, base.minus(h, ChronoUnit.HOURS)));
            }

            System.out.println("\n--- 初始备份列表 ---");
            created.stream()
                    .sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified()))
                    .forEach(f -> System.out.println(formatEntry(f, base)));

            List<Map<String, Object>> tiers = Arrays.asList(
                    tier("1D", List.of("30M", "4H", "8H", "12H"), null, null, 0),
                    tier("7D", Collections.emptyList(), "1D", 1.5, 4),
                    tier("30D", List.of("7D", "14D"), null, null, 0)
            );

            File[] files = snapshotZipFiles(tempDir);
            if (files.length == 0) {
                throw new IllegalStateException("未生成测试备份文件");
            }

            BackupTask.applyTieredRetention(files, tiers, 10, Logger.getLogger("RetentionSimulation"), base.toEpochMilli());

            List<File> remaining = collectBackups(tempDir);

            System.out.println("--- 清理后备份列表 ---");
            remaining.forEach(f -> System.out.println(formatEntry(f, base)));

            assertTrue(remaining.size() <= 10, "总数量应受到 max-total 限制");

            long shortTerm = remaining.stream()
                    .filter(f -> ChronoUnit.HOURS.between(Instant.ofEpochMilli(f.lastModified()), base) <= 24)
                    .count();
            assertEquals(4, shortTerm, "1 天窗口应被 4 个短期备份占满");

            boolean hasFourDay = remaining.stream()
                    .anyMatch(f -> ChronoUnit.HOURS.between(Instant.ofEpochMilli(f.lastModified()), base) >= 24 * 4);
            assertTrue(hasFourDay, "应至少保留 1 个超过 4 天的备份");

            boolean hasFiveDay = remaining.stream()
                    .anyMatch(f -> ChronoUnit.HOURS.between(Instant.ofEpochMilli(f.lastModified()), base) >= 24 * 5);
            assertTrue(hasFiveDay, "应至少保留 1 个超过 5 天的备份");
        } finally {
            TimeZone.setDefault(originalTz);
            deleteRecursively(tempDir);
        }
    }

    @Test
    void hourlyBackupsShowTopTenList() throws Exception {
        TimeZone originalTz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        NAME_FORMAT.setTimeZone(TimeZone.getDefault());
        Path tempDir = Files.createTempDirectory("easybackup-hourly-demo");
        try {
            List<Map<String, Object>> tiers = Arrays.asList(
                    tier("1D", List.of("1H", "1H", "3H", "6H"), null, null, 0),
                    tier("7D", Collections.emptyList(), "12H", 2.0, 4),
                    tier("30D", List.of("10D", "15D"), null, null, 0)
            );

            Logger logger = Logger.getLogger("RetentionHourlyDemo");
            int totalBackups = 700;
            Instant finalNow = Instant.parse("2025-11-15T11:43:07Z");
            Instant start = finalNow.minus(totalBackups - 1L, ChronoUnit.HOURS);
            Set<Integer> watchPoints = new LinkedHashSet<>();
            int watchLimit = Math.min(700, totalBackups);
            for (int w = 1; w <= watchLimit; w++) {
                watchPoints.add(w);
            }
            if (totalBackups > watchLimit) {
                watchPoints.add(totalBackups);
            }
            List<File> lastRetained = Collections.emptyList();

            for (int i = 0; i < totalBackups; i++) {
                Instant current = start.plus(i, ChronoUnit.HOURS);
                File newBackup = createBackup(tempDir, current);

                File[] snapshot = snapshotZipFiles(tempDir);
                BackupTask.applyTieredRetention(snapshot, tiers, 10, logger, current.toEpochMilli());

                List<File> retained = collectBackups(tempDir);
                assertTrue(retained.size() <= 10, "max-total 限制应生效");
                lastRetained = retained;

                if (watchPoints.contains(i + 1)) {
                    System.out.printf("\n# 第%04d次备份 -> %s%n", i + 1, newBackup.getName());
                    printTopTen(retained, current);
                }
            }

            assertEquals(10, lastRetained.size(), "最终应只保留 10 个备份");
            long tenthAgeHours = ChronoUnit.HOURS.between(Instant.ofEpochMilli(lastRetained.get(9).lastModified()), finalNow);
            assertTrue(tenthAgeHours >= 240, "第 10 个备份应至少追溯 10 天，以展示跨层效果");
        } finally {
            TimeZone.setDefault(originalTz);
            deleteRecursively(tempDir);
        }
    }

    private static File createBackup(Path dir, Instant instant) throws IOException {
        String name = "EasyBackUp_" + NAME_FORMAT.format(Date.from(instant)) + ".zip";
        Path file = dir.resolve(name);
        Files.writeString(file, "dummy");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(instant));
        return file.toFile();
    }

    private static Map<String, Object> tier(String window, List<String> spacings, String minSpacing, Double growth, int keep) {
        Map<String, Object> tier = new HashMap<>();
        tier.put("window", window);
        if (!spacings.isEmpty()) {
            tier.put("spacings", spacings);
        }
        if (minSpacing != null) {
            tier.put("min-spacing", minSpacing);
        }
        if (growth != null) {
            tier.put("growth-multiplier", growth);
        }
        if (keep > 0) {
            tier.put("keep", keep);
        }
        return tier;
    }

    private static String formatEntry(File file, Instant base) {
        long ageHours = ChronoUnit.HOURS.between(Instant.ofEpochMilli(file.lastModified()), base);
        return String.format(Locale.ROOT, "%s  (距现在 %dh)", file.getName(), ageHours);
    }

    private static void printTopTen(List<File> retained, Instant referenceNow) {
        if (retained.isEmpty()) {
            System.out.println("  * 当前没有备份");
            return;
        }
        int limit = Math.min(10, retained.size());
        System.out.printf("  当前保留 %d 个，展示前 %d 个:%n", retained.size(), limit);
        for (int idx = 0; idx < limit; idx++) {
            File f = retained.get(idx);
            long ageHours = ChronoUnit.HOURS.between(Instant.ofEpochMilli(f.lastModified()), referenceNow);
            System.out.printf(Locale.ROOT, "  %02d. %s (距当前约 %dh)%n", idx + 1, f.getName(), ageHours);
        }
    }

    private static List<File> collectBackups(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .map(Path::toFile)
                    .filter(File::isFile)
                    .filter(f -> f.getName().startsWith("EasyBackUp_"))
                    .sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified()))
                    .collect(Collectors.toList());
        }
    }

    private static File[] snapshotZipFiles(Path dir) {
        File[] list = dir.toFile().listFiles((d, name) -> name.startsWith("EasyBackUp_") && name.endsWith(".zip"));
        return list != null ? list : new File[0];
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
