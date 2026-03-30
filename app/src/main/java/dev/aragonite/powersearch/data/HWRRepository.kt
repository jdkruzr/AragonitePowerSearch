package dev.aragonite.powersearch.data

// pattern: Imperative Shell

import android.content.Context
import dev.aragonite.hwr.AragoniteHWR
import dev.aragonite.hwr.HWRStroke

open class HWRRepository(private val context: Context) {

    protected var isBound = false

    open suspend fun bind(): Boolean {
        if (isBound) return true
        isBound = AragoniteHWR.bindAndAwait(context)
        return isBound
    }

    open fun unbind() {
        if (isBound) {
            AragoniteHWR.unbind(context)
            isBound = false
        }
    }

    open suspend fun recognizeStrokes(
        strokes: List<HWRStroke>,
        viewWidth: Float,
        viewHeight: Float
    ): String? {
        if (!isBound) return null
        return AragoniteHWR.recognizeStrokes(strokes, viewWidth, viewHeight)
    }
}
