# 列车剧情摆件重建

`QUEST_EXEC_CHANGE_PLAYER_TRAIN_FIGURE_SGV`（138）的原版整数 ID → SGV 对照仍未恢复。
在用户授权重建缺失关卡逻辑后，AstaPS 使用独立配置
`src/main/resources/defaults/data/QuestTrainFigures.json` 建立以下**自定义映射**。
可在部署的 `data/QuestTrainFigures.json` 覆盖这份配置。配置必须保留 `reconstructed: true`。

| 任务参数中的摆件 ID | 本次重建的真实 SGV | 客户端显隐对象 |
|---:|---|---|
| 1 | SGV_TrainCollection_01 | Collection_01 |
| 2 | SGV_TrainCollection_02 | Collection_02 |
| 3 | SGV_TrainCollection_03 | Collection_03 |
| 4 | SGV_TrainCollection_04 | Collection_04 |
| 5 | SGV_TrainCollection_05 | Collection_05 |
| 6 | SGV_TrainCollection_06 | Collection_06 |
| 7 | SGV_TrainCollection_07 | Collection_07 |
| 8 | SGV_TrainCollection_08 | Collection_08 |
| 9 | SGV_CarriageWallpaper_01 | CarriageWallpaper_01 |
| 10 | SGV_CarriageWallpaper_02 | CarriageWallpaper_02 |
| 11 | SGV_CarriageWallpaper_03 | CarriageWallpaper_03 |
| 12 | SGV_CarriageWallpaper_04 | CarriageWallpaper_04 |
| 13 | SGV_CarriageWallpaper_05 | CarriageWallpaper_05 |
| 14 | SGV_CarriageWallpaper_06 | CarriageWallpaper_06 |

这些对应是重建设计，不是从数组次序推导的原版编号。14 个 SGV 的实际消费者来自
`resources/BinOutput/Ability/Temp/GadgetAbilities/ConfigAbility_Scene_V7_0_SnezhnayaTrain.json`
的 `SceneObj_Area_Zd_Property_SnezhnayaTrain_Interior_Head_01`：
`SnezhnayaTrain_CollectionManager` 为每项声明 `AttachModifierToGlobalValueMixin`，
其 Enable/Disable modifier 通过 `EnableSceneTransformByName` 控制表中的真实对象。

原任务文本、对话、奖励参数保持原样。例如主线 700917 的 `[3,2,1]` 显示 `Collection_02`；
世界任务 7767601 的 `[3,14,1]` 显示 `CarriageWallpaper_06`。当前资源实际使用的摆件 ID
为 1、2、3、4、5、6、14。

第一个参数 `3` 在此重建中明确解释为提瓦特场景 3，未宣称这是未恢复的原版语义。
动作要求玩家当前就在该场景且持有灰眼睛 220133；7002 的原始奖励 107002 已授予该道具。
服务器按玩家 UID 的列车记录找到或生成其本人列车，不能由客户端传入别人的实体 ID。
最后一个参数只接受 0（隐藏）或 1（显示）。

14 项的重建初始值为 0，使剧情奖励产生真实的显示变化；这替换了旧实现抓包中
“所有 Collection 已显示”的初始值。结果写入 `Player.trainFigures`，立即发送现有
`ServerGlobalValueChangeNotify`，并写入实体运行时 SGV；重登、重新进入场景和实体刷新
都从保存值还原。只影响玩家自己的列车，站台和 NPC 列车保留原行为。

定向测试检查真实资源中的 14 个显隐消费者、两个原任务参数、七个在用 ID、归属和场景限制、
重复执行、保存后恢复，以及初始实体能力包中保存值覆盖旧默认值。尚未做客户端实机游玩验证。
