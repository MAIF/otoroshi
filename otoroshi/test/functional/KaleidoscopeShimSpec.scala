package functional

import otoroshi.utils.KaleidoscopeShim.*

// the `r"..."` pattern interpolator of the expression language and of a few plugins: the captures of a pattern, and
// its extractor built once, since a `case r"..."` is evaluated each time the match tries it
class KaleidoscopeShimSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private def headerWithDefault(expr: String): Option[(String, String)] = expr match {
    case r"req.headers.$field@(.*):$dv@(.*)" => Some((field, dv))
    case _                                  => None
  }

  "The r interpolator" should {

    "bind the named captures of a pattern" in {
      headerWithDefault("req.headers.x-foo:bar") mustBe Some(("x-foo", "bar"))
      headerWithDefault("req.headers.x-foo") mustBe None
      headerWithDefault("req.query.x-foo:bar") mustBe None
    }

    "capture greedily with a bare hole" in {
      ("abc" match { case r"a$rest" => rest; case _ => "" }) mustBe "bc"
    }

    "keep the regex meaning of the literal parts" in {
      // a dot is any char, a backslash escapes, as in kaleidoscope
      ("itemXfoo" match { case r"item.$field@(.*)" => field; case _ => "" }) mustBe "foo"
      ("date(2020-01-01).epoch_ms" match { case r"date\($date@(.*)\).epoch_ms" => date; case _ => "" }) mustBe "2020-01-01"
      ("date[2020-01-01].epoch_ms" match { case r"date\($date@(.*)\).epoch_ms" => date; case _ => "" }) mustBe ""
    }

    "match patterns whose head is not a plain literal" in {
      ("123" match { case r"$nbr@([0-9]+)" => nbr; case _ => "" }) mustBe "123"
      ("12.5" match { case r"$nbr@([0-9\\.,]+)" => nbr; case _ => "" }) mustBe "12.5"
      ("ac" match { case r"ab?$rest@(c)" => rest; case _ => "" }) mustBe "c"
      ("bc" match { case r"a|b$rest@(c)" => rest; case _ => "" }) mustBe "c"
      ("token.a|token.b" match { case r"token.$f@(.*)\|token.$g@(.*)" => s"$f,$g"; case _ => "" }) mustBe "a,b"
    }

    "not match an input shorter than the literal head of the pattern" in {
      ("req" match { case r"req.headers.$field@(.*)" => field; case _ => "none" }) mustBe "none"
      ("" match { case r"req.$field@(.*)" => field; case _ => "none" }) mustBe "none"
    }

    "build the extractor of a pattern once" in {
      def extractor = StringContext("item.", "@(.*)").r
      (extractor eq extractor) mustBe true
    }
  }
}
