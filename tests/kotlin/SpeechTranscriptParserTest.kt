package com.leqi.experiment.phonebt

import java.util.Base64
import kotlin.system.exitProcess

/**
 * `GemmaOutput.transcript` 的离线行为测试。
 *
 * 只覆盖解析器本身：模型输出 → 用户原话 或 明确失败。语音入口要保证的是
 * “用户怎么说就怎么传下去”，所以这里的用例集中在两件事：
 * - 完整表达（询问、肯定、否定、取消、请求、多句标点）必须逐字留下，不截断、不去标点、不过滤措辞；
 * - 空原话、JSON 之外的散文、解释、额外字段、超长输出必须明确失败，不能拿模型的分析当原话。
 *
 * 手写 JSON 解析的边界也在这里钉住：多余的逗号、带符号或位数不对的 `\u` 转义、大小写不对的字段名
 * 都不是约定形状，必须报失败，而不是被“看起来差不多”地收下。
 *
 * 不依赖 Android：配合 runner 只编译 `GemmaOutput.kt` 和本文件。
 * 期望值用 base64 打印，避免 Windows 控制台编码干扰比对与阅读。
 * 运行方式见 `tools/run-speech-parser-tests.mjs`。
 */

private class Case(val name: String, val raw: String, val expected: String?)

/** 一段超过 32 字的完整原话：证明上限不是旧目的地尺度的 32 字。 */
private const val LONG_UTTERANCE =
    "我想去人民公园，但我不知道现在应该走哪条路，能不能先带我到最近的地铁站，再换乘过去。"

private const val AT_LIMIT = "好"

/** 造一段正好 [length] 个字的原话；上限用例只关心字数，内容无所谓。 */
private fun repeatTo(length: Int): String {
    val builder = StringBuilder()
    while (builder.length < length) builder.append(AT_LIMIT)
    return builder.toString().substring(0, length)
}

private val CASES = listOf(
    // —— 完整表达必须逐字保留 ——
    Case("question-kept", """{"transcript":"请问最近的药店怎么走？"}""", "请问最近的药店怎么走？"),
    Case("affirmative-kept", """{"transcript":"好的，可以。"}""", "好的，可以。"),
    Case("negative-kept", """{"transcript":"不用了，谢谢。"}""", "不用了，谢谢。"),
    Case("cancel-kept", """{"transcript":"算了，取消吧。"}""", "算了，取消吧。"),
    Case("request-kept", """{"transcript":"我想去人民公园，从东门进去。"}""", "我想去人民公园，从东门进去。"),
    Case(
        "multi-sentence-punctuation-kept",
        """{"transcript":"我要去第一医院。等等，先别走！"}""",
        "我要去第一医院。等等，先别走！",
    ),
    Case(
        "long-utterance-not-truncated",
        """{"transcript":"$LONG_UTTERANCE"}""",
        LONG_UTTERANCE,
    ),
    // 旧解析器把“抱歉/听不清/你”这类措辞当成不可信内容；原话里它们就是用户真说的话。
    Case(
        "unreliable-wording-kept",
        """{"transcript":"抱歉，我可能听不清你说的，你再说一遍好吗？"}""",
        "抱歉，我可能听不清你说的，你再说一遍好吗？",
    ),
    // —— 格式内的合法变体 ——
    Case("json-whitespace-ok", """{ "transcript" : "嗯，行。" }""", "嗯，行。"),
    Case("escaped-quotes-decoded", """{"transcript":"他说\"走吧\"，然后转身。"}""", "他说\"走吧\"，然后转身。"),
    Case(
        "unicode-escape-decoded",
        """{"transcript":"你好，请问地铁站怎么走？"}""",
        "你好，请问地铁站怎么走？",
    ),
    Case("at-limit-accepted", """{"transcript":"${repeatTo(200)}"}""", repeatTo(200)),
    Case("backslash-escape-decoded", """{"transcript":"前面左转\\再直行"}""", "前面左转\\再直行"),
    Case("uppercase-hex-escape-decoded", """{"transcript":"你好"}""", "你好"),
    // —— 必须明确失败 ——
    Case("empty-transcript-fails", """{"transcript":""}""", null),
    Case("blank-transcript-fails", """{"transcript":"   "}""", null),
    Case("empty-model-output-fails", "   ", null),
    Case("empty-object-fails", "{}", null),
    Case("missing-field-fails", """{"text":"走吧"}""", null),
    Case("extra-analysis-field-fails", """{"transcript":"去公园","analysis":"用户想去公园"}""", null),
    Case("extra-confidence-field-fails", """{"transcript":"去公园","confidence":0.9}""", null),
    Case("duplicate-field-fails", """{"transcript":"走吧","transcript":"站住"}""", null),
    Case("non-string-value-fails", """{"transcript":123}""", null),
    Case("prose-answer-fails", "用户说他想去公园，建议先走到地铁站。", null),
    // 旧实现自己约定的“标签：值”形式早已删除：它不是 JSON，现在和任意散文一样必须失败。
    Case("labelled-text-fails", "原话：人民公园", null),
    Case("json-array-fails", """["走吧"]""", null),
    // —— 手写 JSON 解析的边界 ——
    Case("trailing-comma-fails", """{"transcript":"走吧",}""", null),
    Case("trailing-comma-with-space-fails", """{"transcript":"走吧" , }""", null),
    Case("only-comma-fails", """{,}""", null),
    Case("key-case-sensitive-fails", """{"Transcript":"走吧"}""", null),
    Case("nested-value-fails", """{"transcript":{"text":"走吧"}}""", null),
    Case("signed-hex-escape-fails", """{"transcript":"\u+041"}""", null),
    Case("short-hex-escape-fails", """{"transcript":"\u12"}""", null),
    Case("raw-newline-in-json-fails", "{\n\"transcript\":\"走吧\"}", null),
    Case("trailing-prose-fails", """{"transcript":"走吧"} 希望有帮助""", null),
    Case("fenced-json-fails", "```json\n{\"transcript\":\"走吧\"}\n```", null),
    Case("raw-newline-in-string-fails", "{\"transcript\":\"走吧\n站住\"}", null),
    Case("over-limit-fails", """{"transcript":"${repeatTo(201)}"}""", null),
)

private fun encode(value: String?): String =
    if (value == null) "-" else "+" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

fun main() {
    var failed = 0
    for (case in CASES) {
        val actual = try {
            GemmaOutput.transcript(case.raw)
        } catch (error: GemmaBridgeException) {
            null
        } catch (error: Throwable) {
            // 解析器抛出约定之外的异常同样是失败，但单独标出来，免得和“按约定失败”混淆。
            "!" + error.javaClass.simpleName
        }
        val expected = encode(case.expected)
        val produced = if (actual != null && actual.startsWith("!")) actual else encode(actual)
        if (produced == expected) {
            println("PASS\t" + case.name)
        } else {
            failed += 1
            println("FAIL\t" + case.name + "\texpected=" + expected + "\tactual=" + produced)
        }
    }
    println("SUMMARY\ttotal=" + CASES.size + "\tfailed=" + failed)
    if (failed > 0) exitProcess(1)
}
