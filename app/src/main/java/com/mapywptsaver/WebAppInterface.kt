package com.mapywptsaver

import android.content.Context
import android.webkit.JavascriptInterface

class WebAppInterface(
    private val mContext: Context
) {

    @JavascriptInterface
    fun getCoordinatesJson(): String {
        if (mContext is MainActivity) {
            val coords = mContext.getCoordinatesList()
            val sb = StringBuilder("[")
            for ((index, coord) in coords.withIndex()) {
                val lonStr = coord.lon?.toString() ?: "null"
                val latStr = coord.lat?.toString() ?: "null"
                val rawIdStr = coord.rawId?.let { "\"$it\"" } ?: "null"
                sb.append("{\"lon\":$lonStr,\"lat\":$latStr,\"isOsm\":${coord.isOsm},\"rawId\":$rawIdStr}")
                if (index < coords.size - 1) {
                    sb.append(",")
                }
            }
            sb.append("]")
            return sb.toString()
        }
        return "[]"
    }

    @JavascriptInterface
    fun getSourceUrl(): String {
        if (mContext is MainActivity) {
            return mContext.getFullUrl() ?: ""
        }
        return ""
    }

    @JavascriptInterface
    fun onGpxDownloaded(filename: String, content: String) {
        if (mContext is MainActivity) {
            mContext.runOnUiThread {
                mContext.saveGpxFile(filename, content)
            }
        }
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        if (mContext is MainActivity) {
            mContext.runOnUiThread {
                android.widget.Toast.makeText(mContext, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
