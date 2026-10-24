@file:Suppress("unused", "NOTHING_TO_INLINE")

package org.jellyfin.mobile.utils

import android.content.Context
import android.content.res.Resources
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.Window
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

inline fun Context.toast(@StringRes text: Int, duration: Int = Toast.LENGTH_SHORT) =
    Toast.makeText(this, text, duration).show()

inline fun Context.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) =
    Toast.makeText(this, text, duration).show()

inline fun LifecycleOwner.runOnUiThread(noinline block: suspend CoroutineScope.() -> Unit) {
    lifecycleScope.launch(Dispatchers.Main, block = block)
}

fun LayoutInflater.withThemedContext(context: Context, @StyleRes style: Int): LayoutInflater {
    return cloneInContext(ContextThemeWrapper(context, style))
}

fun View.applyWindowInsetsAsMargins() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        updateLayoutParams<MarginLayoutParams> {
            setMargins(insets.left, insets.top, insets.right, insets.bottom)
        }
        windowInsets
    }
}

/**
 * 淡入到可见（幂等：已经可见且不透明时不做任何事）。
 *
 * 不使用 `withLayer()`：给 WebView 套硬件图层会让整层内容失效，
 * 正是"从其它路由返回后页面全黑"的一类原因；动画结束再显式把 alpha 落到终态，
 * 避免动画被打断（组合销毁）时停在中间透明度。
 */
fun View.fadeIn() {
    if (isVisible && alpha == 1f) return
    animate().cancel()
    alpha = 0f
    isVisible = true
    animate()
        .alpha(1f)
        .setDuration(300L)
        .setInterpolator(LinearOutSlowInInterpolator())
        .withEndAction { alpha = 1f }
        .start()
}

inline fun Resources.dip(px: Int) = (px * displayMetrics.density).toInt()

inline var Window.brightness: Float
    get() = attributes.screenBrightness
    set(value) {
        attributes = attributes.apply {
            screenBrightness = value
        }
    }
