package com.leqi.experiment.phonebt

/**
 * 本地 Gemma 输出的保守解析。
 *
 * 这里只有一件事：把语音转写的结果读回来。模型是转写器，不是助手，所以 [transcript] 返回的
 * 就是用户原话本身，逐字保留：不清洗、不去标点、不判断措辞、不截断。
 *
 * 输入只认 System Prompt 里约定的那一种形状，任何偏离约定的输出一律按失败处理：
 * 宁可返回失败，也不猜测、不补全、不截取模型的长篇回答当结果。
 * 本文件不依赖 Android，可脱离设备单独编译验证。
 */
internal object GemmaOutput {

    const val TRANSCRIPT_FIELD = "transcript"

    private const val RAW_ECHO_LIMIT = 60

    /**
     * 一句原话的字数上限。
     *
     * 这不是地名那种几十字尺度的限制：用户说的是完整一句话甚至几句话。
     * 上限只用来挡住明显失控的输出（例如模型把整段提示词或长篇解释当原话吐出来），
     * 正常的一轮询问、确认、取消或请求都远在它之下。
     */
    private const val MAX_TRANSCRIPT_LENGTH = 200

    /**
     * 约定形式：`{"transcript":"<用户原话>"}`，且只有这一个字段。
     *
     * 转写结果就是用户原话本身，必须逐字保留。疑问、肯定、否定、取消、请求、称呼和标点都原样留下，
     * 不改写、不总结、不去标点、不截断；原话里出现“不是”“取消”“抱歉”这类词恰恰是用户真实表达，
     * 不能当成不可信内容过滤掉。
     *
     * 模型给出的分析、解释、置信度或任何额外字段都不是原话，一律报失败；
     * 空原话（模型表示听不清）同样明确失败，绝不返回占位文本。
     */
    fun transcript(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw GemmaBridgeException("本地模型没有输出任何内容")
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw GemmaBridgeException(
                "本地模型输出了多行内容，不符合约定格式：" + echo(text),
            )
        }
        val fields = jsonFields(text)
        val unexpected = fields.keys.firstOrNull { it != TRANSCRIPT_FIELD }
        if (unexpected != null) {
            throw GemmaBridgeException(
                "本地模型除了原话还给出了“$unexpected”，不能当作原话：" + echo(text),
            )
        }
        val value = fields[TRANSCRIPT_FIELD]
            ?: throw GemmaBridgeException(
                "本地模型输出不符合约定格式（应为 {\"$TRANSCRIPT_FIELD\":\"…\"}）：" + echo(text),
            )
        if (value.isBlank()) {
            throw GemmaBridgeException("本地模型没有从这段语音里听出可转写的内容")
        }
        if (value.length > MAX_TRANSCRIPT_LENGTH) {
            throw GemmaBridgeException(
                "本地模型给出的原话超过 $MAX_TRANSCRIPT_LENGTH 字，不可信：" + echo(value),
            )
        }
        // 原话原样返回：这里不做任何清理、去标点或首尾裁剪之外的加工。
        return value
    }

    /**
     * 解析一个只含字符串字段的扁平 JSON 对象。
     *
     * 只认这一种形状：多一个字节的散文、代码块围栏、数字、嵌套值、重复字段和多余的逗号都是格式错误。
     * 宁可报失败，也不从一段解释里“挑出”看起来像原话的部分。
     */
    private fun jsonFields(text: String): Map<String, String> {
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw GemmaBridgeException("本地模型输出不是约定的 JSON 对象：" + echo(text))
        }
        val fields = LinkedHashMap<String, String>()
        val end = text.length - 1
        var index = 1
        // 逗号之后必须真的跟着下一个字段：`{"transcript":"走吧",}` 不是合法 JSON，不能因为闭括号
        // 恰好落在预期位置就把它收下。
        var afterComma = false
        while (true) {
            index = skipSpaces(text, index)
            if (index >= end) {
                if (fields.isEmpty()) {
                    throw GemmaBridgeException("本地模型的 JSON 里没有任何字段：" + echo(text))
                }
                if (afterComma) {
                    throw GemmaBridgeException("本地模型的 JSON 末尾多了一个逗号：" + echo(text))
                }
                return fields
            }
            val key = readJsonString(text, index)
            index = skipSpaces(text, key.next)
            if (index >= end || text[index] != ':') {
                throw GemmaBridgeException("本地模型的 JSON 字段缺少冒号：" + echo(text))
            }
            val value = readJsonString(text, skipSpaces(text, index + 1))
            if (fields.put(key.value, value.value) != null) {
                throw GemmaBridgeException(
                    "本地模型的 JSON 里字段“${key.value}”出现了多次：" + echo(text),
                )
            }
            afterComma = false
            index = skipSpaces(text, value.next)
            if (index == end) return fields
            if (index > end || text[index] != ',') {
                throw GemmaBridgeException("本地模型的 JSON 字段之间缺少逗号：" + echo(text))
            }
            index += 1
            afterComma = true
        }
    }

    private fun skipSpaces(text: String, start: Int): Int {
        var index = start
        while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index += 1
        return index
    }

    /** 读一个 JSON 字符串字面量，返回内容与下一个待读位置。 */
    private fun readJsonString(text: String, start: Int): JsonString {
        if (start >= text.length || text[start] != '"') {
            throw GemmaBridgeException("本地模型的 JSON 字段值只能是字符串：" + echo(text))
        }
        val builder = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            val character = text[index]
            if (character == '"') return JsonString(builder.toString(), index + 1)
            if (character != '\\') {
                if (character.code < 0x20) break
                builder.append(character)
                index += 1
                continue
            }
            if (index + 1 >= text.length) break
            when (val escape = text[index + 1]) {
                '"', '\\', '/' -> {
                    builder.append(escape)
                    index += 2
                }
                'b' -> {
                    builder.append('\b')
                    index += 2
                }
                'f' -> {
                    builder.append('\u000C')
                    index += 2
                }
                'n' -> {
                    builder.append('\n')
                    index += 2
                }
                'r' -> {
                    builder.append('\r')
                    index += 2
                }
                't' -> {
                    builder.append('\t')
                    index += 2
                }
                'u' -> {
                    val hex = text.substring(index + 2, minOf(index + 6, text.length))
                    // 只认四位十六进制：`\u+41` 这类带符号的写法不是 JSON 转义，必须报失败。
                    if (!hex.matches(HEX4)) break
                    builder.append(hex.toInt(16).toChar())
                    index += 6
                }
                else -> break
            }
        }
        throw GemmaBridgeException("本地模型的 JSON 字符串没有正常结束：" + echo(text))
    }

    private class JsonString(val value: String, val next: Int)

    private fun echo(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= RAW_ECHO_LIMIT) flat else flat.substring(0, RAW_ECHO_LIMIT) + "…"
    }

    private val HEX4 = Regex("[0-9a-fA-F]{4}")
}
