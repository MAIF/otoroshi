package functional

import otoroshi.utils.ReplaceAllWith

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

// the replacer behind the expression language: the ${...} expressions of a value are replaced by what the callback
// returns for them. a value is scanned once: what a callback returns is inserted as is and never scanned again, since
// it can hold data that does not come from the configuration, like request headers
class ExpressionReplacerSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  // the pattern of GlobalExpressionLanguage
  private val replacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

  // both run under a timeout: a replacer that scans its own output may never return
  private def sync(value: String)(callback: String => String): String =
    Await.result(Future(replacer.replaceOn(value)(callback)), 10.seconds)

  private def async(value: String)(callback: String => String): String =
    Await.result(replacer.replaceOnAsync(value)(e => Future(callback(e))), 10.seconds)

  Seq[(String, String => (String => String) => String)](
    ("ReplaceAllWith.replaceOn", sync),
    ("ReplaceAllWith.replaceOnAsync", async)
  ).foreach { case (name, replace) =>
    name should {

      "replace every expression, in order, and keep the text around them" in {
        replace("a ${one} b ${two} c")(_.toUpperCase) mustBe "a ONE b TWO c"
      }

      "leave a value without expression untouched" in {
        replace("plain value")(_ => "x") mustBe "plain value"
      }

      "replace adjacent expressions and expressions at both ends" in {
        replace("${a}${b}")(identity) mustBe "ab"
        replace("${a} and ${b}")(identity) mustBe "a and b"
      }

      "insert a replacement as is, even when it holds an expression" in {
        val calls = new AtomicInteger(0)
        replace("x-${first}-y") { _ =>
          calls.incrementAndGet()
          "${second}"
        } mustBe "x-${second}-y"
        calls.get() mustBe 1
      }

      "not scan the text that a replacement and the rest of the value form together" in {
        replace("${a}{b}")(_ => "$") mustBe "${b}"
      }

      "come to an end when a replacement is the very expression it replaces" in {
        val done = Future(replace("${self}")(_ => "${self}"))
        Await.result(done, 5.seconds) mustBe "${self}"
      }
    }
  }
}
