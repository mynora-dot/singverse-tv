# Android 客户端发行与更新契约

手机仓库包名 com.mynora.singverse.mobile、minSdk 24；TV 包名 com.mynora.singverse.tv、minSdk 23。每仓根 VERSION 独立维护，正式标签 v{VERSION}。versionCode = major × 1000000 + minor × 1000 + patch，minor/patch 不超过999，结果为1–2100000000，且高于上一正式包。正式签名证书持续一致，不使用 debug 签名发布。

## 发行资产

每个正式 Release 包含 singverse-{mobile|tv}.apk、release.json、SHA256SUMS。JSON 字段为 schemaVersion=1、platform、versionName、versionCode、packageName、minSdk、abis、size、sha256、certificateSha256、notes；摘要为小写64位十六进制，大小上限256MiB。没有 native-code 的包写 abis=["universal"]。notes 为最多16000字节的 UTF-8 发布说明。

SHA256SUMS 使用标准 sha256sum 两空格格式，覆盖 APK 与 release.json。发布前验证真实签名、APK manifest、版本、ABI与大小；历史正式包缺元数据、证书变化或版本不增长均拒绝。同一 tag 重试只能复用完全相同的资产。

## 管理平台组装

Go components.json 固定前端 Release 标签；新构建对手机和TV分别读取一次 Latest 正式 Release，拒绝草稿、预发布与非稳定语义版本。记录 repository、releaseId、tag、所有 assetId、大小、SHA-256 和 APK 元数据到 components.lock.json。架构构建共用这些输入；发布重试复用锁及已验收 OCI 归档。固定标签不得替换内容。

业务镜像只读 releases/mobile 和 releases/tv 提供包；运行期间不访问 GitHub。显式 SINGVERSE_TV_RELEASE_DIRECTORY 旧配置继续由原电视接口处理，新的通用下载接口要求完整元数据。未配置或未打包时返回 available:false，配置损坏时返回503。

## 公开只读接口

- GET /api/v1/client-releases/{platform}：available:false 或完整元数据与同源 url。
- GET/HEAD /api/v1/client-releases/{platform}/apk：APK，支持 Range、206、416与 ETag。
- GET /api/v1/client-releases/{platform}/qr?origin={NAS-origin}：指向 /downloads/#{platform} 的PNG。
- /downloads/ 与显式 /downloads/index.html：匿名页面；/downloads 重定向补斜杠。

platform 只接受 mobile、tv。二维码 origin 必须为手机可达的 NAS HTTP(S) 源地址，不含路径、凭据、查询或片段；回环与未指定IP拒绝。只读发行入口不授予管理员会话、曲库读取或设备注册权限。真实响应详见 OpenAPI。

## 客户端行为

连接或恢复保存的NAS后异步检查，前台每天复查，并提供手动入口。手机仅选择家庭曲库NAS，临时聚会扫码不替换更新来源。仅远端 versionCode 更大时提示；稍后可再次提醒，跳过此版本按NAS和版本持久化。手动检查仍显示被跳过的新版本。

选择更新后流式下载并显示进度，可取消/重试。验证实际字节大小、SHA-256、包名、versionCode、versionName及签名证书与已安装包一致，再打开系统安装确认。Android 8及以上按系统引导授权安装未知来源；返回后再次确认当前连接、包校验及空闲状态。

NAS切换/断开时取消下载，generation阻止旧检查和迟到结果污染新连接。检查失败不阻塞正常使用。手机录音、混音、导出期间，TV播放期间延后自动提示；安装由用户空闲时主动发起。覆盖安装不清除作品、收藏、设置或连接数据；同一测试签名两个递增版本的数据保留须在设备上单独验收。
