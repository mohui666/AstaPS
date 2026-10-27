# 历史 UP 顺序表

`history.json` 根据 [Samsara 的历史表](https://github.com/benlei/samsara-web/blob/main/public/data/banners.yaml)生成，当前 7.1 两期与 [UIGF 的公告缓存](https://github.com/UIGF-org/CurrentBannerWatcher/blob/main/banner-data.json)逐 ID 核对。快照取于 2026-10-26，覆盖 1.0 至 7.1，计 108 个阵容阶段。Samsara 数据的 MIT 授权见同目录 `samsara-LICENSE.txt`。

按版本与阶段排序，Luna I–VIII 位于 5.8 与 7.0 之间。源表中复刻条目的日期存在复用，故不以日期排序或展示未经核对的起止日期。同阶段多角色池、武器池及集录池的 UP 合并进 AstaPS 现有混合池；只重放 UP 阵容，抽取消耗、概率、非 UP 池和保底沿用原配置。

1.3 的角色池与武器池错开更新，拆为四阶段：2 月 3 日魈＋和璞鸢/磐岩结绿；2 月 17 日刻晴＋上述武器；2 月 23 日刻晴＋护摩之杖/狼的末路；3 月 2 日胡桃＋上述武器。可在[历史记录](https://bannerschedule.com/zh/genshin/banner-history/)对照。

下载上述两个源文件到 `local/gacha-sources/` 后运行 `python tools/gacha/generate-history.py`。脚本通过本地角色表和 `ITEM_USE_GAIN_AVATAR` 映射原生奖励 ID，排除测试角色卡，并核对本地物品星级。服务端离线加载打包的 JSON。
