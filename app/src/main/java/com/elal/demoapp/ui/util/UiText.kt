package com.elal.demoapp.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.text.BidiFormatter

sealed class UiText {
    data class DynamicString(val value: String) : UiText()
    class StringResource(@param:StringRes val resId: Int, vararg val args: Any) : UiText()

    @Composable
    fun asString(): String = when (this) {
        is DynamicString -> value
        is StringResource -> {
            val bidi = BidiFormatter.getInstance()
            val wrapped = args.map { if (it is String) bidi.unicodeWrap(it) else it }.toTypedArray()
            stringResource(resId, *wrapped)
        }
    }
}
