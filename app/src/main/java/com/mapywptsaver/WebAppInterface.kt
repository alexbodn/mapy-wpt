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
            val instructions = mContext.getScrapedInstructions()
            val sb = StringBuilder("[")

            // We map instructions to the sequence of *valid coordinate waypoints*, not raw params.
            var wptCount = 0
            for ((index, coord) in coords.withIndex()) {
                val lonStr = coord.lon?.toString() ?: "null"
                val latStr = coord.lat?.toString() ?: "null"
                val rawIdStr = coord.rawId?.let { "\"$it\"" } ?: "null"

                var instructionText = "null"
                // If it's a valid coordinate point
                if (coord.lon != null && coord.lat != null) {
                    // Assign the instruction mapped to the PREVIOUS valid coordinate's segment
                    if (wptCount < instructions.size) {
                        instructionText = "\"" + instructions[wptCount].replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
                    }
                    wptCount++
                }

                sb.append("{\"lon\":$lonStr,\"lat\":$latStr,\"isOsm\":${coord.isOsm},\"rawId\":$rawIdStr,\"instruction\":$instructionText}")
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

    @JavascriptInterface
    fun saveInstructions(instructionsJson: String) {
        if (mContext is MainActivity) {
            mContext.saveScrapedInstructions(instructionsJson)
        }
    }
}
