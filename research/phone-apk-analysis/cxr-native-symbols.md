# CXR Native 符号清单

## 证据

分析对象：开发机工作区的 `apk_unpacked.zip`。

关键库：

- `lib/arm64-v8a/libcxr-bridge-jni.so`
- `lib/arm64-v8a/libcxr-sock-proto-jni.so`
- `lib/arm64-v8a/libflora-cli.so`
- `lib/arm64-v8a/libcaps.so`

## 已观察到的接口

`libcxr-bridge-jni.so`：

```text
CXRBridgeInitialize
nativeInitialize
nativeSendMessage
nativeSendMessageStream
nativeSendARTCFrame
nativeSubscribe
startBTPairing
startAudioStream
stopAudioStream
onDeviceConnected
onDeviceDisconnected
```

`libcxr-sock-proto-jni.so`：

```text
nativeAuth
nativeActive
nativeRequest
nativeSend
nativeFetchClientList
nativeRemoveClient
nativeStartAudioStream
nativeCancelAudioStream
nativeChangeRokidAccount
nativeHandleReadPacket
```

消息类型符号包括：

```text
AuthRequest / AuthResponse
ActiveRequest / ActiveStatusNotify
ClientListReq / ClientListResp
RemoveClientReq / RemoveClientResp
RokidAccountRequest
Request / Response / Notify
TransferInfo / RecvTransfer
SendAudioStream / RecvAudioStream
RecvARTCFrame
```

## 高置信度结论

- 手机 APK 中包含实际 CXR 协议客户端，而不是只有 UI 或组件声明。
- 协议包含认证、请求/响应/通知、设备客户端管理、二进制传输和音频流。
- bridge 层使用 `Flora` 和 `unix:cxr-service` 相关入口。
- socket protocol 层出现 `BTSocketRead`、`CXRConfig::gatt` 和 `RokidCXR1.0` 字符串，说明蓝牙/GATT 或其抽象通道参与传输。

## 尚未确认

- 消息编号和 `Caps` 字段的完整结构；
- GATT UUID、MTU 和分片细节；
- 鉴权材料和账号绑定流程；
- 普通第三方 APK 是否可以复用这些 Native 库；
- CXR socket 是直接连接设备还是连接手机侧系统服务。

因此生产代码只依赖 `DeviceTransport`，不把这些 Native 符号直接暴露给领域模块。
