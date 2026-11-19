# Android 客户端发行与更新契约

手机仓库包名 com.mynora.singverse.mobile、minSdk 24；TV 包名 com.mynora.singverse.tv、minSdk 21（Android 5.0，包含 Android 5.1 / API 22）。各仓通过 GitHub 正式 Release 标签 vMAJOR.MINOR.PATCH 独立管理版本，Actions 注入 SINGVERSE_VERSION，不维护源码 VERSION 文件。versionCode = major × 1000000 + minor × 1000 + patch，minor/patch 不超过999，结果为1–2100000000，且高于上一正式包；同次发布各 ABI 使用相同 versionCode，不加架构偏移。正式签名证书持续一致，不使用 debug 签名发布。TV APK 保留 Android 5 所需的 v1 签名及 armeabi-v7a 支持。

## 发行资产

新正式 Release 只发布单架构 APK，不生成通用包。手机完整发布 armeabi-v7a、arm64-v8a、x86_64，TV 完整发布 armeabi-v7a、arm64-v8a、x86、x86_64；文件名为 singverse-{mobile|tv}-{abi}.apk。每个 APK 必须实际包含且只包含对应 ABI，缺包、重复 ABI、混合架构或证书不一致均拒绝。

release.json 使用 schemaVersion=2，公共字段为 platform、versionName、versionCode、packageName、minSdk、certificateSha256、notes，variants 数组每项包含 abi、fileName、size、sha256。所有 APK 共用版本、包名、最低系统和签名；摘要为小写64位十六进制，每个包大小上限256MiB，notes 最多16000个 UTF-8 字节。

历史 schemaVersion=1 清单及 singverse-{mobile|tv}.apk 继续支持读取，用于上一正式版签名连续性检查和已锁定的旧版平台组装；新发布不得回退到旧清单或通用 APK。

SHA256SUMS 使用标准 sha256sum 两空格格式，覆盖每个 ABI APK 与 release.json。发布前验证真实签名、APK manifest、版本、ABI与大小；历史正式包缺元数据、证书变化或版本不增长均拒绝。同一 tag 重试逐文件比较，只复用完全相同的资产并补传缺失文件。

## 管理平台组装

Go components.json 指定前端、手机和TV的仓库；新构建分别读取一次 Latest 正式 Release（可为组件显式设置 tag），拒绝草稿、预发布与非稳定语义版本。记录 repository、releaseId、tag、所有 assetId、大小、SHA-256 和 APK 元数据到 components.lock.json。架构构建共用这些输入；发布重试复用锁及 OCI 归档。固定标签不得替换内容。

业务镜像只读 releases/mobile 和 releases/tv 提供包；运行期间不访问 GitHub。显式 SINGVERSE_TV_RELEASE_DIRECTORY 旧配置继续由原电视接口处理，新的通用下载接口要求完整元数据。未配置或未打包时返回 available:false，配置损坏时返回503。

## 公开只读接口

- GET /api/v1/client-releases/{platform}：available:false 或选中 APK 的 schemaVersion=1 完整元数据与同源 url；新清单另返回 variants 数组（abi、size、sha256、url），供下载页选择。
- GET/HEAD /api/v1/client-releases/{platform}/apk：选中架构的 APK，支持 Range、206、416与 ETag。
- GET /api/v1/client-releases/{platform}/qr?origin={NAS-origin}：指向 /downloads/#{platform} 的PNG。
- /downloads/ 与显式 /downloads/index.html：匿名页面；/downloads 重定向补斜杠。

platform 只接受 mobile、tv。二维码 origin 必须为手机可达的 NAS HTTP(S) 源地址，不含路径、凭据、查询或片段；回环与未指定IP拒绝。只读发行入口不授予管理员会话、曲库读取或设备注册权限。真实响应详见 OpenAPI。

清单和 APK 请求通过 X-SingVerse-ABI 指定同一个架构，客户端按 Build.SUPPORTED_ABIS 的系统优先顺序选择。固定下载路径保持不变，选包后的大小和摘要与对应 APK 一致。网页使用 ?abi={abi}；查询与请求头同时提供时必须一致。未知、重复、空白查询或不属于平台的 ABI 返回400，损坏包返回503，不改发其他架构。

未指定架构的旧客户端和页面，手机默认 arm64-v8a，TV 默认 armeabi-v7a。旧客户端如果不支持该默认包，需要先从下载页手动安装对应架构的新版本；新版本后续自动按架构更新。新前端、Go 镜像与两端 APK 应一起部署。应用仍兼容旧 NAS 提供的 schemaVersion=1 通用 APK。

## 客户端行为

连接或恢复保存的NAS后异步检查，前台每天复查，并提供手动入口。手机仅选择家庭曲库NAS，临时聚会扫码不替换更新来源。仅远端 versionCode 更大时提示；稍后可再次提醒，跳过此版本按NAS和版本持久化。手动检查仍显示被跳过的新版本。

选择更新后流式下载并显示进度，可取消/重试。验证实际字节大小、SHA-256、包名、versionCode、versionName及签名证书与已安装包一致，再打开系统安装确认。Android 8及以上按系统引导授权安装未知来源；返回后再次确认当前连接、包校验及空闲状态。

NAS切换/断开时取消下载，generation阻止旧检查和迟到结果污染新连接。检查失败不阻塞正常使用。手机录音、混音、导出期间，TV播放期间延后自动提示；安装由用户空闲时主动发起。覆盖安装不清除作品、收藏、设置或连接数据；同一测试签名两个递增版本的数据保留须在设备上单独验收。
