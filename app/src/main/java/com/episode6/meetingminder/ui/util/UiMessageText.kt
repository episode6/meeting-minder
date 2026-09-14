package com.episode6.meetingminder.ui.util

import android.content.res.Resources
import com.episode6.meetingminder.store.UiMessage

/** The snackbar text for [UiMessage]: a plurals lookup when it carries a quantity, a plain string otherwise. */
fun UiMessage.resolve(resources: Resources): String {
    val args = formatArgs.toTypedArray()
    return if (quantity != null) resources.getQuantityString(text, quantity, *args) else resources.getString(text, *args)
}
