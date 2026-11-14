package com.leqi.experiment

/** 设备授权窗口；不解释用户意图，也不承担 Agent 任务状态。调用方在主线程串行使用。 */
class CxrlInputGate {
    private var type: String? = null
    private var tag = ""
    private var deadline = 0L

    fun arm(command: String, payload: String, now: Long) {
        when (command) {
            "voice.arm", "entrance.arm" -> {
                require(payload.isNotBlank() && payload.length <= 256)
                type = command.substringBefore('.')
                tag = payload
                deadline = now + if (type == "voice") 90000L else 120000L
            }
            "voice.disarm" -> if (type == "voice") cancel()
            "entrance.disarm" -> if (type == "entrance") cancel()
            "task.cancel" -> cancel()
            else -> error("未知设备授权命令")
        }
    }

    fun press(now: Long): Pair<String, String>? {
        val current = type ?: return null
        val result = Pair("$current.${if (now <= deadline) "confirm" else "expired"}", tag)
        cancel()
        return result
    }

    fun cancel() { type = null; tag = ""; deadline = 0 }
    fun expire(now: Long): Pair<String, String>? = if (type != null && now > deadline) press(now) else null
}
