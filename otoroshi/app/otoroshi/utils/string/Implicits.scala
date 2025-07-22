package otoroshi.utils.string

import java.text.Normalizer

object Implicits {
    implicit class EnhancedString(val input: String) extends AnyVal {
        def slug: String = {
            Utils
                .replace(
                    Normalizer
                        .normalize(input, Normalizer.Form.NFD)
                        .replaceAll("\\p{InCombiningDiacriticalMarks}", ""),
                    " -._~!$'()*,;&=@:",
                    "-"
                )
                .replaceAll("-+", "-")
                .toLowerCase
                .trim
        }

        def slug2: String = {
            Utils
                .replace(
                    Normalizer
                        .normalize(input, Normalizer.Form.NFD)
                        .replaceAll("\\p{InCombiningDiacriticalMarks}", ""),
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