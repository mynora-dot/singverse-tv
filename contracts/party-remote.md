# 亲友点歌与 TV 遥控 v2

Go 为曲库、家庭收藏、反馈、队列、播放推进和互动事件的唯一权威；Vue 与 TV 只消费同一快照。所有下列接口为 `/api/v1` 下 Bearer 身份，不接受管理员 Cookie。

## 能力、快照与兼容

TV 使用 `POST /tv/sessions/{id}/capabilities` 提交 `{capabilities:["AUTO_ADVANCE","SEEK","AUDIO_ROLE","APPLY_AUDIO_ROLES","REACTIONS"]}`，每次启动及重连重新声明。快照 `capabilities` 仅包含服务端支持的能力；未声明的新命令返回 409，不排入电视命令。未声明 AUTO_ADVANCE 的旧 TV 保持原有交接协议，H5 提示更新版本。

快照补充 `queuePolicy`（FAIR 或 LEGACY）、`rotation`（点歌人 ID 数组）、`priorityItemId`、`manualStopped`、`sampledAt`、`tvOnline`、`tvSeenAt`、`commandResults`、`reactionCursor`、`reactions`。TV 约每秒拉取快照；设备拉取更新在线采样，15 秒无 TV 心跳视为离线。`stateRevision` 用于丢弃迟到快照；`revision` 仍为队列修订号。`playback` 补充总时长、位置采样时间及当前 `audioRole`（original/backing）。

## 公平轮唱与自动播放

FAIR 会话的 WAITING 由 Go 按 rotation 每人一首轮流投影，同一人的 FIFO 由入队序号保持。新点歌人加入当前轮转尾部，正在播放不参与重排。`SHUFFLE` 只改变点歌人轮转顺序。`TOP` 设置唯一的下一首优先位，再次置顶替换旧优先位；`UNTOP` 取消。优先项消费后恢复未消费的正常轮转，不修改原个人序号。旧 UP/DOWN 在 FAIR 下拒绝，避免破坏 FIFO。

所有有效加入者均可 TOP/UNTOP/SHUFFLE 和遥控。REMOVE 仅本人待唱或主持人；LOCK/UNLOCK/CLEAR/END/HOST/REVOKE 仍仅 TV 可操作。队列动作携带稳定 requestId 与 expectedRevision，同主体同请求重试不重复应用，冲突返回 409。

AUTO_ADVANCE 下空闲时第一首入队自动生成已确认 LOAD；NEXT 与当前实例的 ENDED 在同一会话事务锁内推进，新的 attemptId 和修订号确保并发只推进一次。Go 是唯一自动推进者，TV 不再在自然结束后自行 START。手动 STOP 持久化 manualStopped，新增点歌不恢复；明确 START/RESUME 才解除。recover 清理旧实例和旧能力声明并保持停止，TV 必须重新声明能力，避免进程重建擅自重播，也防止降级客户端继承新命令。

## 遥控与执行结果

沿用 `POST /party|tv/sessions/{id}/commands`：新增 SEEK `{positionMs}`、AUDIO_ROLE `{role:"original"|"backing"}`、APPLY_AUDIO_ROLES `{songId}`。FAIR 下 NEXT/PAUSE/RESUME/RESTART/STOP/SEEK/AUDIO_ROLE/APPLY_AUDIO_ROLES 必须绑定当前 `attemptId`；迟到请求返回 409，不能作用下一首。除改变队列的 NEXT/START 外，遥控不因进度采样增加队列修订号。requestId 去重先于修订/实例检查。

命令携带 `id/requestId/attemptId/action/playback` 及动作参数。TV 只执行当前实例的命令，执行后向 playback-events 报 `{commandId,commandStatus:"EXECUTED"|"FAILED",commandDetail?}`；未知命令必须 FAILED，不可假确认。`commandResults` 有界保留最近 50 条，状态 SENT/EXECUTED/FAILED，H5 区分“已发送”与“电视已执行”。异步媒体重建完成后才确认；重复命令在本机去重。

SEEK 定位原媒体时间轴，原唱/伴奏切换及 APPLY_AUDIO_ROLES 使用最新已保存映射并保留位置、暂停状态和同一次播放计数。RESTART 是新的明确演唱尝试；切歌与重唱由 H5 二次确认。

## 曲库、共享收藏与反馈

- `/party/library/artists`、`/{id}`、`/{id}/songs`、`/{id}/images/{kind}` 与 TV 复用歌手投影和照片；歌曲按原有 tagIds/artistId/q 语义筛选。
- `GET /party/library/recent` 按歌曲去重，排除 ADMIN 试听与 playedMs=0；`GET /party/library/popular?period=30d|7d|all` 按有效播放排序，默认 30d。基于本 NAS 真实持久历史，返回分页歌曲投影、available、lastPlayedAt/playCount。不可用歌曲保留历史标题并明确禁点。
- `GET /party/favorites`、`/ids`、`PUT /party/favorites {songIds,selected}` 复用 NAS 共享收藏服务和目标状态幂等，任何加入者可增删。失败保留服务端原值，不建立浏览器收藏副本。
- `POST /party/missing-song-feedback` 复用后台处理列表；稳定 UUID requestId，title 必填，artist/versionLabel/description 选填。source 固定 PARTY，submittedBy 和请求主体由服务端身份确定；相同请求不同内容或身份返回 409。
- `PUT /party/audio-roles/songs/{id}` 仍保存全库映射，支持已核验多轨中任意两条不同音轨；保存后 H5 对当前同一 attempt 发 APPLY_AUDIO_ROLES。保存与 TV 应用分开报告，应用失败或能力不足显示“标记已保存，电视尚未应用”，可独立重试应用。

## 互动

`POST /party/sessions/{id}/reactions {requestId,kind}`，kind 为 LIKE/CLAP/CHEER。独立短期请求回执，不消耗队列 receipts/revision；每人每秒最多 3 次、每场每秒最多 20 次，超过返回 429。事件包含递增 cursor、attemptId、createdAt、expiresAt（3 秒），快照只返回有效事件及当前 cursor。TV 首次连接/重连以当前 cursor 为基线，后续仅展示同一播放实例且未过期的新事件；切歌丢弃旧事件。全屏透明动效约 2 秒、无音效，TV 可关闭并持久保存该设置，关闭期间仍推进游标。

发布顺序：Go → TV → H5。真实 NAS、直播放/转码和微信/App 容器验收与本机构建结果分别记录。
