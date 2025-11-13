## EasyBackUp

一个简单、稳定、对新人友好的 Minecraft 服务器备份插件（Paper/Spigot/Bukkit）。

它会将你选择的文件与目录一起打成一个 ZIP 包，默认存放在服务器根目录的 `backups/` 下；支持自动备份、手动备份、热重载配置、状态查询、排除列表、进度与大小提示等。

---

## 支持

- 将多个“目录或文件”一次性打包成一个 `.zip`
- 自动备份（可配置间隔，`0` 关闭）
- 备份前执行 `save-all` 并 `save-off`；备份完成 `save-on`
- 可配置输出目录（相对路径=相对服务器根目录；也支持绝对路径，兼容 Win/Linux）
- 备份数量上限，超出自动删除最旧的备份
- 排除目录/文件/后缀名
- 控制台进度提示与压缩结果大小展示，增大缓冲区提升速度
- 支持热重载配置与运行时修改关键参数
- 命令：`now` 立即备份、`status` 查看状态、`reload` 重载、`set` 修改配置

---

## 快速开始

1) 将构建出的 `EasyBackUp.jar` 放到服务器 `plugins/` 目录。

2) 启动一次服务器，会生成默认配置 `plugins/EasyBackUp/config.yml`，并在服务器根目录创建 `backups/`（默认输出目录）。

3) 修改配置后可在游戏内执行 `/ebu reload` 热重载；或重启服务器。

---

## 配置项

- `interval`: 自动备份间隔（字符串），支持 `xDxHxMxS`，如 `6H`、`1D2H30M`、`45S`、`5M`；设为 `0S` 或留空则关闭自动备份
- `target-save-paths`: 需要备份的“相对服务器根目录”的路径列表，既可填目录也可填单个文件
- `output-dir`: 备份输出目录
	- 相对路径：相对服务器根目录（默认 `backups`）
	- 绝对路径：直接使用（支持 Win/Linux）
- `max-backups`: 最多保留多少个 ZIP（按修改时间删除最旧）
- `notify-players`: 开始/结束是否全服公告
- `exclude-dirs`: 要排除的目录名（仅按名称匹配）
- `exclude-files`: 要排除的文件名（仅按名称匹配）
- `exclude-extensions`: 要排除的后缀名（例如 `log`, `tmp`，无需带点）
- `progress-every-files`: 处理多少个文件输出一次进度到控制台（默认 500）
- `buffer-size-kb`: 压缩时的缓冲区大小（默认 64）

完整配置见仓库内 `src/main/resources/config.yml` 注释。

---

## 命令与权限

默认权限等级为 OP。

| 命令 | 说明 | 权限 |
|------|------|------|
| `/ebu now` 或 `/ebu backup` | 立即执行一次备份 | `ebu.now` |
| `/ebu status` | 显示上次备份时间、结果、压缩包大小、用时、以及距离下次自动备份还剩多久 | `ebu.status` |
| `/ebu reload` | 热重载配置 | `ebu.reload` |
| `/ebu set <key> <value>` | 修改常用配置（如 `interval`、`output-dir` 等）并自动重载 | `ebu.set` |

**示例：**
- `/ebu set interval 1D2H30M`（1天2小时30分）
- `/ebu set interval 3H`
- `/ebu set interval 5M`
- `/ebu set interval 45S`



<!-- ## 工作原理（简述）

1) 插件启动后读取配置并按间隔调度异步任务。
2) 每次备份时，先在主线程执行：`save-all` → `save-off`，确保磁盘写入稳定。
3) 异步线程对 `target-save-paths` 中的所有目标统一打一个 ZIP（应用排除规则，增大缓冲提升速度）。
4) 打包完成后在主线程执行：`save-on`；随后按 `max-backups` 清理历史包。
5) 记录本次备份的信息（成功/失败、大小、用时、目标数量），用于 `/ebu status` 展示。

--- -->

<!-- ## 说明

本插件使用稳定 Bukkit API，未用 NMS，通常可直接在 1.21.x 上运行。如需“显式对齐”，可将 `plugin.yml` 的 `api-version` 调整为 `1.21` 并用对应版本的 Spigot API 构建。 -->
