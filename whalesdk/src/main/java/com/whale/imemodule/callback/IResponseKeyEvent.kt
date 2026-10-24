package com.whale.imemodule.callback

import com.whale.imemodule.entity.keyboard.SoftKey
import com.whale.imemodule.prefs.behavior.PopupMenuMode
import com.whale.inputmethod.core.CandidateListItem

interface IResponseKeyEvent {
    fun responseKeyEvent(sKey: SoftKey)
    fun responseLongKeyEvent(result:Pair<PopupMenuMode, String>)
    fun responseHandwritingResultEvent(words: Array<CandidateListItem>)
}
