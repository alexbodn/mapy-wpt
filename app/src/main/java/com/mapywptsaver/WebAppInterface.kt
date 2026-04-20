package com.mapywptsaver

import android.content.Context
import android.webkit.JavascriptInterface

class WebAppInterface(
    private val mContext: Context,
    private val coordinates: List<Pair<Double, Double>>,
    private val sourceUrl: String?
) {

    @JavascriptInterface
    fun getCoordinatesJson(): String {
        val sb = StringBuilder("[")
        for ((index, coord) in coordinates.withIndex()) {
            sb.append("{\"lon\":${coord.first},\"lat\":${coord.second}}")
            if (index < coordinates.size - 1) {
                sb.append(",")
            }
        }
        sb.append("]")
        return sb.toString()
    }

    @JavascriptInterface
    fun getSourceUrl(): String {
        return sourceUrl ?: ""
    }

    @JavascriptInterface
    fun onGpxDownloaded(filename: String, content: String) {
        if (mContext is MainActivity) {
            mContext.runOnUiThread {
                mContext.saveGpxFile(filename, content)
            }
        }
    }
}
