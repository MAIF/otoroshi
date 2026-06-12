package otoroshi.utils

import scala.util.matching.Regex

/**
 * Minimal drop-in replacement for the Scala 2 `kaleidoscope` 0.5.0 `r"..."` pattern interpolator.
 *
 * The original library is macro-based and cannot be consumed from Scala 3, and the modern Scala 3
 * `kaleidoscope` changed both the capture syntax (`$name(regex)` instead of `$name@(regex)`) and the
 * capture type (`Text` instead of `String`), and drags in the whole soundness dependency tree. To
 * keep every existing `case r"...$name@(regex)..."` call-site unchanged, we re-implement just the
 * subset of the old API that Otoroshi uses: pattern matching with named capture groups bound to
 * `String`s.
 *
 * Old kaleidoscope syntax, preserved here:
 *   - `r"literal$name@(regex)literal"` — `$name` (or `${name}`) immediately followed by `@(regex)`
 *     declares a capturing group whose match is bound to `name` (a `String`).
 *   - regex special characters (notably `\`) do not need to be escaped inside the interpolator;
 *     only `$` must be written `$$`.
 *
 * Implementation note: the `r` extension returns an extractor whose `unapplySeq` reads the raw
 * `StringContext.parts` at runtime to build a standard `scala.util.matching.Regex`. Each hole is
 * followed, in the next literal part, by `@(...)`; that group becomes a capturing group and the
 * hole binds to it positionally. No macro is required.
 */
object KaleidoscopeShim {

  extension (sc: StringContext) {
    def r: RegexExtractor = new RegexExtractor(sc)
  }

  final class RegexExtractor(sc: StringContext) {
    private val regex: Regex                             = KaleidoscopeShim.buildRegex(sc.parts)
    def unapplySeq(input: String): Option[Seq[String]]  = regex.unapplySeq(input)
  }

  private def buildRegex(parts: Seq[String]): Regex = {
    val it = parts.iterator
    val sb = new StringBuilder
    sb.append(it.next()) // first literal part (raw, may contain unescaped backslashes)
    while (it.hasNext) {
      val part = it.next()
      if (part.startsWith("@(")) {
        // `$name@(regex)rest` => the capturing group is the balanced-paren expression after `@`
        val afterAt       = part.substring(1) // drop the leading '@', keep "(regex)rest"
        val (group, rest) = splitGroup(afterAt)
        sb.append(group)
        sb.append(rest)
      } else {
        // bare `$name` (no `@(...)`) behaves like a greedy capture, as in kaleidoscope
        sb.append("(.*)")
        sb.append(part)
      }
    }
    sb.toString.r
  }

  // Splits a string starting with '(' into its balanced-paren group (parens included) and the rest,
  // honouring character classes so a ')' inside `[...]` does not close the group.
  private def splitGroup(s: String): (String, String) = {
    var depth   = 0
    var i       = 0
    var inClass = false
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '\\') { i += 1 } // skip the escaped character
      else if (c == '[') inClass = true
      else if (c == ']') inClass = false
      else if (!inClass && c == '(') depth += 1
      else if (!inClass && c == ')') {
        depth -= 1
        if (depth == 0) return (s.substring(0, i + 1), s.substring(i + 1))
      }
      i += 1
    }
    sys.error(s"kaleidoscope: unbalanced capture group in pattern fragment: $s")
  }
}
