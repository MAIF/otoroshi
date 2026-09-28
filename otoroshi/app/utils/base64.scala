package otoroshi.utils

import java.util.Base64

// java.util.Base64 is one to two orders of magnitude faster than commons-codec's Base64 (about 70x
// on a 600 bytes url-safe encode, 10 to 20x on decodes), and it is on the proxy hot path: every info
// token, every Basic auth apikey, every JWT that gets verified goes through here.
//
// encoding is a drop-in replacement: same alphabets, same padding rules, no line chunking.
//
// decoding is not. commons-codec decodes leniently: it accepts the standard and the url-safe
// alphabets alike, skips every character outside them (whitespace, line breaks, junk) and never
// throws. the JDK decoders are strict, and callers rely on that leniency (Basic auth headers, JWT
// segments, PEM bodies, secrets read from the config). so `decode` picks the JDK decoder matching
// the alphabet of its input and falls back to commons-codec whenever the input is not canonical for
// it. a JDK decoder only accepts canonical input, on which commons-codec returns exactly the same
// bytes, so the result is always the one commons-codec would give: the fast path takes well-formed
// input, the fallback reproduces the old behaviour on everything else.
object Base64Codec {

  private val encoder    = Base64.getEncoder
  private val urlEncoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder    = Base64.getDecoder
  private val urlDecoder = Base64.getUrlDecoder

  // what an ASCII character tells about the alphabet of the input: nothing (a letter, a digit, or
  // the padding, which the JDK decoder places itself), standard only, url-safe only, or neither.
  // a table rather than a chain of comparisons: base64 characters are random, so the comparisons
  // mispredict on almost every character
  private final val Shared   = 0
  private final val Standard = 1
  private final val UrlSafe  = 2
  private final val Foreign  = 4

  private val classes: Array[Int] = {
    val table = Array.fill(128)(Foreign)
    ('A' to 'Z').foreach(c => table(c) = Shared)
    ('a' to 'z').foreach(c => table(c) = Shared)
    ('0' to '9').foreach(c => table(c) = Shared)
    table('=') = Shared
    table('+') = Standard
    table('/') = Standard
    table('-') = UrlSafe
    table('_') = UrlSafe
    table
  }

  // same output as commons-codec's encodeBase64String: standard alphabet, padded, on one line
  def encodeToString(bytes: Array[Byte]): String = encoder.encodeToString(bytes)

  // same output as commons-codec's encodeBase64URLSafeString: url-safe alphabet, no padding
  def encodeUrlSafeToString(bytes: Array[Byte]): String = urlEncoder.encodeToString(bytes)

  // same result as commons-codec's decodeBase64 for any input, see above
  def decode(value: String): Array[Byte] = {
    if (value == null) {
      org.apache.commons.codec.binary.Base64.decodeBase64(value)
    } else {
      var seen = Shared
      var i    = 0
      val len  = value.length
      // stops as soon as the input is known to go to commons-codec (both alphabets seen, or a
      // character outside them): a PEM body, for instance, is decided at its first line break
      while (i < len && seen <= UrlSafe) {
        val c = value.charAt(i)
        seen |= (if (c < 128) classes(c) else Foreign)
        i += 1
      }
      seen match {
        case Shared | Standard => decodeOrFallback(decoder, value)
        case UrlSafe           => decodeOrFallback(urlDecoder, value)
        // both alphabets mixed, or characters outside them: only commons-codec knows what it makes of that
        case _                 => org.apache.commons.codec.binary.Base64.decodeBase64(value)
      }
    }
  }

  private def decodeOrFallback(jdkDecoder: Base64.Decoder, value: String): Array[Byte] = {
    try {
      jdkDecoder.decode(value)
    } catch {
      case _: IllegalArgumentException => org.apache.commons.codec.binary.Base64.decodeBase64(value)
    }
  }
}
