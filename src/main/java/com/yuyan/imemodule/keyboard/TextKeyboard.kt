package com.yuyan.imemodule.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.KeyEvent
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.data.theme.ThemeManager.prefs
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyToggle
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode
import com.yuyan.imemodule.service.InputDispatcher

/**
 * 软件盘视图
 */
open class TextKeyboard(context: Context?) : BaseKeyboardView(context){
    private var mKeyboardChanged = false
    private var mBuffer: Bitmap? = null
    private var mCanvas: Canvas? = null
    private var mNormalKeyTextSize = 0   //正常按键的文本大小
    private var mNormalKeyTextSizeSmall = 0  //正常按键的文本大小(小值)
    private val mPaint: Paint = Paint()   //绘制按键的画笔
    private val mSymbolBounds = Rect() // 上方小标点的墨迹范围，用于按墨迹下沿对齐
    private var isKeyBorder = false // 启用按键边框
    protected lateinit var mActiveTheme: Theme
    private var keyRadius = 0
    private var keyboardFontBold = false
    private var keyboardSymbol = false
    private var keyboardMnemonic = false
    protected var mDirtyRect = Rect()
    private var skbStyleMode: SkbStyleMode = prefs.skbStyleMode.getValue()

    /**
     * 构造方法
     */
    init {
        mPaint.isAntiAlias = true
        keyboardFontBold = prefs.keyboardFontBold.getValue()
        keyboardSymbol = prefs.keyboardSymbol.getValue()
        keyboardMnemonic = AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
    }

    /**
     * 设置键盘实体
     *
     * @param softSkb 键盘
     */
    override fun setSoftKeyboard(softSkb: SoftKeyboard) {
        super.setSoftKeyboard(softSkb)
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = activeTheme
        mPaint.color = mActiveTheme.keyTextColor
        mKeyboardChanged = true
        invalidateView()
    }

    /**
     * 刷新按键状态
     */
    fun updateStates() {
        var softKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_ENTER) as? SoftKeyToggle
        softKey?.enableToggleState( if(mService!!.isAddPhrases)4 else InputModeSwitcher.mToggleStates.imeAction)
        softKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_SHIFT_LEFT) as? SoftKeyToggle
        val isEnglishCell = AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()
        softKey?.enableToggleState(InputModeSwitcher.mToggleStates.modifiers + if(isEnglishCell) 3 else 0)
        invalidateView()
    }

    /**
     * 重置主题
     */
    open fun setTheme(theme: Theme) {
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = theme
        mPaint.color = mActiveTheme.keyTextColor
        invalidateView()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var measuredWidth = 0
        var measuredHeight = 0
        if (null != mSoftKeyboard) {
            measuredWidth = instance.skbWidth +  paddingLeft + paddingRight
            measuredHeight = instance.skbHeight + paddingTop + paddingBottom
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    private fun invalidateView() {
        requestLayout()
        invalidateKey()
    }

    public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mBuffer = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        mDirtyRect.union(0, 0, width, height)
        if (mDrawPending || mBuffer == null || mKeyboardChanged) {
            onBufferDraw()
        }
        canvas.drawBitmap(mBuffer!!, 0f, 0f, null)
    }

    override fun onBufferDraw() {
        if (mBuffer == null || mKeyboardChanged) {
            if (mBuffer == null || mBuffer!!.width != width || mBuffer!!.height != height) {
                val width = max(1.0, width.toDouble()).toInt()
                val height = max(1.0, height.toDouble()).toInt()
                mBuffer = createBitmap(width, height)
                mCanvas = Canvas(mBuffer!!)
            }
            invalidateKey()
            mKeyboardChanged = false
        }
        if (mSoftKeyboard == null) return
        mCanvas!!.withSave {
            val canvas = mCanvas
            canvas?.clipRect(mDirtyRect)
            canvas?.drawColor(0x00000000, PorterDuff.Mode.CLEAR)
            val env = instance
            mNormalKeyTextSize = env.keyTextSize
            mNormalKeyTextSizeSmall = env.keyTextSmallSize
            val keyXMargin = mSoftKeyboard!!.keyXMargin
            val keyYMargin = if(skbStyleMode == SkbStyleMode.Google && InputModeSwitcher.isQwert) mSoftKeyboard!!.keyYMargin * 1.5
                else mSoftKeyboard!!.keyYMargin
            for (softKeys in mSoftKeyboard!!.mKeyRows) {
                for (softKey in softKeys) {
                    canvas?.let { drawSoftKey(it, softKey, keyXMargin, keyYMargin.toInt()) }
                }
            }
            mCanvas!!
        }
        mDrawPending = false
        mDirtyRect.setEmpty()
    }

    /**
     * 在画布上画一个按键
     *
     * @param canvas     画布
     * @param softKey    需绘制的按键
     * @param keyXMargin 按键左右边间距
     * @param keyYMargin 按键上下边间距
     */
    private fun drawSoftKey(canvas: Canvas, softKey: SoftKey, keyXMargin: Int, keyYMargin: Int) {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = keyRadius.toFloat() // 设置圆角半径
        bg.setBounds(softKey.mLeft + keyXMargin, softKey.mTop + keyYMargin, softKey.mRight - keyXMargin, softKey.mBottom - keyYMargin)
        if (softKey.pressed || (mService?.hasSelection == true && softKey.code == InputModeSwitcher.USER_KEYCODE_SELECT_MODE)) {
            bg.setColor(mActiveTheme.keyPressHighlightColor)
            bg.draw(canvas)
        } else if (isKeyBorder) {
            val background = when (softKey.code) {
                KeyEvent.KEYCODE_ENTER -> mActiveTheme.accentKeyBackgroundColor
                KeyEvent.KEYCODE_SPACE-> mActiveTheme.functionKeyBackgroundColor
                else  -> mActiveTheme.keyBackgroundColor
            }
            bg.setColor(background)
            bg.draw(canvas)
        } else if(softKey.code == KeyEvent.KEYCODE_ENTER) {
               bg.setColor(mActiveTheme.accentKeyBackgroundColor)
               bg.shape = GradientDrawable.OVAL
               val bgWidth = softKey.width() -  keyXMargin
               val bgHeight = softKey.height() - keyYMargin
               val radius = min(bgWidth, bgHeight)*3/4
               val keyMarginX = (bgWidth - radius)/2
               val keyMarginY = (bgHeight - radius)/2
                bg.setBounds(softKey.mLeft + keyMarginX, softKey.mTop + keyMarginY, softKey.mRight - keyMarginX, softKey.mBottom - keyMarginY)
                bg.draw(canvas)
        }
        // 语言切换键原本用矢量字形图标绘制，字形/字重与键盘字体对不上，这里改用文本绘制（stateId=2 是地球图标，保留）
        val isLangKey = softKey.code == InputModeSwitcher.USER_KEYCODE_LANG && softKey.stateId != 2
        var keyLabel = when {
            isLangKey -> "" // 语言键由下方独立分支绘制（主字大、副字小），不走普通文本路径
            skbStyleMode == SkbStyleMode.Google ->
                if (InputModeSwitcher.isLower) softKey.keyLabel.lowercase() else softKey.keyLabel
            else ->
                if (InputModeSwitcher.isLower && InputModeSwitcher.isEnglish) softKey.keyLabel.lowercase() else softKey.keyLabel
        }
        val keyLabelSmall = softKey.getmKeyLabelSmall()
        val keyMnemonic = softKey.keyMnemonic
        var keyIcon = if(skbStyleMode == SkbStyleMode.Google && softKey.code == KeyEvent.KEYCODE_SPACE) null
            else if(skbStyleMode == SkbStyleMode.Google && softKey.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION && !DictDecoder.isCandidatesEmpty) null
            else if(isLangKey) null
            else softKey.keyIcon
        val weightHeigth = softKey.height() / 4f
        val textColor = mActiveTheme.keyTextColor
        if(softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT && InputModeSwitcher.isChinese && !InputDispatcher.isFinish){
            keyLabel = "分词"
            keyIcon = null
        }
        // 小标点是否与字母上下叠放：雅燕风格为上下叠放，其余风格贴在键帽右上方
        val stackedSymbol = keyboardSymbol && !TextUtils.isEmpty(keyLabelSmall) &&
                prefs.skbStyleMode.getValue() == SkbStyleMode.Yuyan
        // 垂直位置一律按字号的固定比例推算：所有按键共用同一条基线，不会因逐字测量墨迹而高低不齐
        val keyTextSizeF = mNormalKeyTextSize.toFloat()
        val smallTextSizeF = mNormalKeyTextSizeSmall * SYMBOL_SIZE_RATIO
        val letterCap = keyTextSizeF * CAP_HEIGHT_RATIO
        val symbolCap = smallTextSizeF * CAP_HEIGHT_RATIO
        val symbolGap = smallTextSizeF * SYMBOL_GAP_RATIO
        // 字母视觉中心：键帽几何中心略下移，读起来更稳
        var letterCenterY = if (keyboardMnemonic && !TextUtils.isEmpty(keyMnemonic))
            softKey.mTop + softKey.height() * 0.42f
        else (softKey.mTop + softKey.mBottom) / 2.0f + softKey.height() * LETTER_CENTER_OFFSET_RATIO
        // 符号墨迹顶边按「字母顶边 - 间隙 - 符号高度」推算，键帽顶部留白不足时整组下压
        var symbolInkTop = letterCenterY - letterCap / 2.0f - symbolGap - symbolCap
        val minSymbolTop = softKey.mTop + softKey.height() * SYMBOL_MIN_TOP_RATIO
        if (stackedSymbol && symbolInkTop < minSymbolTop) {
            letterCenterY += minSymbolTop - symbolInkTop
            symbolInkTop = minSymbolTop
        }
        val letterBaseline = letterCenterY + letterCap / 2.0f
        val symbolBaseline = if (stackedSymbol) {
            // 按墨迹下沿对齐：@ 等字符低于基线，直接按基线摆放会离字母过近
            mPaint.textSize = smallTextSizeF
            mPaint.typeface = Typeface.DEFAULT
            mPaint.getTextBounds(keyLabelSmall, 0, keyLabelSmall.length, mSymbolBounds)
            symbolInkTop + symbolCap - mSymbolBounds.bottom
        } else 0f

        if (keyboardSymbol && !TextUtils.isEmpty(keyLabelSmall)) {
            mPaint.color = textColor
            mPaint.setTypeface(Typeface.DEFAULT)
            if(skbStyleMode == SkbStyleMode.Samsung)mPaint.alpha = 128
            mPaint.textSize = smallTextSizeF
            val x = when(prefs.skbStyleMode.getValue()){
                SkbStyleMode.Yuyan -> softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabelSmall)) / 2.0f
                SkbStyleMode.Samsung -> softKey.mRight - mPaint.measureText(keyLabelSmall) - keyXMargin * 2
                SkbStyleMode.Google -> softKey.mRight - mPaint.measureText(keyLabelSmall) - keyXMargin * 2
            }
            // 叠放时按组块顶边定位，其余风格沿用键帽上部固定位置
            val y = if (stackedSymbol) symbolBaseline else softKey.mTop + weightHeigth * 1.1f
            canvas.drawText(keyLabelSmall, x, y, mPaint)
            mPaint.alpha = 255
        }
        if (isLangKey) {
            // 主字（当前语言）大且固定加粗、副字（可切换语言）小且固定常规，与「键盘字母加粗」全局设置解耦
            mPaint.color = textColor
            val mainTypeface = Typeface.DEFAULT_BOLD
            val subTypeface = Typeface.DEFAULT
            // 主字跟随实际输入语言：中文输入时「中」大，英文输入时「EN」大
            val langIsEnglish = InputModeSwitcher.isEnglish
            val langMain = if (langIsEnglish) LANG_KEY_MAIN_EN else LANG_KEY_MAIN_CN
            val langSub = if (langIsEnglish) LANG_KEY_SUB_CN else LANG_KEY_SUB_EN
            var mainSize = keyTextSizeF * LANG_KEY_MAIN_RATIO
            var subSize = mainSize * LANG_KEY_SUB_RATIO
            mPaint.typeface = mainTypeface
            mPaint.textSize = mainSize
            var mainWidth = mPaint.measureText(langMain)
            mPaint.typeface = subTypeface
            mPaint.textSize = subSize
            var subWidth = mPaint.measureText(langSub)
            // 文字比单字母长，整组超宽时等比缩小，保证不出键帽
            val maxWidth = softKey.width() - keyXMargin * 2f - LANG_KEY_SIDE_PADDING
            if (maxWidth > 0f && mainWidth + subWidth > maxWidth) {
                val scale = maxWidth / (mainWidth + subWidth)
                mainSize *= scale
                subSize *= scale
                mainWidth *= scale
                subWidth *= scale
            }
            val startX = softKey.mLeft + (softKey.width() - mainWidth - subWidth) / 2.0f
            // 主字按真实墨迹高度居中于键帽（不沿用字母组的 6% 下移，否则大字会明显压底）
            val langCenterY = (softKey.mTop + softKey.mBottom) / 2.0f
            mPaint.typeface = mainTypeface
            mPaint.textSize = mainSize
            mPaint.getTextBounds(langMain, 0, langMain.length, mSymbolBounds)
            val mainBaseline = langCenterY + mSymbolBounds.height() / 2.0f - mSymbolBounds.bottom
            val mainInkBottom = mainBaseline + mSymbolBounds.bottom
            // 副字与主字墨迹底边对齐，避免大字底部往下凸出
            mPaint.typeface = subTypeface
            mPaint.textSize = subSize
            mPaint.getTextBounds(langSub, 0, langSub.length, mSymbolBounds)
            val subBaseline = mainInkBottom - mSymbolBounds.bottom
            mPaint.typeface = mainTypeface
            mPaint.textSize = mainSize
            canvas.drawText(langMain, startX, mainBaseline, mPaint)
            mPaint.typeface = subTypeface
            mPaint.textSize = subSize
            canvas.drawText(langSub, startX + mainWidth, subBaseline, mPaint)
        } else if (null != keyIcon) {
            var  intrinsicWidth = keyIcon.intrinsicWidth
            var  intrinsicHeight = keyIcon.intrinsicHeight
            while(softKey.width() < intrinsicWidth || softKey.height() < intrinsicHeight){
                intrinsicWidth /= 2
                intrinsicHeight /= 2
            }
            val marginLeft = (softKey.width() - intrinsicWidth) / 2
            val marginRight = softKey.width() - intrinsicWidth - marginLeft
            val marginTop = (softKey.height() - intrinsicHeight) / 2
            val marginBottom = softKey.height() - intrinsicHeight - marginTop
            keyIcon.setTint(mActiveTheme.keyTextColor)
            keyIcon.setBounds(softKey.mLeft + marginLeft, softKey.mTop + marginTop, softKey.mRight - marginRight, softKey.mBottom - marginBottom)
            keyIcon.draw(canvas)
        } else if (!TextUtils.isEmpty(keyLabel)) { //Label位于中间
            mPaint.color = textColor
            mPaint.typeface = if (keyboardFontBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            mPaint.textSize =  keyTextSizeF
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabel)) / 2.0f
            canvas.drawText(keyLabel, x, letterBaseline, mPaint)
        }
        if (keyboardMnemonic && !TextUtils.isEmpty(keyMnemonic)) {  //助记符位于中下方
            mPaint.color = textColor
            mPaint.typeface = Typeface.DEFAULT
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat() * 0.7f
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyMnemonic)) / 2.0f
            val y = softKey.mTop + weightHeigth * 3 + weightHeigth / 2.0f
            canvas.drawText(keyMnemonic, x, y, mPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    override fun closing() {
        super.closing()
        mBuffer = null
        mCanvas = null
    }

    companion object {
        // 字母、数字的视觉高度相对字号的比例，用于把内容在键帽内垂直居中
        private const val CAP_HEIGHT_RATIO = 0.72f

        // 上方小标点相对原小字号的缩放，压低存在感，避免喧宾夺主
        private const val SYMBOL_SIZE_RATIO = 0.75f

        // 上方小标点与字母之间的间隙，相对标点字号
        private const val SYMBOL_GAP_RATIO = 1.0f

        // 键帽顶部最小留白，相对键高；不够时整组下压，防止符号顶出键帽
        private const val SYMBOL_MIN_TOP_RATIO = 0.05f

        // 字母视觉中心相对键帽几何中心的下移量，相对键高
        private const val LETTER_CENTER_OFFSET_RATIO = 0.06f

        // 语言切换键的文本：主字为当前语言、副字为可切换语言；原图标就是「大中 + 小/En」的主次关系
        private const val LANG_KEY_MAIN_CN = "中"
        private const val LANG_KEY_SUB_EN = "/EN"
        private const val LANG_KEY_MAIN_EN = "EN"
        private const val LANG_KEY_SUB_CN = "/中"

        // 主字明显大于普通按键字母（受键宽限制，副字压小为主字让出空间）
        private const val LANG_KEY_MAIN_RATIO = 1.45f
        private const val LANG_KEY_SUB_RATIO = 0.35f

        // 语言键文字两侧的最小留白（px），防止贴到键帽边缘
        private const val LANG_KEY_SIDE_PADDING = 4f
    }
}
