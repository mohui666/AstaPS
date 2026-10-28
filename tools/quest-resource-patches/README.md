# 7.1 剧情资源增量

`7.1-story-repairs.patch` 包含本版主线、森林书、邀约、重要世界任务和缺失关卡重建的配套资源，来自资源修复提交范围 `c0213668..2b0526f9`，涉及 358 个文件。

先准备 [AstaPS-Resource](https://github.com/MeChen618/AstaPS-Resource) 的 7.1 资源；基线为 `c0213668889ed05cbeb86660f424c3daf18bfacd`，放在 MGC 根目录的 `resources/`。在 MGC 根目录执行：

```sh
git apply --check --directory=resources tools/quest-resource-patches/7.1-story-repairs.patch
git apply --directory=resources tools/quest-resource-patches/7.1-story-repairs.patch
```

将完整资源准备好后再应用此增量；第一条命令检查匹配情况，第二条实际写入。已应用本版修复的资源无需再次应用。补丁只包含这批已完成的资源改动，不包含整套客户端资源或玩家数据。

原版服务端脚本缺失的关卡采用本项目重建逻辑，战斗和阶段安排保留本版实现边界，详见 [剧情支持说明](../../docs/quest/runtime-support.md) 及资源中的 `Scripts/Scene/20238/CUSTOM_WAR_README.md`。自动检查通过不代表客户端全剧情通关。