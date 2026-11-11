# RGR06 指环在 RG_glasses 上的 ADB 输入记录

2026-11-11 在眼镜上用 `adb shell getevent -pl` 枚举设备，用 `adb shell getevent -lt` 记录实际输入。以下节点编号只对应本次连接，重新连接后可能变化。

| 输入设备名 | 本次节点 | 实际观察到的事件 |
| --- | --- | --- |
| `RGR06 Keyboard` | `/dev/input/event2` | `EV_KEY` 的 `KEY_F1` 至 `KEY_F8`、`KEY_ENTER`、`KEY_LEFT`、`KEY_RIGHT`、`KEY_UP`、`KEY_DOWN` |
| `RGR06 Consumer Control` | `/dev/input/event3` | `EV_KEY KEY_BACK`（`MSC_SCAN 0x000c0224`） |
| `RGR06` | `/dev/input/event4` | `EV_KEY BTN_TOUCH`、`EV_KEY BTN_DIGI`、`EV_ABS ABS_X`、`EV_ABS ABS_Y` |

## 按操作顺序对照

下表对应这一轮操作的时间顺序。按键名省略 `KEY_` 前缀。各次短按包含 `DOWN` 和 `UP`。

| 操作 | 观察到的输入 |
| --- | --- |
| 左单击 | `F3`（`MSC_SCAN 0x0007003c`） |
| 左长按，两次 | 都是 `F3 DOWN → UP`，分别约 1.38 秒和 1.43 秒 |
| 滚轮上滚 | `F1 → LEFT → UP` |
| 滚轮下滚 | `F1 → RIGHT → DOWN`，同期还有触控坐标 |
| 中长按 | `F1 → F6` |
| 中单击 | `F1 → F4` |
| 中双击 | `F1 × 2 → F5` |
| 中三击 | `F1 × 3 → F8` |
| 右单击 | `F1 → ENTER` |
| 右双击 | 键盘的 `F1 × 2`，随后是消费控制设备的 `BACK` |
| 右长按 | `F1 → F2`，`F2` 持续按下约 0.78 秒 |
| 右三击 | `F1 × 3 → F7` |
| 触摸板移动 | `BTN_DIGI` 和 `ABS_X / ABS_Y` 坐标变化 |
| 触摸板单击 | `BTN_TOUCH` 与 `BTN_DIGI` 按下、松开 |

`F1` 出现在多类操作中，单独出现时不足以区分动作。滚轮的组合键和触控重叠应以单项复测确认后再做业务映射。

这些名称来自眼镜系统的输入设备层。证据支持 HID 输入形式。蓝牙链路类型（BLE 或经典蓝牙）、Android 应用层键码和 CXR-L 是否转发，需要分别验证。
