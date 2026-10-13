package com.fileforge.core.pdf

/**
 * PDF 的 `/Info` 那份元数据：要写哪些键、要清掉哪些、话怎么说，全在这里判一次。
 *
 * 为什么要单拎出来：查看与清除元数据那条已经在图片上做了（JPEG/PNG 按段照抄），
 * PDF 这一族里别人写的文件把"作者""制作软件""转换工具"都留在文件属性里，转发出去就跟着走；
 * 而"改几个字段"也是转换工具站上的常规操作。写 PDF 的那一层（安卓与桌面探针）只负责
 * 把这里算出来的改写照做，两边用的是同一份判定。
 *
 * 三条主张：
 *  - **没填的不等于要清掉**：改写模式下空着的那格是"别动它"，要清得走清除模式或明确勾掉，
 *    否则用户只想加个标题却把作者删了
 *  - **清除要清得干净**：认得的键全删，文件里自己加的那些自定义键（`Company` 这种）也一并删 ——
 *    只删我们认识的六个键，剩下那半截身份还在文件里
 *  - **改了什么要能报出来**：几项被写、几项被清、哪一项因为超长被截，都进 [Patch.notes]
 */
object PdfMeta {

    /** `/Info` 里我们认的那几个键，连同界面上说的话。顺序就是界面里列的顺序。 */
    val FIELDS = listOf(
        "Title" to "标题",
        "Author" to "作者",
        "Subject" to "主题",
        "Keywords" to "关键词",
        "Creator" to "制作软件",
        "Producer" to "转换工具",
    )

    /** PDF 里日期那两个键就叫这名（不是 ModificationDate），清除时要按文件里的写法点名。 */
    private val DATE_KEYS = listOf("CreationDate" to "创建时间", "ModDate" to "修改时间")

    /** 一个值最多留这么长：/Info 是明文写在文件里的，没必要让人塞一整篇文章。 */
    const val MAX_VALUE = 400

    /** 一份写照：[writes] 是"这个键改成这个值"，[removes] 是"这个键删掉"。 */
    class Patch(
        val writes: List<Pair<String, String>>,
        val removes: List<String>,
        val keeps: List<String>,
        val keepDates: Boolean,
        val stampNow: Boolean,
        val notes: List<String>,
    ) {
        val changed: Boolean get() = writes.isNotEmpty() || removes.isNotEmpty() || stampNow
    }

    /**
     * 算一份写照。
     *
     * @param mode `clear` 把认得的与自定义的都删掉；`edit` 只改填了值的那几格
     * @param keepDates 清除时留不留两个日期（日期本身也是身份信息，默认一起清）
     * @param stampNow 把"修改时间"盖成现在（改完不给个新时间，别人看着还是旧文件）
     */
    fun patch(
        mode: String,
        values: Map<String, String>,
        keepDates: Boolean = false,
        stampNow: Boolean = false,
        nowMillis: Long = 0L,
    ): Patch {
        val notes = ArrayList<String>()
        val writes = ArrayList<Pair<String, String>>()
        val removes = ArrayList<String>()
        if (mode == "clear" || mode == "clear-standard") {
            FIELDS.forEach { (key, _) -> removes += key }
            if (!keepDates) DATE_KEYS.forEach { (key, _) -> removes += key }
            // "只清标准字段"这一档留着文件自己加的键：有人就靠 Company 认自己的文件
            if (mode == "clear") removes += "*"                 // 文件自己加的自定义键
            if (mode == "clear-standard") notes += "文件里自己加的字段（如 Company）留着：要一起清就选清除全部"
            if (keepDates) notes += "两个日期留着了（日期本身也算身份信息，要一起清就换回默认）"
            if (stampNow) notes += "清除模式下不另盖修改时间（刚删掉的东西别再写回去）"
            return Patch(writes, removes, if (keepDates) DATE_KEYS.map { it.first } else emptyList(), keepDates, false, notes)
        }
        FIELDS.forEach { (key, label) ->
            val given = values[key].orEmpty().trim()
            when {
                given.isEmpty() -> Unit
                given.length > MAX_VALUE -> {
                    writes += key to given.take(MAX_VALUE)
                    notes += "「$label」有 ${given.length} 个字，太长的那截没写进去（最多留 $MAX_VALUE）"
                }
                else -> writes += key to given
            }
        }
        if (writes.isEmpty() && !stampNow) notes += "没填要改的字段，也没勾盖时间：这份原样不动"
        if (writes.isNotEmpty()) {
            notes += "改写 ${writes.size} 项：${writes.joinToString("、") { (k, _) -> FIELDS.first { it.first == k }.second }}"
        }
        return Patch(writes, removes, emptyList(), keepDates, stampNow && nowMillis > 0L, notes)
    }

    /** 日期那两个键的界面用语（app 与探针都要按这个名单读文件里原来的值）。 */
    fun dateLabels(): List<Pair<String, String>> = DATE_KEYS

    /** 键名 → 界面用语；认不出来的按"文件里自己加的"说。 */
    fun label(key: String): String =
        (FIELDS + DATE_KEYS).firstOrNull { it.first == key }?.second
            ?: "自定义（$key）"

    /** PDF 的日期写法：`D:YYYYMMDDHHmmSS+08'00'`；没有时区就是本地时间，我们一律按 +00'00' 给。 */
    fun pdfDate(millis: Long): String {
        val utc = java.util.Date(millis)
        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss")
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return "D:" + format.format(utc) + "+00'00'"
    }

    /** 库把日期读回来是个时刻（Calendar），先转成 PDF 的写法再走同一句人话，免得出现第二套格式化。 */
    fun readableMillis(millis: Long): String = readableDate(pdfDate(millis))

    /** 把 PDF 里那个日期写法翻回人能读的一句；解不开就说原样。 */
    fun readableDate(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val body = raw.removePrefix("D:").trim()
        val digits = Regex("""^\d{8}(\d{2}(\d{2}(\d{2})?)?)?""").find(body)?.value ?: return raw
        val year = digits.substring(0, 4)
        val month = digits.getOrElse(4) { '0' }.toString() + digits.getOrElse(5) { '0' }
        val day = digits.getOrElse(6) { '0' }.toString() + digits.getOrElse(7) { '0' }
        val hour = if (digits.length >= 10) digits.substring(8, 10) else null
        val minute = if (digits.length >= 12) digits.substring(10, 12) else null
        val zone = body.substringAfterLast(digits, "")
        val clock = listOfNotNull(hour, minute?.let { "$it" }).joinToString(":")
        return listOfNotNull("$year-$month-$day", clock.takeIf { it.isNotEmpty() }, zone.takeIf { it.isNotEmpty() })
            .joinToString(" ")
    }
}
