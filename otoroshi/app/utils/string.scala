package otoroshi.utils.string

import java.text.Normalizer

import org.apache.commons.lang.StringUtils

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
  }
}
