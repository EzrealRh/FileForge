package com.fileforge.core.model

/**
 * 批次血缘：同一次导入进来的文件算一批，这批文件转出来的结果也算这批，
 * 链式再加工（HEIC→JPG→压缩→合成 PDF）一路跟着走。
 */
object BatchLineage {

    /** 还没归批的占位值。 */
    const val NONE = 0L

    /**
     * 产物该归哪一批：来源全在同一批就继承；跨了多批就返回 null，
     * 由调用方另起一批 —— 硬塞进其中一批会让两批的历史都失真。
     */
    fun inherit(sourceGroupIds: Collection<Long>): Long? {
        val distinct = sourceGroupIds.filter { it != NONE }.distinct()
        return if (distinct.size == 1) distinct.first() else null
    }
}
