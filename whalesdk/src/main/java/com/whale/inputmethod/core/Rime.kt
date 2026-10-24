package com.whale.inputmethod.core

import android.content.Context
import com.whale.imemodule.application.CustomConstant
import com.whale.imemodule.application.Launcher

/**
 * librime 的 Kotlin 门面。
 *
 * 设计约束：native 侧（`libwhaleime.so`）只保留「Kotlin 调不到 C ABI」的必需胶水，
 * 一律直通 `rime_api.h` 的公开 C API；所有可以表达为普通字符串/数组操作的原生逻辑
 * 都放在这里（见 [replaceKey]、[RimeKeycode]、以及 `RimeEngine` 里的候选分页）。
 */
class Rime(fullCheck: Boolean) {

    init {
        startup(Launcher.instance.context, fullCheck)
    }

    companion object {
        private var instance: Rime? = null
        private var mContext: RimeContext? = null

        @JvmStatic
        fun getInstance(fullCheck: Boolean = false): Rime {
            if (instance == null) instance = Rime(fullCheck)
            return instance!!
        }

        init {
            System.loadLibrary("whaleime")
        }

        fun startup(context: Context, fullCheck: Boolean) {
            // shared 只读（rime-v<N> 预编译词典），user 可写（userdb/部署产物），分离后
            // 每次启动 maintenance 只扫 user 目录，不再对 112MB 共享词库做全量校验
            startupRime(context, CustomConstant.RIME_SHARED_PATH, CustomConstant.RIME_USER_PATH, fullCheck)
            updateContext()
        }

        @JvmStatic
        fun destroy() {
            exitRime()
            instance = null
        }

        /**
         * 刷新组合串与候选区元信息。
         *
         * **调用时机**：引擎状态发生变化的每一个入口都会调它（[processKey] / [replaceKey] /
         * [selectCandidate] / [clearComposition] / [selectSchema]），所以按键路径上的调用方
         * 直接读 [compositionText] 就是最新的，不要为了"保险"再拉一次。
         *
         * `get_context` 会顺带按 schema 的 `menu/page_size` 建一页候选（现在这个值已降到 5，
         * 候选本体走 [getCandidates]），它本身也不再返回候选数组。
         */
        fun updateContext() {
            mContext = getRimeContext() ?: RimeContext()
        }

        @JvmStatic
        val composition: RimeComposition?
            get() = mContext?.composition

        @JvmStatic
        val compositionText: String
            get() = composition?.preedit ?: ""

        @JvmStatic
        fun processKey(keycode: Int, mask: Int): Boolean {
            if (keycode <= 0 || keycode == 0xffffff) return false
            return processRimeKey(keycode, mask).also {
                updateContext()
            }
        }

        /** librime 的原始组合串（raw input），只含拼音码与分隔符 */
        @JvmStatic
        fun getInput(): String = getRimeInput()

        /**
         * 把组合串里 `[caretPos, caretPos + length)` 换成 [text]，等价于旧 librime 内部的
         * `Context::ReplaceInput`：替换后光标移到末尾并重新组句（`set_input` 就是这个语义）。
         *
         * 坐标系差异：librime 的 `pos`/`len` 是 **UTF-8 字节偏移**，Kotlin 字符串是 UTF-16 下标。
         * 小鲸鱼的组合串恒为拼音码（ASCII 字母 + 空格/单引号分隔符），两者相等；
         * 这里显式拒绝非 ASCII 组合串，避免将来组合串里出现中文时静默错位。
         * 调用方拿不到 `true` 时应走自己的兜底路径（例如 `CorrectionSession` 的清空重放）。
         */
        @JvmStatic
        fun replaceKey(caretPos: Int, length: Int, key: String): Boolean {
            val input = getRimeInput()
            val ok = if (input.any { it.code > 0x7F } ||
                caretPos < 0 || length < 0 || caretPos + length > input.length
            ) {
                false
            } else {
                val next = input.substring(0, caretPos) + key + input.substring(caretPos + length)
                setRimeInput(next)
            }
            updateContext()
            return ok
        }

        @JvmStatic
        fun clearComposition() {
            clearRimeComposition()
            updateContext()
        }

        /**
         * 全量候选列表的一段：从**全局下标** [offset] 起取最多 [limit] 条。
         *
         * 下标即 `select_candidate` 的入参，不再有「页内 / 全局」两套语义；
         * 分页（向后取更多）由调用方自己维护 offset，引擎侧不需要翻页动作。
         */
        @JvmStatic
        fun getCandidates(offset: Int, limit: Int): List<CandidateListItem> {
            if (offset < 0 || limit <= 0) return emptyList()
            return getRimeCandidates(offset, limit).asList()
        }

        @JvmStatic
        fun selectCandidate(index: Int): Boolean {
            return selectRimeCandidate(index).also {
                updateContext()
            }
        }

        @JvmStatic
        fun setOption(option: String, value: Boolean) {
            setRimeOption(option, value)
        }

        @JvmStatic
        fun selectSchema(schemaId: String): Boolean {
            return selectRimeSchema(schemaId).also {
                updateContext()
            }
        }

        fun getAssociateList(key: String?): Array<String?> {
            return getRimeAssociateList(key)
        }

        fun chooseAssociate(index: Int): Boolean {
            return selectRimeAssociate(index)
        }

        // ------------------------------------------------------------------
        // native 直通方法：与 rime_api.h 一一对应，不含任何逻辑
        // ------------------------------------------------------------------

        @JvmStatic
        external fun startupRime(context: Context, sharedDir: String, userDir: String, fullCheck: Boolean, )

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun processRimeKey(keycode: Int, mask: Int): Boolean

        /** `api->get_input`：返回组合串，调用内即拷成 jstring */
        @JvmStatic
        external fun getRimeInput(): String

        /** `api->set_input`：整串替换组合串，光标移到末尾并重新组句 */
        @JvmStatic
        external fun setRimeInput(input: String): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        @JvmStatic
        external fun getRimeCommit(): RimeCommit?

        @JvmStatic
        external fun getRimeContext(): RimeContext?

        /**
         * `api->get_status` 直通。**不要放进按键路径**：小鲸鱼目前不用组句状态/开关态
         * （候选是否为空看 [getCandidates]，是否在组合看 [compositionText]），
         * 留作状态标签、调试或部署进度之类的按需查询。
         */
        @JvmStatic
        external fun getRimeStatus(): RimeStatus?

        @JvmStatic
        external fun setRimeOption(option: String, value: Boolean, )

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        @JvmStatic
        external fun selectRimeCandidate(index: Int): Boolean

        /** `api->candidate_list_from_index` + `candidate_list_next`：全量候选列表切片 */
        @JvmStatic
        external fun getRimeCandidates(offset: Int, limit: Int): Array<CandidateListItem>

        @JvmStatic
        external fun getRimeAssociateList(key: String?): Array<String?>

        @JvmStatic
        external fun selectRimeAssociate(index: Int): Boolean
    }
}
