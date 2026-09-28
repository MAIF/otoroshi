package functional

import otoroshi.utils.Base64Codec

import java.nio.charset.StandardCharsets
import java.util.{Arrays, Base64}
import scala.util.Random

// Base64Codec replaces commons-codec's Base64 on the hot path. its contract is to give exactly what
// commons-codec gives, lenient decoding included: callers feed it Basic auth headers, JWT segments,
// PEM bodies and secrets from the config, and rely on commons-codec accepting both alphabets,
// skipping whatever is not base64 and never throwing
class Base64CodecSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private def commonsDecode(value: String): Array[Byte] = org.apache.commons.codec.binary.Base64.decodeBase64(value)

  private def sameAsCommons(value: String): Unit = {
    val expected = commonsDecode(value)
    val actual   = Base64Codec.decode(value)
    withClue(s"decoding [$value]: ") {
      Arrays.equals(expected, actual) mustBe true
    }
  }

  // drops, inserts or replaces characters, truncates, adds padding: every shape of malformed input
  private def corrupt(value: String, random: Random): String = {
    val noise = Seq(' ', '\n', '\r', '\t', '=', '-', '_', '+', '/', '.', '*', '#', '%', 'é', 'A', '0', '~', ':')
    val sb    = new StringBuilder(value)
    (0 until 1 + random.nextInt(4)).foreach { _ =>
      random.nextInt(5) match {
        case 0 if sb.nonEmpty => sb.deleteCharAt(random.nextInt(sb.length))
        case 1                => sb.insert(random.nextInt(sb.length + 1), noise(random.nextInt(noise.size)))
        case 2 if sb.nonEmpty => sb.setCharAt(random.nextInt(sb.length), noise(random.nextInt(noise.size)))
        case 3 if sb.nonEmpty => sb.setLength(random.nextInt(sb.length))
        case _                => sb.append('=')
      }
    }
    sb.toString
  }

  "encoding" should {

    "give the same output as commons-codec" in {
      val random = new Random(42)
      (0 until 5000).foreach { _ =>
        val bytes = new Array[Byte](random.nextInt(300))
        random.nextBytes(bytes)
        Base64Codec.encodeToString(bytes) mustBe org.apache.commons.codec.binary.Base64.encodeBase64String(bytes)
        Base64Codec.encodeUrlSafeToString(bytes) mustBe org.apache.commons.codec.binary.Base64
          .encodeBase64URLSafeString(bytes)
      }
    }
  }

  "decoding" should {

    "read canonical input in both alphabets, padded or not" in {
      val random = new Random(42)
      (0 until 5000).foreach { _ =>
        val bytes = new Array[Byte](random.nextInt(300))
        random.nextBytes(bytes)
        val standard = Base64.getEncoder.encodeToString(bytes)
        val urlSafe  = Base64.getUrlEncoder.encodeToString(bytes)
        Seq(standard, standard.replace("=", ""), urlSafe, urlSafe.replace("=", "")).foreach { value =>
          Arrays.equals(Base64Codec.decode(value), bytes) mustBe true
          sameAsCommons(value)
        }
      }
    }

    "give the same bytes as commons-codec on malformed input" in {
      val random = new Random(42)
      (0 until 5000).foreach { _ =>
        val bytes = new Array[Byte](random.nextInt(300))
        random.nextBytes(bytes)
        Seq(
          Base64.getEncoder.encodeToString(bytes),
          Base64.getUrlEncoder.withoutPadding().encodeToString(bytes),
          Base64.getMimeEncoder.encodeToString(bytes) // line breaks, as in a PEM body
        ).foreach(value => sameAsCommons(corrupt(value, random)))
      }
    }

    "give the same bytes as commons-codec on edge cases" in {
      Seq(
        "",
        "=",
        "====",
        "A",
        "AA",
        "YQ",
        "YQ=",
        "YQ==",
        "YQ===",
        "YR",
        "YWJjZ",
        "YQ==YQ==",
        "=YQ==",
        "Y=Q=",
        "-_+/",
        "a b c d",
        "YWJj\n",
        "é",
        "Zm9v OmJh cg==",
        "eyJhbGciOiJIUzI1NiJ9"
      ).foreach(sameAsCommons)
      Base64Codec.decode(null) mustBe commonsDecode(null)
    }

    "read Basic auth credentials" in {
      val credentials = "my-client-id:my-client-secret"
      val header      = Base64.getEncoder.encodeToString(credentials.getBytes(StandardCharsets.UTF_8))
      new String(Base64Codec.decode(header), StandardCharsets.UTF_8) mustBe credentials
    }
  }
}
