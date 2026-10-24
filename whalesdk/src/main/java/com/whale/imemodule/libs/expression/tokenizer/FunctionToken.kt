package com.whale.imemodule.libs.expression.tokenizer

import com.whale.imemodule.libs.expression.function.Function

class FunctionToken(@JvmField val function: Function) : Token(TOKEN_FUNCTION)
