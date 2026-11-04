package com.pagefold.reader.field

/**
 * 出站请求的身份。
 *
 * 界面上只让玩家挑「客户端」—— Chrome、Edge、Android、iOS 四选一。
 * Origin / Accept-Language 这类不用他操心，写死。
 *
 * 注意：这个 object 的属性按声明顺序初始化。谁调用谁，谁就必须先声明。
 * DEFAULT 写在 UA 前面的话，DEFAULT 初始化时 UA 还是 null，第一次碰这个类就 NPE。
 * App.onCreate 里会碰，于是开机即闪退。
 */
object Headers {

    /**
     * 只留两台 Windows 上的浏览器。
     *
     * 手机 UA 那两个试过了，对方不认 —— 要么 401，要么直接不回音频。
     * 这台接口是按桌面端签发的，装成手机反而是破绽。少两个选项，少两次踩空。
     */
    val CLIENTS: List<Pair<String, String>> = listOf(
        "chrome" to "Chrome · Windows",
        "edge" to "Edge · Windows",
    )

    private val UA: Map<String, String> = mapOf(
        "chrome" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "edge" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
        "android" to "Mozilla/5.0 (Linux; Android 10; MI 8 SE) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36",
        "ios" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1",
    )

    /** UA 之后才声明。 */
    val DEFAULT: List<Pair<String, String>> = of("chrome")

    fun label(key: String?): String =
        CLIENTS.firstOrNull { it.first == key }?.second ?: CLIENTS[0].second

    /** 参数收可空：旧存档里没有这个字段时，Gson 会把 String 填成 null。 */
    fun of(client: String?): List<Pair<String, String>> = listOf(
        "User-Agent" to (UA[client] ?: UA.getValue("edge")),
        "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    /** 兼容旧字段：一行一个头的自由写法。空输入回默认。 */
    fun parse(raw: String?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        raw.orEmpty().lines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) return@forEach
            val i = t.indexOf(':')
            if (i <= 0) return@forEach
            val k = t.substring(0, i).trim()
            val v = t.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out += k to v
        }
        return if (out.isEmpty()) DEFAULT else out
    }

    /** 设置 → 实际要发的东西。自定义为空就用选的那套预设。 */
    fun build(s: Settings): List<Pair<String, String>> =
        if (s.headers.isNullOrBlank()) of(s.client) else parse(s.headers)

    /** 预设写成文本，塞进可编辑框里当起点。 */
    fun presetText(client: String?): String =
        of(client).joinToString("\n") { "${it.first}: ${it.second}" }

    fun ua(list: List<Pair<String, String>>): String =
        list.firstOrNull { it.first.equals("User-Agent", true) }?.second ?: DEFAULT.first().second

    fun language(list: List<Pair<String, String>>): String =
        list.firstOrNull { it.first.equals("Accept-Language", true) }?.second ?: "zh-CN,zh;q=0.9"
}
