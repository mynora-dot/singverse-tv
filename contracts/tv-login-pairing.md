# TV 登录接力 v1

电视未登录时先显示登录方式选择；选择「手机扫码登录」后直接显示本机短期配对二维码，无需填写 NAS 地址；已登录音乐库的 SingVerse Android App 扫码、核对电视并确认后，将当前 NAS 的电视访问凭证加密交给电视。正常路径无需重新填写 NAS 地址。手机和电视须能在同一私有 IPv4 网络直接通信。本协议不增加云中继或 Go 授权审批接口。

保留本轮已移除的设备授权申请、网页审批、六位码和轮询的删除结果。Go 继续沿用当前 `POST /api/v1/auth/tv-session`：已完成 MFA 的管理员 Cookie + CSRF 输入 `{name}`，返回 `{deviceId,token}`。电视及聚会 API 继续只接受其限权 Bearer，管理员 Cookie、密码与恢复码不传到电视。

## 二维码

`singverse://tv-login?v=1&endpoint=<URL编码的HTTP根地址>&session=<32位小写hex>&key=<64位小写hex>`。

`session` 为 16 字节密码学随机值，`key` 为 32 字节密码学随机值。参数必须且只能各出现一次，顺序不限；禁止片段、用户信息及附加路径。`endpoint` 仅允许 HTTP + 规范数字形式 RFC1918 IPv4 + 显式 1～65535 端口，不接受域名、公网、loopback 或重定向。电视从实际网卡取得地址，系统选择空闲端口；不等待 NAS 广播。会话有效期为电视本机单调时钟 300 秒；退出、后台、刷新或过期后关闭临时监听并销毁密钥。成功后立即隐藏二维码，只保留同一次尝试的完成状态最多 10 秒（不超原始有效期），供手机在响应丢失时查询，随后关闭监听并销毁密钥；不能再接受其他连接尝试。二维码本身是短期配对能力，禁止写入日志、历史或持久化存储。

## 加密 HTTP

仅接受 `POST /pair/v1/{session}/inspect` 与 `POST /pair/v1/{session}/connect`，JSON Content-Type、Content-Length，禁止 Origin、Transfer-Encoding、超过 4 KiB 的头或超过 8 KiB 的包体。没有 CORS 或网页入口。

外层 JSON 为 `{nonce,ciphertext}`，两字段为小写 hex。使用 AES-256-GCM，随机 12 字节 nonce、128 bit tag（附在密文末尾）；每次发送重新生成 nonce。AAD 为 UTF-8 `singverse:tv-login:v1:{session}:{operation}`，operation 分别为 `inspect-request`、`inspect-response`、`connect-request`、`connect-response`。响应同样加密，并回显原始请求的 `requestId`，防止请求反射、跨会话或跨方向替换；HTTP 返回 no-store。

所有明文请求包含 `requestId`（手机生成的 16 字节随机值的小写 hex）。`inspect` 请求无其他字段。成功的明文响应包含 `{requestId,name,state,remainingSeconds,message}`，name 为电视型号/名称，state 为 waiting/scanned/connecting/connected/error；有效剩余时间由电视单调时钟判断。

手机原生桥接先验证当前账号会话，再检查配对入口，向用户展示电视名称与 App 当前音乐库。用户未确认或音乐库/账号会话发生切换时不得签发和传递电视凭证。Flutter 不接收 Cookie 或电视 token。

用户确认后，手机原生桥接复用同一 NAS 连接代次及现有账号登录接口获取电视 token。`connect` 的加密明文为 `{requestId,origin,token}`。电视检查根地址、服务身份和 token 可访问 `/api/v1/tv/home` 后才保存凭证并打开音乐库。仅一个 requestId 能提交；相同 requestId 重传不能重复建立连接。旧二维码、过期、退出或取消后的迟到结果不得改变当前 NAS。

手机只在加密响应确认 connected 后显示完成；响应丢失时先检查同一尝试状态，不能直接签发第二个凭证。失败或取消时尽力通过同一 NAS 会话撤销本次刚取得的 deviceId；不撤销其他电视。设备凭证由 TV 既有 Android Keystore 加密保存。缺少局域网、路由隔离、码过期等显示可恢复错误，并保留局域网搜索 NAS / 账号登录入口。

扫码聚会邀请码（`/party/#invite=...`）仍按现有协议处理，与首次登录码明确区分。
