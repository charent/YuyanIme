package com.yuyan.inputmethod

import android.os.SystemClock
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.manager.UserDictManager
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.libs.expression.ExpressionBuilder
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import kotlin.math.roundToInt

object CustomEngine {
    fun parseExpressionAtEnd(input: String): String? {
        val expressionEndPattern = "((φ|π|pi|sin|cos|tan|cot|asin|acos|atan|sinh|cosh|tanh|abs|log|log1p|ceil|floor|sqrt|cbrt|pow|exp|expm|signum|csc|sec|csch|sech|coth|toradian|todegree|\\(|\\)|^|%|\\+|-|\\*|/|\\.|e|E|\\d)+)$".toRegex()
        return expressionEndPattern.find(input.removeSuffix("="))?.value
    }

    fun expressionCalculator(input: String, expression: String?):Array<String>{
        val results = mutableListOf<String>()
        if(!expression.isNullOrBlank() && expression.length < 500 && !StringUtils.isNumber(expression) && !StringUtils.isLetter(expression)){
            try {
                val evaluate = ExpressionBuilder(expression).build().evaluate()
                val  resultFloat = evaluate.toFloat()
                val  resultInt = evaluate.roundToInt()
                if(evaluate.compareTo(resultInt) == 0){
                    if(!input.endsWith("=")) results.add("=${resultInt}")
                    else results.add(resultInt.toString())
                } else {
                    val resultPercentage = "${(evaluate * 100).roundToInt()}%"
                    if(!input.endsWith("=")) {
                        results.add("=".plus(resultFloat))
                        results.add("≈".plus(resultInt))
                        if(evaluate < 10 && evaluate > 0.005) results.add("=${resultPercentage}")
                    } else {
                        results.add(resultFloat.toString())
                        if(evaluate < 10 && evaluate > 0.005) results.add(resultPercentage)
                    }
                }
            } catch (_:Exception){ }
        }
        results.addAll(arrayOf("=", "+", "-", "*", "/", "%", ".", ",", "'", "(", ")"))
        return results.toTypedArray()
    }

    fun predictAssociationWordsChinese(text: String):MutableList<String> {
        val associations = mutableListOf("，", "。")
        val suffixesDays = setOf("大前天", "前天", "昨天", "今天", "明天", "大后天", "后天")
        val suffixesExclamation = setOf("啊", "呀", "呐", "啦", "噢", "哇", "吧", "呗", "了")
        val suffixesQuestion = setOf("吗", "啊", "呢", "吧", "谁", "何", "什么", "哪", "几", "多少", "怎", "难道", "岂", "不")
        val days = suffixesDays.firstOrNull{ text.endsWith(it) }
        if(days != null)associations.addAll(0, TimeUtils.getData(days))
        else if(suffixesExclamation.any{ text.endsWith(it) }){ associations.add(0, "！") }
        else if(suffixesQuestion.any{ text.contains(it) }){ associations.add(0, "？") }
        return associations
    }

    fun processPhrase(text: String):MutableList<String> {
        val phrases = mutableListOf<String>()
        // 用户词库：编码整串匹配时排在最前，自己学习/添加的词优先于自带词库
        if (getInstance().input.userDictEnable.getValue()) {
            phrases.addAll(UserDictManager.match(text))
        }
        val chinesePredictionDate = getInstance().input.chinesePredictionDate.getValue()
        if(chinesePredictionDate) {
            phrases.addAll(queryPhrases(text))
            val suffixesDate = setOf("rq", "riqi", "PGPG", "PP")
            if (suffixesDate.any { it == text }) {
                phrases.addAll(TimeUtils.getData())
            }
            val suffixesTime = setOf("sj", "shijian", "PJ", "PGGJGAM")
            if (suffixesTime.any { it == text }) {
                phrases.addAll(TimeUtils.getTime())
            }
        }
        return phrases.distinct().toMutableList()
    }

    /**
     * 常用语查询：这条路径**每敲一个键**都会走一次，而 `phrase` 表在 qwerty/t9/lx17 上没有索引，
     * 常用语一多就是一次全表扫描，输入会明显发涩。同一个编码在连续输入里会反复出现，
     * 这里加一层短 TTL 的缓存；[invalidatePhraseCache] 用于用户新增/删除常用语后立刻生效。
     */
    private fun queryPhrases(code: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        phraseCache[code]?.let { if (now - it.time <= PHRASE_CACHE_TTL_MS) return it.content }
        val content = DataBaseKT.instance.phraseDao().query(code).map { it.content }
        phraseCache[code] = CachedPhrases(now, content)
        return content
    }

    fun invalidatePhraseCache() {
        phraseCache.clear()
    }

    private class CachedPhrases(val time: Long, val content: List<String>)

    private val phraseCache = object : LinkedHashMap<String, CachedPhrases>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPhrases>): Boolean =
            size > PHRASE_CACHE_SIZE
    }

    private const val PHRASE_CACHE_SIZE = 32
    private const val PHRASE_CACHE_TTL_MS = 3000L
}