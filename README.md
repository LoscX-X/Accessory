# Accessory+

**插件版本 6.0.0 · Minecraft Java 26.3 · Paper / Folia · Java 25**

适用于 Minecraft Java 版 Paper / Folia 26.3 的饰品背包插件。只装 Accessory+ 就能按物品说明（Lore）识别饰品、限制装备槽位，并用 SQLite 保存玩家装备。服主还可以配置多页背包、玩家分组、套装、属性和技能。

## 安装与第一次装备

1. 将 `accessory+.jar` 放进服务器的 `plugins/`，重启服务器。配置会生成在 `plugins/accessoryplus/`。同一服务器安装一个饰品背包实现即可。
2. 用有 `/give` 权限的玩家在游戏内执行下方命令，领取一张带黑桃标签的纸。
3. 输入 `/accessory`，把纸放到「♠ 黑桃」下方的空格。关窗再打开，纸还在原位。平时也可以主手拿着饰品潜行右键，若有多个可用槽位，点击聊天中的选项即可。

```mcfunction
/minecraft:give @s minecraft:paper[minecraft:lore=[{text:"[♠]",color:"aqua",italic:false}]] 1
```

默认第一页接受 `[♠]`、`[♣]`、`[❤]`、`[♦]` 四种花色。每种花色有自己的槽位，底部中央接受任意花色。右下角箭头打开遗物页，接受带 `[Relics]` 的物品。以上步骤只需 Paper 或 Folia 与主 JAR。

## 自定义 Lore 饰品

**槽位识别标签写在 `plugins/accessoryplus/layouts/<页面名>.yml` 的 `slots.<槽号>.lore`。** 已有牌组页的规则在 `layouts/default.yml`，遗物页在 `layouts/extra.yml`。`frame.<名称>.lore` 则是玩家悬停在装饰或按钮上时看到的说明。

例如，要创建一个有戒指槽的新页面，在数据目录里新建 `layouts/rings.yml`。一行有 9 格，槽号从 0 开始，`"4"` 是第一行正中间。

```yaml
# layouts/rings.yml
size: 9
title: '<gold>戒指'

slots:
  "4":
    lore:
      - '[戒指]'
      - '[Ring]'
```

在 `profiles/default.yml` 将已有的 `inventory.pages` 改为下面的列表。这会把默认档案的第一页设为戒指页。同文件的 `rules` 可以继续保留。

```yaml
# profiles/default.yml
inventory:
  pages:
    - rings
```

执行 `/accessory reload`，再用下方命令领取戒指。打开背包，把它放进中间的槽位。

```mcfunction
/minecraft:give @s minecraft:gold_nugget[minecraft:lore=[{text:"[戒指]"}]] 1
```

- **匹配方式**　任何一行 Lore 命中列表中的一个独立标签即可。文字颜色、样式和英文大小写不影响识别。建议每个标签单写一行，配置中用引号包住带方括号的文本。
- **同时使用物品类型**　槽位配置了 `types` 时，已知类型的物品按类型匹配。没有类型的物品可以用同槽位的 `lore` 匹配。只使用 Lore 的服务器直接配置 `lore` 即可。
- **识别属性数值**　要把 `生命 +4` 这类说明转为原版属性，在 `config.yml` 启用 `attribute.lore.enable` 并设置 `attribute.lore.mappings`。槽位的标签识别无需开启这个开关。

## 常用配置

| 要调整的内容 | 数据目录下的文件 |
| --- | --- |
| 页面标题、大小、装备标签、槽位权限、翻页按钮 | `layouts/*.yml` |
| 页面顺序、不同世界或权限组使用的背包 | `profiles/*.yml` |
| 快捷装备、禁止卸下的 Lore、数量上限、死亡处理、存储 | `config.yml` |
| 按物品 ID 指定可装备的类型 | `item-types.yml` |
| 套装成员与件数奖励、饰品技能 | `sets/*.yml`、`skills/*.yml` |
| 提示文字和布局译文 | `lang/<语言>.yml`。简中为 `zh_CN.yml`，槽位权限不足用 `messages.Slot_no_permission` |

修改布局、分组、语言或套装后执行 `/accessory reload`。护盾等 AuraSkills 特性系数在 `stats.yml`，修改后重启服务器。可以直接参考[默认牌组页](src/main/resources/layouts/default.yml)、[VIP 分组](src/main/resources/profiles/vip.yml)和[套装示例](src/main/resources/sets/example.yml)。

## 可选联动

服主安装主 JAR 和想用的目标插件即可。原版饰品、Lore 和背包可以独立运行。

| 插件 | 安装后可用的内容 |
| --- | --- |
| MythicMobs | 用 `mythic:物品ID` 识别装备，或让饰品和套装触发 Mythic 技能。默认技能执行器为 `mythic`。 |
| AuraSkills | 技能等级条件、护盾与吸血特性。要启用它作为外部属性来源，设置 `attribute.provider: auraskills`。 |
| PlaceholderAPI | 在其他插件中显示套装件数、套装名与冷却，例如 `%acc_set_pieces_star%`、`%acc_cd_1%`。 |

另提供 AttributePlus 属性适配。其 3.3.3.5 版在 Paper 26.3 上会报告版本不兼容。

## 命令与权限

| 命令 | 用途 | 权限 |
| --- | --- | --- |
| `/accessory`、`/acc`、`/acre` | 打开自己的饰品背包 | 普通玩家 |
| `/accessory view <玩家>` | 只读查看在线玩家的装备 | `accessory.view` |
| `/accessory reload` | 校验并重载配置 | `accessory.reload` |
| `/accessory clear <玩家>` | 清空指定玩家的饰品 | `accessory.clear` |
| `/accessory migrate <来源> <目标> --dry-run` | 预演迁移并检查冲突，正式复制时去掉 `--dry-run` | 服务器控制台 |

管理员权限 `accessory.admin` 包含查看、重载、清空和两种护盾命令的权限。启用对应 AuraSkills 特性后可用 `/shield` 和 `/magicshield`。

## 存储与旧服升级

新服默认使用 SQLite，数据库是 `plugins/accessoryplus/accessory.db`。在 `config.yml` 设置 `storage.type: yml` 才会新建 `contents/`，已有 `contains/` 的老服继续使用原目录。其他模式包括 `mysql`、`redis` 和退出即清空的 `only-ram`。

旧服升级前停服备份配置和玩家数据，保留原存储类型。更换后端时，让玩家下线并停掉共用数据库的其他服务器。例如从 YML 迁移到 SQLite，在控制台先执行 `accessory migrate yml sqlite --dry-run`，再执行 `accessory migrate yml sqlite`。核对复制结果后，把 `storage.type` 改为 `sqlite` 并重启。

多服共享饰品时，各服连接同一个 MySQL 数据库或同一个 Redis 数据库和键前缀，启用 `storage.cross-server.enabled`，为每台服设置唯一的 `server-id`。连接和租期配置见[主配置](src/main/resources/config.yml)。

## 开发与构建

使用 JDK 25。Windows 运行 `.\gradlew.bat build`，Linux 或 macOS 运行 `./gradlew build`。首次构建会下载并校验 Paper 26.3 build 205 SDK 和构建依赖。发行包生成在 `dist/26.3/accessory+.jar`，开发者可用 `:api:jar` 单独构建 API。

开发者在插件描述中声明对 `accessoryplus` 的依赖，即可通过 `AccessoryApi` 访问饰品、分组、技能与扩展服务。跨区域查看或编辑使用 `AccessoryService.open`，对同一所有者的写操作可通过 `onOwner` 排队执行。[公开 API](api/src/main/java/com/cendreal/accessory/api)
