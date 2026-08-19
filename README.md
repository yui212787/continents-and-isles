# 大陆与群岛 (Continents and Isles)

一个 Minecraft NeoForge 1.21.1 世界生成模组，生成单一超大陆与外围群岛。

## 中文介绍

### 功能特性

- **超大陆** — 生成单一巨型大陆（半径可配置，默认约 4250 格），拥有自然的海岸线与沙滩
- **六大扇区** — 大陆按扇区划分，各具主题：雪原、山脉、丛林、热带草原、沙漠、群岛
- **WiFi 形山脉扇区** — 同心弧状山脊与峡谷交错，越靠近环山带越高
- **环山带** — 环绕整片大陆，随机分布钻石、金、红石、铁、铜、煤、青金石矿脉（大/中/小型随机）
- **三大湖泊** — 东湖（岛湖）、山湖（深湖，50% 概率生成天池环山效果）、随机扇区湖（群系湖）
- **群岛扇区** — 内海散布小岛，中心必定生成一座蘑菇岛，海洋神殿周围保留 300 格半径的深海区
- **必生结构** — 沙漠扇区必定生成沙漠神殿、必定生成林地府邸、沼泽湖中生成女巫小屋、海洋神殿
- **完全可配置** — 大陆半径、扇区主/副群系及比例、三大湖泊生成开关等均可通过配置文件调整
- **模组群系支持** — 安装其他群系模组时，以约 12% 的概率混入模组群系

### 制作说明

- 本模组**完全由 AI 制作**
- 当前版本已基本满足需求，后续是否更新视情况而定

### 冲突与兼容性

- 已简单测试过热门的数个群系和结构生成模组，**未发现冲突**
- 注意：加入「地牢浮现之时」（When Dungeons Arise）后，**必定会在出生点位置生成一个结构**
- 尚未精细测试各类群系模组的生成，暂不了解是否存在群系和结构无法生成的现象
- 超大陆的内海群岛以及超大陆之外的群岛，会随机生成单一群系（包含位于地下的群系）的岛屿，理应兼容各类群系生成

### 运行要求

- Minecraft 1.21.1
- NeoForge 21.1.x

### 安装

1. 安装 NeoForge 21.1.x（Minecraft 1.21.1 对应版本）
2. 将 `continents_and_isles-x.x.x.jar` 放入 `.minecraft/mods` 文件夹
3. 创建新世界时，在"更多世界类型"中选择 **「大陆与群岛」** 预设

### 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。

---

## English Introduction

**Continents and Isles** is a Minecraft NeoForge 1.21.1 world generation mod that creates a single vast supercontinent with natural coastlines, surrounded by scattered islands in the outer ocean.

- **Six themed sectors**: Snowy Plains, Mountains, Jungle, Savanna, Desert, and Archipelago
- **WiFi-shaped mountain sector** with concentric arc ridges and valleys, rising toward the encircling ring mountain belt
- **Ring mountain belt** with randomly placed ore veins (diamond, gold, redstone, iron, copper, coal, lapis)
- **Three great lakes**, including a deep mountain lake with a chance of a Tianchi (ring) effect
- **Guaranteed structures**: desert temple, woodland mansion, witch hut, and ocean monument
- **Fully configurable**: continent radius, sector biomes, and lake generation via config file
- **Modded biome support**: ~12% chance to mix in biomes from other biome mods

This mod was **entirely made by AI**. The current version largely meets the author's needs; future updates depend on circumstances.

**Compatibility**: Several popular biome and structure mods have been lightly tested with no conflicts found. Note that with "When Dungeons Arise" installed, a structure always generates at the spawn point. Other biome mods have not been exhaustively tested.

**Requirements**: Minecraft 1.21.1 + NeoForge 21.1.x. Place the jar in `.minecraft/mods` and select the "Continents and Isles" preset when creating a world.

Licensed under the [MIT License](LICENSE).

---

Built with [NeoForged MDK](https://github.com/NeoForged/NeoForge).
