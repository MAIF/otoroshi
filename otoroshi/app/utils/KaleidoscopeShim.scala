package otoroshi.utils


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
 * Implementation note: the `r` extension returns an extractor whose regex is built from the raw
 * `StringContext.parts` at runtime, a standard `scala.util.matching.Regex`. Each hole is
 * followed, in the next literal part, by `@(...)`; that group becomes a capturing group and the
 * hole binds to it positionally. No macro is required.
 *
 * A `case r"..."` is evaluated each time the match tries it, so the extractor of a pattern is
 * built once and kept: compiling the regex at each try made an expression language match that
 * succeeds late compile dozens of patterns. The patterns are literals of the code, so the cache
 * holds one extractor per pattern.
 */
object KaleidoscopeShim {

  extension (sc: StringContext) {
    def r: RegexExtractor = extractorOf(sc.parts)
  }

  final class RegexExtractor private[KaleidoscopeShim] (regex: scala.util.matching.Regex, head: String) {
    // the regex has to match the whole input, so an input that does not start with its literal head cannot match
    def unapplySeq(input: String): Option[Seq[String]] = if (input.startsWith(head)) regex.unapplySeq(input) else None
  }

  private val extractors = new java.util.concurrent.ConcurrentHashMap[Seq[String], RegexExtractor]()

  private def extractorOf(parts: Seq[String]): RegexExtractor = {
    val existing = extractors.get(parts)
    if (existing ne null) existing
    else
      extractors.computeIfAbsent(
        parts,
        _ => {
          val source = buildSource(parts)
          new RegexExtractor(source.r, literalHead(parts.head, source))
        }
      )
  }

  // the chars a pattern starts with, which every input it matches starts with too: none when the pattern has an
  // alternation, and not the char a quantifier makes optional
  private def literalHead(firstPart: String, source: String): String = {
    if (source.indexOf('|') >= 0) ""
    else {
      val end = firstPart.indexWhere(c => "\\.[](){}*+?^$|".indexOf(c) >= 0)
      if (end < 0) firstPart
      else if ("*?{".indexOf(firstPart.charAt(end)) >= 0) firstPart.substring(0, math.max(0, end - 1))
      else firstPart.substring(0, end)
    }
  }

  private def buildSource(parts: Seq[String]): String = {
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
    sb.toString
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
