package otoroshi.utils.string

import java.util.{Base64 => JavaBase64}

object Utils {
    def replace(input: String, search: String, repl: String): String = {
        val replChar = repl.charAt(0)
        val searched = search.toSeq
        input.map {
            case c if searched.contains(c) => replChar
            case c => c
        }
    }

    def encodeHexString(bytes: Array[Byte]): String = {
        bytes.map("%02x".format(_)).mkString
    }

    def isBase64(str: String): Boolean = {
        try {
            JavaBase64.getDecoder.decode(str)
            true
        } catch {
            case _: IllegalArgumentException => false
        }
    }
}
