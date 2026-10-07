package com.leqi.experiment.phonebt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager

/**
 * 启动自检：如实检查运行时真正依赖的每一项，缺什么就说什么。
 *
 * 只回答“这台手机现在能不能真的跑一遍”，不做任务裁决：
 * - **本地模型**：手机推理模型不随 APK 分发，所以这里既要查文件在不在，也要等真正的加载结果，
 *   不能只看“文件存在”就报就绪；
 * - **离线语音**：三个必需文件齐不齐，加载结果同样如实记录；
 * - **地图与定位**：高德 Key 有没有配、隐私政策有没有同意、定位权限有没有授予，逐条给原因；
 * - **眼镜蓝牙**：权限、蓝牙开关、已配对设备、当前通道状态。
 *
 * 只出现文件路径、权限名和状态，不出现任何密钥内容，也不出现媒体字节。
 * 任何一项不通过都必须能被上层读出来（[Report.captureBlockedReason]），以免“假装会话开起来了”。
 */
class RuntimeReadiness(private val context: Context) {

    /** 运行时能力。会话能否开始、导航能否用，一律按能力判定，不按某一行的文字。 */
    enum class Capability(val label: String) {
        AGENT("仓库 Agent"),
        MODEL("本地模型"),
        SPEECH("离线语音"),
        MAP("地图与定位"),
        BLUETOOTH("眼镜蓝牙"),
    }

    /** 一项前置条件的状态：还没查/正在查、通过、不通过。 */
    enum class Level { PENDING, OK, BLOCKED }

    /** 眼镜通道的当前形态；[CONNECTING] 是“正在连/正在重连”，不是“没戏”。 */
    enum class LinkState { CONNECTED, CONNECTING, DOWN }

    class Item(val capability: Capability, val level: Level, val detail: String)

    class Report(val items: List<Item>) {
        /** 没有任何阻塞项时才算就绪；待查项不算就绪，避免提前说“已就绪”。 */
        val ready: Boolean
            get() = items.none { it.level != Level.OK }

        fun blocked(capability: Capability): Item? =
            items.firstOrNull { it.capability == capability && it.level == Level.BLOCKED }

        /** 这一项只要还没到 [Level.OK]（待查或不通过）就给出说明；已通过返回 null。 */
        private fun notReady(capability: Capability): Item? =
            items.firstOrNull { it.capability == capability && it.level != Level.OK }

        /**
         * 采集链路的阻塞原因：Agent 没起来、模型或离线语音不可用、眼镜没连上时，任何一段录音/一张照片
         * 都不可能被真正处理，所以任一不通过都必须能拿到一句可以直接展示的原因。
         *
         * 待查（PENDING）同样算阻塞：自检没跑完之前不允许采集——“还没查完”不等于“可以开始”。
         */
        fun captureBlockedReason(): String? =
            listOf(Capability.AGENT, Capability.MODEL, Capability.SPEECH, Capability.BLUETOOTH)
                .firstNotNullOfOrNull { notReady(it)?.detail }

        /** 导航起不来的原因：地图与定位这一项没通过（或还在查）就不许起导航。 */
        fun navigationBlockedReason(): String? = notReady(Capability.MAP)?.detail

        /** 逐项可读的状态行，供界面展示。 */
        fun lines(): List<String> = items.map { "${it.capability.label}：${it.detail}" }

        /** 一行摘要：优先说第一个阻塞项，其次第一个待查项，全绿才说就绪。 */
        val headline: String
            get() {
                val blocked = items.firstOrNull { it.level == Level.BLOCKED }
                if (blocked != null) return "${blocked.capability.label}不可用：${blocked.detail}"
                val pending = items.firstOrNull { it.level == Level.PENDING }
                if (pending != null) return "${pending.capability.label}：${pending.detail}"
                return "全部就绪"
            }
    }

    /** 高德 Key 的 manifest meta-data 名；只判存在与否，不读值，也不打印值。 */
    private val mapKeyConfigured: Boolean = readMapKeyConfigured()

    /** 本地模型这一项的状态；由宿主在 worker 线程上更新。 */
    @Volatile private var model = Item(Capability.MODEL, Level.PENDING, "尚未加载")
    /** 离线语音这一项的状态；由宿主在 worker 线程上更新。 */
    @Volatile private var speech = Item(Capability.SPEECH, Level.PENDING, "尚未加载")

    /**
     * 登记一项能力的检查/加载结果。
     *
     * 只有 [Capability.MODEL] 和 [Capability.SPEECH] 需要宿主来登记（它们要真的加载才知道结果）；
     * 其余三项每次都现算，传进来也会被忽略，免得两份状态各说各话。
     */
    fun mark(item: Item) {
        when (item.capability) {
            Capability.MODEL -> model = item
            Capability.SPEECH -> speech = item
            Capability.AGENT, Capability.MAP, Capability.BLUETOOTH -> Unit
        }
    }

    /**
     * 当前状态快照。
     *
     * @param agentReady Agent bundle 是否已经报过 ready
     * @param link 眼镜通道当前是连着、正在连、还是断了
     * @param glassesState 眼镜通道的当前状态说明（已连接 / 第 n 次重连中 / 未连接）
     */
    fun report(agentReady: Boolean, link: LinkState, glassesState: String): Report = Report(
        listOf(
            if (agentReady) {
                Item(Capability.AGENT, Level.OK, "bundle 已加载并报 ready")
            } else {
                Item(Capability.AGENT, Level.BLOCKED, "bundle 还没报 ready，Agent 尚未接管")
            },
            model,
            speech,
            mapItem(),
            bluetoothItem(link, glassesState),
        ),
    )

    /**
     * 启动时的静态检查：只查文件在不在，不加载。
     *
     * 给宿主在真正加载之前先定一个“缺文件”的说法——缺文件时连加载都不用试，
     * 直接就能告诉用户该往哪个目录推模型。
     */
    fun staticItems(): List<Item> = listOf(modelFileItem(), speechFileItem())

    /** 模型文件这一项：文件不在就明确说是缺文件，不试加载。 */
    fun modelFileItem(): Item =
        if (GemmaLocalBridge.findModelFile(context) != null) {
            Item(Capability.MODEL, Level.PENDING, "已找到模型文件，正在加载")
        } else {
            Item(Capability.MODEL, Level.BLOCKED, GemmaLocalBridge.missingModelMessage(context))
        }

    /** 离线语音这一项：必需文件缺一个就明确列出缺哪些。 */
    fun speechFileItem(): Item {
        val missing = OfflineTtsBridge.missingModelFiles(context)
        return if (missing.isEmpty()) {
            Item(Capability.SPEECH, Level.PENDING, "已找到语音模型文件，正在加载")
        } else {
            Item(
                Capability.SPEECH,
                Level.BLOCKED,
                "离线语音缺少文件：" + missing.joinToString("、") +
                    "（目录 " + OfflineTtsBridge.resolveModelDir(context).absolutePath + "）",
            )
        }
    }

    /** 地图与定位：Key、隐私同意、定位权限逐条给原因，全过才算通过。 */
    private fun mapItem(): Item {
        val problems = ArrayList<String>()
        if (!mapKeyConfigured) problems.add("没有配置高德 Key")
        if (!readBoolean { AmapPrivacy.isAgreed(context) }) problems.add("还没有同意高德隐私政策")
        if (!PhoneLocationAdapter.hasPermission(context)) problems.add("没有定位权限")
        return if (problems.isEmpty()) {
            Item(Capability.MAP, Level.OK, "Key 已配置、隐私已同意、定位权限已授予")
        } else {
            Item(Capability.MAP, Level.BLOCKED, problems.joinToString("；"))
        }
    }

    /**
     * 眼镜蓝牙：权限、开关、已配对设备、通道状态。
     *
     * 有配对设备不等于连上了：只有 [LinkState.CONNECTED] 才算通过，正在连是待查，其余都是不通过。
     */
    private fun bluetoothItem(link: LinkState, glassesState: String): Item {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Item(Capability.BLUETOOTH, Level.BLOCKED, "没有附近的设备权限")
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return Item(Capability.BLUETOOTH, Level.BLOCKED, "本机没有蓝牙适配器")
        if (!adapter.isEnabled) return Item(Capability.BLUETOOTH, Level.BLOCKED, "手机蓝牙未开启")
        val paired = readBoolean {
            adapter.bondedDevices?.any { device ->
                val name = device.name ?: return@any false
                name.startsWith("Glasses_") || name.startsWith("RG_") || name.contains("Rokid")
            } == true
        }
        if (!paired) return Item(Capability.BLUETOOTH, Level.BLOCKED, "没有已配对的 Rokid 眼镜")
        return when (link) {
            LinkState.CONNECTED -> Item(Capability.BLUETOOTH, Level.OK, glassesState)
            LinkState.CONNECTING -> Item(Capability.BLUETOOTH, Level.PENDING, glassesState)
            LinkState.DOWN -> Item(Capability.BLUETOOTH, Level.BLOCKED, glassesState)
        }
    }

    /** 只判有没有配 Key：读的是存在性，值本身既不返回也不打印。 */
    private fun readMapKeyConfigured(): Boolean = readBoolean {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val value = info.metaData?.getString(MAP_KEY_META)
        !value.isNullOrBlank() && !value.startsWith("\${")
    }

    /** 平台查询可能抛异常；查不动一律当作“没有”，不让自检本身把宿主带崩。 */
    private inline fun readBoolean(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (error: Throwable) {
            false
        }

    private companion object {
        const val MAP_KEY_META = "com.amap.api.v2.apikey"
    }
}
