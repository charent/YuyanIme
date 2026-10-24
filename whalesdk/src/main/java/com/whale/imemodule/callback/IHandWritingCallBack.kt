package com.whale.imemodule.callback

import com.whale.inputmethod.core.CandidateListItem

fun interface IHandWritingCallBack {
    fun onSucess(item:Array<CandidateListItem>)
}