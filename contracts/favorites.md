# NAS 共享收藏

Go 是唯一数据来源。同一 NAS 的后台、手机和电视共享一份收藏，不按账号或设备拆分。不读取、导入、合并或上传旧客户端收藏；不在客户端持久化收藏或建立离线写队列。手机练唱历史和录音作品保持本地存储。

后台/手机使用管理员 Cookie 访问 `/api/v1/favorites`，写入沿用 CSRF。电视使用设备 Bearer 访问 `/api/v1/tv/favorites`，不接受亲友参加者凭证，也不接受管理员 Cookie 代替设备凭证。

两个前缀均提供：

- `GET`：`q`（歌名/歌手，最多 200 字）、`availability=ALL|AVAILABLE|UNAVAILABLE`、`sort=RECENT|TITLE|ARTIST`、`page`（从 0 开始）、`size`（1～100）。搜索、筛选、稳定排序先于分页。返回 `items/page/size/totalElements/totalFavorites/revision`。条目沿用歌曲投影并增加 `available` 和 `addedAt`。
- `GET /ids`：完整 `songIds` 与 `revision`，用于曲库按钮及添加窗口状态。并非分页截取。
- `PUT`：`{"songIds":["歌曲ID"],"selected":true}`，每次 1～500 个不同 ID。显式设置目标状态，整批事务提交，返回最终完整 `songIds/revision`。重复相同状态不修改收藏时间和修订号。新增已消失歌曲返回 409 且整批不变；取消已不存在的关系是成功的空操作。

收藏保存歌曲 ID、首次加入时间和必要的元数据快照。歌曲仍在曲库时使用当前元数据；曲库中已移除则 `available=false`，保留原名称、歌手、专辑用于辨认并取消，封面使用占位。`available=true` 表示 ID 仍在曲库，不代表已经探测远程来源或播放成功。取消收藏不删除歌曲、缓存、练唱历史或原始媒体。

列表、ID 和写响应均禁止 HTTP 缓存。客户端仅在内存保留成功响应，读错保留可辨认的旧视图并提示读取失败，写错保持旧状态。分页汇总客户端需检查各页 `revision` 一致，集合变化时重新读取，避免漏项。切 NAS、退出登录立即清除集合并使迟到响应失效。页面进入、恢复前台和写后重新读取；前台约 30 秒轮询以发现其他终端的更改，刷新收藏不重建播放会话。

SQLite 与 MySQL 迁移：`202611140001_shared_favorites.sql`。收藏没有指向媒体的级联删除外键，数据库内一个共享状态行序列化批量写入。客户端契约见 OpenAPI `FavoriteChange/FavoriteState/FavoritePage`。
