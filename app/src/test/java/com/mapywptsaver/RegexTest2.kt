package com.mapywptsaver

import org.junit.Test

class RegexTest2 {
    @Test
    fun testRegex() {
        val sharedText = "https://mapy.com/s/recokokusu"
        val urlRegex = "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\((?:[^\\s()<>]+|\\([^\\s()<>]+\\))*\\))+(?:\\((?:[^\\s()<>]+|\\([^\\s()<>]+\\))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))".toRegex()
        val matchResult = urlRegex.find(sharedText)
        println("MATCH IS: " + matchResult?.value)

        val sharedText2 = "Check out this place: mapy.cz/s/abcde"
        val matchResult2 = urlRegex.find(sharedText2)
        println("MATCH IS: " + matchResult2?.value)

        val sharedText3 = "https://en.mapy.cz"
        val matchResult3 = urlRegex.find(sharedText3)
        println("MATCH IS: " + matchResult3?.value)
    }
}
