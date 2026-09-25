package com.fileforge.core.pdf

/** 加密时想限制的权限。界面按这四个开关摆，映射到 PDF 权限位在引擎里做。 */
enum class PdfPermission(val label: String, val blockedName: String, val note: String) {
    Print("允许打印", "打印", "不勾就是不给打印"),
    Copy("允许复制文字", "复制文字", "不勾只是给阅读器看的限制，拦不住程序读文件（实测过）"),
    Modify("允许修改", "修改内容", "不勾就是不给改内容和加批注"),
    FillForms("允许填表", "填表单", "不勾就是不给填表单域"),
    ;
}

/**
 * PDF 加密码这件事上的几条硬规矩。
 *
 * 都来自实测（`tools/pdfprobe/run.sh security`），不是想当然：
 *  - PDFBox 的所有者密码留空时会自己造一个随机的 —— 用户看不见也记不住，
 *    所以实现上**绝不允许产出那种文件**：留空就按打开密码填同一个。
 *  - 只用打开密码就能解除保护，所以界面只需要一个必填密码，不必逼人记两个。
 *  - 权限位是"建议"而不是强制：勾了「不允许复制」，程序照样抽得出文字。
 *    话术必须照实说，不能假装那是道锁。
 */
object PdfSecurity {

    /** 一句话说明权限位为什么不是一道锁。界面上要出现在限制开关旁边。 */
    const val ADVISORY_NOTE = "这些限制是给阅读器看的行为约定，不是加密锁：懂命令的人仍能读出内容。真正挡住别人的是打开密码。"

    /** 密码最短长度。PDF 本身不限制，但 1~2 位的密码等于没设，且用户会以为安全了。 */
    const val MIN_PASSWORD = 4

    /**
     * 校验加密参数。返回 null 表示可以动手。
     *
     * 两种"等于没加密"的情况都要拦下来而不是默默产出：密码太短，以及
     * 四个限制全放开 —— 那种文件别人打开毫无障碍，用户却以为自己保护过了。
     */
    fun validate(userPassword: String, ownerPassword: String, granted: Set<PdfPermission>): String? {
        if (userPassword.isBlank() && ownerPassword.isBlank()) return "至少写一个打开密码"
        if (userPassword.isNotBlank() && userPassword.length < MIN_PASSWORD)
            return "打开密码少于 $MIN_PASSWORD 位，等于没设"
        if (ownerPassword.isNotBlank() && ownerPassword.length < MIN_PASSWORD)
            return "所有者密码少于 $MIN_PASSWORD 位，等于没设"
        if (granted.size == PdfPermission.entries.size) return "四项限制全都放开了，这样加密等于没加密"
        return null
    }

    /**
     * 所有者密码留空时的取值。留空给 PDFBox 它会随机造一个谁也记不住的，
     * 于是"以后想解开"就没了退路 —— 所以这里强制按打开密码填同一个。
     */
    fun ownerPasswordFor(ownerPassword: String, userPassword: String): String =
        ownerPassword.ifBlank { userPassword }

    /** 打开密码：留空时为空串，表示"不设打开密码，只限制操作"。 */
    fun userPasswordOf(userPassword: String): String = userPassword

    /** 结果说明里那一句：这次到底限制了什么。全勾时是"什么都没限制"（validate 会先拦住）。 */
    fun summarize(granted: Set<PdfPermission>): String {
        val blocked = PdfPermission.entries.filter { it !in granted }.map { it.blockedName }
        return if (blocked.isEmpty()) "四项限制全放开" else "不让：" + blocked.joinToString("、")
    }
}
