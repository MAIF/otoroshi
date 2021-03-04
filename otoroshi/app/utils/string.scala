package otoroshi.utils.string

import java.text.Normalizer

import org.apache.commons.lang.StringUtils

object Utils {
  def replace(input: String, search: String, repl: String): String = {
    val replChar = repl.charAt(0)
    val searched = search.toSeq
    input.map {
      case c if searched.contains(c) => replChar
      case c                         => c
    }
  }
}

object Implicits {
  implicit class EnhancedString(val input: String) extends AnyVal {
    def slug: String = {
      StringUtils
        .replaceChars(
          Normalizer
            .normalize(input, Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", ""),
          " -._~!$'()*,;&=@:",
          "-"
        )
        .replaceAll("--", "-")
        .replaceAll("---", "-")
        .replaceAll("----", "-")
        .toLowerCase
        .trim
    }
    def slug2: String = {
      Utils
        .replace(
          Normalizer
            .normalize(input, Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", ""),
          " -._~!$'()*,;&=@:/",
          "-"
        )
        .replaceAll("--", "-")
        .replaceAll("---", "-")
        .replaceAll("----", "-")
        .toLowerCase
        .trim
    }
  }
}
