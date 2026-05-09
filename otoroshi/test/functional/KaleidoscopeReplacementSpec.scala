//package functional

// =============================================================================
// DISABLED — this spec validated the kaleidoscope → stdlib `Regex` migration on
// Scala 3.7.4, where it pinned 69 input/output equivalences across all 136
// `r"..."` patterns in the codebase. After the migration, kaleidoscope is no
// longer a production dependency, but this file keeps the kaleidoscope arm of
// each assertion as a baseline.
//
// Re-enabling the body requires Scala 3.7.x because kaleidoscope crashes the
// 3.8.x typer (`AssertionError: class addableString has non-class parent ...`).
// Either revert `scalaLangVersion` to `3.7.4` or wait for a kaleidoscope
// release that compiles under 3.8+.
//
// To re-enable: drop the surrounding `/* */` and run
//   sbt 'testOnly functional.KaleidoscopeReplacementSpec'
// =============================================================================

/*
import kaleidoscope.*
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.matching.Regex

class KaleidoscopeReplacementSpec extends AnyWordSpec with Matchers {

  // -----------------------------------------------------------------------------
  // Generic equivalence helper
  // -----------------------------------------------------------------------------

  /**
   * Compare a kaleidoscope partial-function and a stdlib partial-function on a list
   * of inputs. Asserts they agree (both match with same payload, or both miss).
   */
  private def agree[A](
      kaleido: PartialFunction[String, A],
      stdlib: PartialFunction[String, A],
      inputs: Seq[String]
  ): Unit = {
    inputs.foreach { in =>
      withClue(s"input='$in': ") {
        kaleido.lift(in) mustBe stdlib.lift(in)
      }
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — single capture, prefix syntax
  // -----------------------------------------------------------------------------

  "el.scala — `prefix.$field(.*)` style" should {

    "agree on `item.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"item.$field(.*)" => field.s }
      val P                                  = """item.(.*)""".r
      val s: PartialFunction[String, String] = { case P(field) => field }
      agree(
        k,
        s,
        Seq(
          "item.foo",
          "item.bar.baz",
          "item.",
          "item.with spaces",
          "item.123",
          // non-matches
          "items.foo",
          "params.foo",
          "",
          "foo"
        )
      )
    }

    "agree on `params.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"params.$field(.*)" => field.s }
      val P                                  = """params.(.*)""".r
      val s: PartialFunction[String, String] = { case P(field) => field }
      agree(k, s, Seq("params.foo", "params.bar/baz", "params.", "param.foo", ""))
    }

    "agree on `token.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"token.$field(.*)" => field.s }
      val P                                  = """token.(.*)""".r
      val s: PartialFunction[String, String] = { case P(field) => field }
      agree(k, s, Seq("token.foo", "token.bar.baz", "tokens.foo", "token", ""))
    }

    "agree on `ctx.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"ctx.$field(.*)" => field.s }
      val P                                  = """ctx.(.*)""".r
      val s: PartialFunction[String, String] = { case P(field) => field }
      agree(k, s, Seq("ctx.foo", "ctx.bar/baz", "context.foo", "ctx", ""))
    }

    "agree on `env.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"env.$field(.*)" => field.s }
      val P                                  = """env.(.*)""".r
      val s: PartialFunction[String, String] = { case P(field) => field }
      agree(k, s, Seq("env.HOME", "env.MY_VAR", "envoy.HOME", "env", ""))
    }

    "agree on `config.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"config.$field(.*)" => field.s }
      val P                                  = """config.(.*)""".r
      val s: PartialFunction[String, String] = { case P(field) => field }
      agree(k, s, Seq("config.app.name", "config.x", "configuration.x", ""))
    }

    "agree on `apikeyjwt.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"apikeyjwt.$field(.*)" => field.s }
      val P                                  = """apikeyjwt.(.*)""".r
      val s: PartialFunction[String, String] = { case P(field) => field }
      agree(k, s, Seq("apikeyjwt.sub", "apikeyjwt.$.iss", "apikeyjwt./claims/sub", "apikeyjwt", ""))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — two captures separated by `:`
  // -----------------------------------------------------------------------------

  "el.scala — `prefix.$field(.*):$dv(.*)` style with default value" should {

    "agree on `env.$field(.*):$dv(.*)`" in {
      val k: PartialFunction[String, (String, String)] = { case r"env.$field(.*):$dv(.*)" => (field.s, dv.s) }
      val P                                            = """env.(.*):(.*)""".r
      val s: PartialFunction[String, (String, String)] = { case P(field, dv) => (field, dv) }
      agree(
        k,
        s,
        Seq(
          "env.HOME:/tmp",
          "env.X:default",
          "env.X:",
          "env.X:a:b", // greedy first group
          "env.X",     // no `:`, should not match
          "env.:x"
        )
      )
    }

    "agree on `token.$field(.*):$dv(.*)`" in {
      val k: PartialFunction[String, (String, String)] = { case r"token.$field(.*):$dv(.*)" => (field.s, dv.s) }
      val P                                            = """token.(.*):(.*)""".r
      val s: PartialFunction[String, (String, String)] = { case P(field, dv) => (field, dv) }
      agree(k, s, Seq("token.foo:bar", "token.x:", "token.x", "token.foo:bar:baz"))
    }

    "agree on `ctx.$field(.*):$dv(.*)`" in {
      val k: PartialFunction[String, (String, String)] = { case r"ctx.$field(.*):$dv(.*)" => (field.s, dv.s) }
      val P                                            = """ctx.(.*):(.*)""".r
      val s: PartialFunction[String, (String, String)] = { case P(field, dv) => (field, dv) }
      agree(k, s, Seq("ctx.foo:bar", "ctx.x:", "ctx.x"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — date(...) family with literal parens (most prevalent shape)
  // -----------------------------------------------------------------------------

  "el.scala — `date(...).plus_ms(...)` family" should {

    "agree on `date($date).plus_ms($field)`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"date\($date(.*)\).plus_ms\($field(.*)\)" => (date.s, field.s)
      }
      val P                                            = """date\((.*)\)\.plus_ms\((.*)\)""".r
      val s: PartialFunction[String, (String, String)] = { case P(date, field) => (date, field) }
      agree(
        k,
        s,
        Seq(
          "date(2024-01-01).plus_ms(1000)",
          "date(2024-01-01T12:00:00Z).plus_ms(500)",
          "date().plus_ms()",
          "date(2024).plus_ms(1).format('yyyy')",  // shouldn't match this longer form
          "date(x).minus_ms(1)"
        )
      )
    }

    "agree on `date($date).plus_ms($field).format('$fmt')`" in {
      val k: PartialFunction[String, (String, String, String)] = {
        case r"date\($date(.*)\).plus_ms\($field(.*)\).format\('$format(.*)'\)" => (date.s, field.s, format.s)
      }
      val P                                                    = """date\((.*)\)\.plus_ms\((.*)\)\.format\('(.*)'\)""".r
      val s: PartialFunction[String, (String, String, String)] = { case P(d, f, fmt) => (d, f, fmt) }
      agree(
        k,
        s,
        Seq(
          "date(2024-01-01).plus_ms(1000).format('yyyy-MM-dd')",
          "date(x).plus_ms(1).format('y')",
          "date(x).plus_ms(1)",      // missing format
          "date(x).plus_ms(1).format(yyyy)" // missing quotes
        )
      )
    }

    "agree on `date($date).plus_ms($field).epoch_ms`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"date\($date(.*)\).plus_ms\($field(.*)\).epoch_ms" => (date.s, field.s)
      }
      val P                                            = """date\((.*)\)\.plus_ms\((.*)\)\.epoch_ms""".r
      val s: PartialFunction[String, (String, String)] = { case P(d, f) => (d, f) }
      agree(k, s, Seq("date(x).plus_ms(1).epoch_ms", "date(x).plus_ms(1)", "date(x).plus_ms(1).epoch_sec"))
    }

    "agree on `date($date).plus_ms($field).epoch_sec`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"date\($date(.*)\).plus_ms\($field(.*)\).epoch_sec" => (date.s, field.s)
      }
      val P                                            = """date\((.*)\)\.plus_ms\((.*)\)\.epoch_sec""".r
      val s: PartialFunction[String, (String, String)] = { case P(d, f) => (d, f) }
      agree(k, s, Seq("date(x).plus_ms(1).epoch_sec", "date(x).plus_ms(1).epoch_ms"))
    }

    "agree on `date($date).minus_ms($field)`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"date\($date(.*)\).minus_ms\($field(.*)\)" => (date.s, field.s)
      }
      val P                                            = """date\((.*)\)\.minus_ms\((.*)\)""".r
      val s: PartialFunction[String, (String, String)] = { case P(d, f) => (d, f) }
      agree(k, s, Seq("date(x).minus_ms(1)", "date(x).plus_ms(1)"))
    }

    "agree on `date($date).format('$fmt')`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"date\($date(.*)\).format\('$format(.*)'\)" => (date.s, format.s)
      }
      val P                                            = """date\((.*)\)\.format\('(.*)'\)""".r
      val s: PartialFunction[String, (String, String)] = { case P(d, fmt) => (d, fmt) }
      agree(
        k,
        s,
        Seq(
          "date(2024-01-01).format('yyyy-MM-dd')",
          "date(x).format('')",
          "date(x).format(y)" // missing quotes
        )
      )
    }

    "agree on `date($date).epoch_ms`" in {
      val k: PartialFunction[String, String] = { case r"date\($date(.*)\).epoch_ms" => date.s }
      val P                                  = """date\((.*)\)\.epoch_ms""".r
      val s: PartialFunction[String, String] = { case P(d) => d }
      agree(k, s, Seq("date(2024-01-01).epoch_ms", "date(x).epoch_sec"))
    }

    "agree on `date($date).epoch_sec`" in {
      val k: PartialFunction[String, String] = { case r"date\($date(.*)\).epoch_sec" => date.s }
      val P                                  = """date\((.*)\)\.epoch_sec""".r
      val s: PartialFunction[String, String] = { case P(d) => d }
      agree(k, s, Seq("date(2024-01-01).epoch_sec", "date(x).epoch_ms"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — date_el(...) family (same shape as date(...))
  // -----------------------------------------------------------------------------

  "el.scala — `date_el(...)` family" should {

    "agree on `date_el($date).plus_ms($field)`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"date_el\($date(.*)\).plus_ms\($field(.*)\)" => (date.s, field.s)
      }
      val P                                            = """date_el\((.*)\)\.plus_ms\((.*)\)""".r
      val s: PartialFunction[String, (String, String)] = { case P(d, f) => (d, f) }
      agree(
        k,
        s,
        Seq(
          "date_el(${some.thing}).plus_ms(1)",
          "date_el(${ref}).plus_ms(${other})",
          "date_el(${a}).plus_ms(1).format('y')"
        )
      )
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — now.* family (no captures or single capture)
  // -----------------------------------------------------------------------------

  "el.scala — `now.*` family" should {

    "agree on `now.format('$fmt')`" in {
      val k: PartialFunction[String, String] = { case r"now.format\('$format(.*)'\)" => format.s }
      val P                                  = """now\.format\('(.*)'\)""".r
      val s: PartialFunction[String, String] = { case P(fmt) => fmt }
      agree(k, s, Seq("now.format('yyyy')", "now.format('')", "now.epoch_ms"))
    }

    "agree on `now.epoch_ms`" in {
      val k: PartialFunction[String, Boolean] = { case r"now.epoch_ms" => true }
      val P                                   = """now\.epoch_ms""".r
      val s: PartialFunction[String, Boolean] = { case P() => true }
      agree(k, s, Seq("now.epoch_ms", "now.epoch_sec", "now"))
    }

    "agree on `now.epoch_sec`" in {
      val k: PartialFunction[String, Boolean] = { case r"now.epoch_sec" => true }
      val P                                   = """now\.epoch_sec""".r
      val s: PartialFunction[String, Boolean] = { case P() => true }
      agree(k, s, Seq("now.epoch_sec", "now.epoch_ms"))
    }

    "agree on `now.plus_ms($field)`" in {
      val k: PartialFunction[String, String] = { case r"now.plus_ms\($field(.*)\)" => field.s }
      val P                                  = """now\.plus_ms\((.*)\)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("now.plus_ms(1000)", "now.plus_ms(0)", "now.minus_ms(1)"))
    }

    "agree on `now.plus_ms($field).format('$fmt')`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"now.plus_ms\($field(.*)\).format\('$format(.*)'\)" => (field.s, format.s)
      }
      val P                                            = """now\.plus_ms\((.*)\)\.format\('(.*)'\)""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, fmt) => (f, fmt) }
      agree(k, s, Seq("now.plus_ms(1).format('y')", "now.plus_ms(1)"))
    }

    "agree on `now.minus_ms($field)`" in {
      val k: PartialFunction[String, String] = { case r"now.minus_ms\($field(.*)\)" => field.s }
      val P                                  = """now\.minus_ms\((.*)\)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("now.minus_ms(1)", "now.plus_ms(1)"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — service.* / route.* with bracket+quote keys
  // -----------------------------------------------------------------------------

  "el.scala — bracket-quoted lookup family" should {

    "agree on `service.groups['$field':'$dv']`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"service.groups\['$field(.*)':'$dv(.*)'\]" => (field.s, dv.s)
      }
      val P                                            = """service\.groups\['(.*)':'(.*)'\]""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, d) => (f, d) }
      agree(
        k,
        s,
        Seq(
          "service.groups['admins':'none']",
          "service.groups['x':'']",
          "service.groups['x']", // single quoted key, no default
          "service.groups[admins]"
        )
      )
    }

    "agree on `service.groups['$field']`" in {
      val k: PartialFunction[String, String] = { case r"service.groups\['$field(.*)'\]" => field.s }
      val P                                  = """service\.groups\['(.*)'\]""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("service.groups['admins']", "service.groups['x']", "service.groups['admins':'none']"))
    }

    "agree on `route.domains['$field':'$dv']`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"route.domains\['$field(.*)':'$dv(.*)'\]" => (field.s, dv.s)
      }
      val P                                            = """route\.domains\['(.*)':'(.*)'\]""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, d) => (f, d) }
      agree(k, s, Seq("route.domains['otoroshi.io':'fallback']", "route.domains['x':'']"))
    }

    "agree on `apikey.tags['$field':'$dv']`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"apikey.tags\['$field(.*)':'$dv(.*)'\]" => (field.s, dv.s)
      }
      val P                                            = """apikey\.tags\['(.*)':'(.*)'\]""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, d) => (f, d) }
      agree(k, s, Seq("apikey.tags['env':'dev']", "apikey.tags['x':'']"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — ctx.field.replace('a', 'b') / replaceAll family (5 patterns)
  // -----------------------------------------------------------------------------

  "el.scala — `ctx`/`token` replace family" should {

    "agree on `ctx.$field.replace('$a', '$b')` (with space)" in {
      val k: PartialFunction[String, (String, String, String)] = {
        case r"ctx.$field(.*).replace\('$a(.*)', '$b(.*)'\)" => (field.s, a.s, b.s)
      }
      val P                                                    = """ctx.(.*).replace\('(.*)', '(.*)'\)""".r
      val s: PartialFunction[String, (String, String, String)] = { case P(f, a, b) => (f, a, b) }
      agree(
        k,
        s,
        Seq(
          "ctx.foo.replace('a', 'b')",
          "ctx.x.replace('', '')",
          "ctx.x.replace('a','b')" // no space — should not match this variant
        )
      )
    }

    "agree on `ctx.$field.replace('$a','$b')` (no space)" in {
      val k: PartialFunction[String, (String, String, String)] = {
        case r"ctx.$field(.*).replace\('$a(.*)','$b(.*)'\)" => (field.s, a.s, b.s)
      }
      val P                                                    = """ctx.(.*).replace\('(.*)','(.*)'\)""".r
      val s: PartialFunction[String, (String, String, String)] = { case P(f, a, b) => (f, a, b) }
      agree(
        k,
        s,
        Seq(
          "ctx.foo.replace('a','b')",
          "ctx.foo.replace('a', 'b')" // with space — should not match this variant
        )
      )
    }

    "agree on `ctx.$field.replaceAll('$a','$b')`" in {
      val k: PartialFunction[String, (String, String, String)] = {
        case r"ctx.$field(.*).replaceAll\('$a(.*)','$b(.*)'\)" => (field.s, a.s, b.s)
      }
      val P                                                    = """ctx.(.*).replaceAll\('(.*)','(.*)'\)""".r
      val s: PartialFunction[String, (String, String, String)] = { case P(f, a, b) => (f, a, b) }
      agree(k, s, Seq("ctx.foo.replaceAll('a','b')", "ctx.foo.replace('a','b')"))
    }

    "agree on `token.$field.replace('$a', '$b')` (with space)" in {
      val k: PartialFunction[String, (String, String, String)] = {
        case r"token.$field(.*).replace\('$a(.*)', '$b(.*)'\)" => (field.s, a.s, b.s)
      }
      val P                                                    = """token.(.*).replace\('(.*)', '(.*)'\)""".r
      val s: PartialFunction[String, (String, String, String)] = { case P(f, a, b) => (f, a, b) }
      agree(k, s, Seq("token.foo.replace('a', 'b')", "token.foo.replace('a','b')"))
    }

    "agree on `ctx.$field|ctx.$field2:$dv`" in {
      val k: PartialFunction[String, (String, String, String)] = {
        case r"ctx.$field(.*)\|ctx.$field2(.*):$dv(.*)" => (field.s, field2.s, dv.s)
      }
      val P                                                    = """ctx.(.*)\|ctx.(.*):(.*)""".r
      val s: PartialFunction[String, (String, String, String)] = { case P(f, f2, d) => (f, f2, d) }
      agree(k, s, Seq("ctx.foo|ctx.bar:none", "ctx.foo|ctx.bar"))
    }

    "agree on `ctx.$field|ctx.$field2`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"ctx.$field(.*)\|ctx.$field2(.*)" => (field.s, field2.s)
      }
      val P                                            = """ctx.(.*)\|ctx.(.*)""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, f2) => (f, f2) }
      agree(k, s, Seq("ctx.foo|ctx.bar", "ctx.foo|ctx.bar:default"))
    }

    "agree on `token.$field|token.$field2`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"token.$field(.*)\|token.$field2(.*)" => (field.s, field2.s)
      }
      val P                                            = """token.(.*)\|token.(.*)""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, f2) => (f, f2) }
      agree(k, s, Seq("token.foo|token.bar", "ctx.foo|ctx.bar"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — vault://, file://, file: prefixes
  // -----------------------------------------------------------------------------

  "el.scala — protocol-prefixed patterns" should {

    "agree on `vault://$path(.*)`" in {
      val k: PartialFunction[String, String] = { case r"vault://$path(.*)" => path.s }
      val P                                  = """vault://(.*)""".r
      val s: PartialFunction[String, String] = { case P(p) => p }
      agree(
        k,
        s,
        Seq(
          "vault://foo/bar",
          "vault://x",
          "vault://",
          "vault:foo",
          "file://foo"
        )
      )
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — user.metadata, user.profile, consumer.metadata
  // -----------------------------------------------------------------------------

  "el.scala — user/consumer metadata patterns" should {

    "agree on `user.metadata.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"user.metadata.$field(.*)" => field.s }
      val P                                  = """user\.metadata\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("user.metadata.email", "user.metadata.x.y", "user.profile.x"))
    }

    "agree on `user.metadata.$field(.*):$dv(.*)`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"user.metadata.$field(.*):$dv(.*)" => (field.s, dv.s)
      }
      val P                                            = """user\.metadata\.(.*):(.*)""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, d) => (f, d) }
      agree(k, s, Seq("user.metadata.email:none", "user.metadata.x"))
    }

    "agree on `user.profile.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"user.profile.$field(.*)" => field.s }
      val P                                  = """user\.profile\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("user.profile.email", "user.metadata.email"))
    }

    "agree on `consumer.metadata.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"consumer.metadata.$field(.*)" => field.s }
      val P                                  = """consumer\.metadata\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("consumer.metadata.role", "user.metadata.role"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — bare-token literals (`nbf`, `iat`, `exp`)
  // -----------------------------------------------------------------------------

  "el.scala — JWT timestamp literals" should {

    "agree on bare `nbf`/`iat`/`exp`" in {
      val k: PartialFunction[String, String] = {
        case r"nbf" => "nbf"
        case r"iat" => "iat"
        case r"exp" => "exp"
      }
      val Pn                                 = """nbf""".r
      val Pi                                 = """iat""".r
      val Pe                                 = """exp""".r
      val s: PartialFunction[String, String] = {
        case Pn() => "nbf"
        case Pi() => "iat"
        case Pe() => "exp"
      }
      agree(k, s, Seq("nbf", "iat", "exp", "exp1", "nb"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — numeric extractor patterns (used in JSON typing)
  // -----------------------------------------------------------------------------

  "el.scala — numeric coercion patterns" should {

    "agree on decimal `[0-9\\.,]+`" in {
      val k: PartialFunction[String, String] = { case r"$nbr([0-9\\.,]+)" => nbr.s }
      val P                                  = """([0-9\.,]+)""".r
      val s: PartialFunction[String, String] = { case P(n) => n }
      agree(k, s, Seq("123", "1.5", "1,000.5", "abc", "1abc", ""))
    }

    "agree on integer `[0-9]+`" in {
      val k: PartialFunction[String, String] = { case r"$nbr([0-9]+)" => nbr.s }
      val P                                  = """([0-9]+)""".r
      val s: PartialFunction[String, String] = { case P(n) => n }
      agree(k, s, Seq("123", "0", "-1", "abc", "12abc", ""))
    }
  }

  // -----------------------------------------------------------------------------
  // workflow.scala — input-key patterns
  // -----------------------------------------------------------------------------

  "workflow.scala — WorkFlowEl patterns" should {

    "agree on `input.$path(.*)`" in {
      val k: PartialFunction[String, String] = { case r"input.$path(.*)" => path.s }
      val P                                  = """input.(.*)""".r
      val s: PartialFunction[String, String] = { case P(p) => p }
      agree(k, s, Seq("input.foo", "input.$.x.y", "inputs.foo"))
    }

    "agree on `cache.$field[$path]`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"cache.$field(.*)\[$path(.*)\]" => (field.s, path.s)
      }
      val P                                            = """cache\.(.*)\[(.*)\]""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, p) => (f, p) }
      agree(
        k,
        s,
        Seq(
          "cache.foo[$.bar]",
          "cache.x[y]",
          "cache.x", // no brackets
          "cache.x[y]extra"
        )
      )
    }

    "agree on `responses.$field[$path]`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"responses.$field(.*)\[$path(.*)\]" => (field.s, path.s)
      }
      val P                                            = """responses\.(.*)\[(.*)\]""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, p) => (f, p) }
      agree(k, s, Seq("responses.r1[$.x]", "responses.r1[y]", "cache.foo[x]"))
    }

    "agree on `file://$path(.*)`" in {
      val k: PartialFunction[String, String] = { case r"file://$path(.*)" => path.s }
      val P                                  = """file://(.*)""".r
      val s: PartialFunction[String, String] = { case P(p) => p }
      agree(k, s, Seq("file:///etc/hosts", "file://./relative", "file:plain", "vault://foo"))
    }

    "agree on `file:$path(.*)` (single colon prefix)" in {
      val k: PartialFunction[String, String] = { case r"file:$path(.*)" => path.s }
      val P                                  = """file:(.*)""".r
      val s: PartialFunction[String, String] = { case P(p) => p }
      agree(k, s, Seq("file:./hosts", "file:relative", "file://double"))
    }
  }

  // -----------------------------------------------------------------------------
  // eureka.scala — URL routing patterns (matched against the path component)
  // -----------------------------------------------------------------------------

  "eureka.scala — URL route patterns" should {

    "agree on `/eureka/apps/$appId/$instanceId/status`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"/eureka/apps/$appId(.*)/$instanceId(.*)/status" => (appId.s, instanceId.s)
      }
      val P                                            = """/eureka/apps/(.*)/(.*)/status""".r
      val s: PartialFunction[String, (String, String)] = { case P(a, i) => (a, i) }
      agree(
        k,
        s,
        Seq(
          "/eureka/apps/myapp/inst1/status",
          "/eureka/apps/a/b/status",
          "/eureka/apps/a/b/metadata",
          "/eureka/apps/a"
        )
      )
    }

    "agree on `/eureka/apps/$appId/$instanceId/metadata`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"/eureka/apps/$appId(.*)/$instanceId(.*)/metadata" => (appId.s, instanceId.s)
      }
      val P                                            = """/eureka/apps/(.*)/(.*)/metadata""".r
      val s: PartialFunction[String, (String, String)] = { case P(a, i) => (a, i) }
      agree(k, s, Seq("/eureka/apps/a/b/metadata", "/eureka/apps/a/b/status"))
    }

    "agree on `/eureka/apps/$appId(.*)`" in {
      val k: PartialFunction[String, String] = { case r"/eureka/apps/$appId(.*)" => appId.s }
      val P                                  = """/eureka/apps/(.*)""".r
      val s: PartialFunction[String, String] = { case P(a) => a }
      agree(
        k,
        s,
        Seq(
          "/eureka/apps/myapp",
          "/eureka/apps/a/b",     // captures `a/b` (greedy)
          "/eureka/instances/x"
        )
      )
    }

    "agree on `/eureka/instances/$instanceId(.*)`" in {
      val k: PartialFunction[String, String] = { case r"/eureka/instances/$instanceId(.*)" => instanceId.s }
      val P                                  = """/eureka/instances/(.*)""".r
      val s: PartialFunction[String, String] = { case P(i) => i }
      agree(k, s, Seq("/eureka/instances/inst1", "/eureka/apps/a"))
    }

    "agree on `/eureka/vips/$vipAddress(.*)`" in {
      val k: PartialFunction[String, String] = { case r"/eureka/vips/$vipAddress(.*)" => vipAddress.s }
      val P                                  = """/eureka/vips/(.*)""".r
      val s: PartialFunction[String, String] = { case P(v) => v }
      agree(k, s, Seq("/eureka/vips/v1", "/eureka/svips/v1"))
    }

    "agree on `/eureka/svips/$svipAddress(.*)`" in {
      val k: PartialFunction[String, String] = { case r"/eureka/svips/$svipAddress(.*)" => svipAddress.s }
      val P                                  = """/eureka/svips/(.*)""".r
      val s: PartialFunction[String, String] = { case P(v) => v }
      agree(k, s, Seq("/eureka/svips/v1", "/eureka/vips/v1"))
    }
  }

  // -----------------------------------------------------------------------------
  // discovery.scala (next + legacy) — registration URL patterns
  // -----------------------------------------------------------------------------

  "discovery URL route patterns" should {

    "agree on `/discovery/$registrationId(.*)/_unregister`" in {
      val k: PartialFunction[String, String] = {
        case r"/discovery/$registrationId(.*)/_unregister" => registrationId.s
      }
      val P                                  = """/discovery/(.*)/_unregister""".r
      val s: PartialFunction[String, String] = { case P(r) => r }
      agree(
        k,
        s,
        Seq(
          "/discovery/abc-123/_unregister",
          "/discovery/x/_heartbeat",
          "/discovery/x/y/_unregister"
        )
      )
    }

    "agree on `/discovery/$registrationId(.*)/_heartbeat`" in {
      val k: PartialFunction[String, String] = {
        case r"/discovery/$registrationId(.*)/_heartbeat" => registrationId.s
      }
      val P                                  = """/discovery/(.*)/_heartbeat""".r
      val s: PartialFunction[String, String] = { case P(r) => r }
      agree(k, s, Seq("/discovery/abc/_heartbeat", "/discovery/abc/_unregister"))
    }
  }

  // -----------------------------------------------------------------------------
  // response.scala — pathparams pattern (the only kaleidoscope use here)
  // -----------------------------------------------------------------------------

  "response.scala — pathparams patterns" should {

    "agree on `req.pathparams.$field(.*):$defaultValue(.*)`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"req.pathparams.$field(.*):$defaultValue(.*)" => (field.s, defaultValue.s)
      }
      val P                                            = """req\.pathparams\.(.*):(.*)""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, d) => (f, d) }
      agree(k, s, Seq("req.pathparams.id:0", "req.pathparams.x:", "req.pathparams.x"))
    }

    "agree on `req.pathparams.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"req.pathparams.$field(.*)" => field.s }
      val P                                  = """req\.pathparams\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("req.pathparams.id", "req.pathparams.x.y", "req.headers.x"))
    }
  }

  // -----------------------------------------------------------------------------
  // body.scala — bodylogger request id URL
  // -----------------------------------------------------------------------------

  "body.scala — bodylogger request URL" should {

    "agree on `/.well-known/otoroshi/plugins/bodylogger/requests/$id(.*).json`" in {
      val k: PartialFunction[String, String] = {
        case r"/.well-known/otoroshi/plugins/bodylogger/requests/$id(.*).json" => id.s
      }
      val P                                  = """/\.well-known/otoroshi/plugins/bodylogger/requests/(.*)\.json""".r
      val s: PartialFunction[String, String] = { case P(i) => i }
      agree(
        k,
        s,
        Seq(
          "/.well-known/otoroshi/plugins/bodylogger/requests/abc.json",
          "/.well-known/otoroshi/plugins/bodylogger/requests/abc.txt",
          "/.well-known/otoroshi/plugins/bodylogger/requests/abc"
        )
      )
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — req.* family (headers, query, cookies, pathparams)
  // -----------------------------------------------------------------------------

  "el.scala — req.* lookup family" should {

    "agree on `req.headers.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"req.headers.$field(.*)" => field.s }
      val P                                  = """req\.headers\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("req.headers.Host", "req.query.x"))
    }

    "agree on `req.headers.$field(.*):$dv(.*)`" in {
      val k: PartialFunction[String, (String, String)] = {
        case r"req.headers.$field(.*):$defaultValue(.*)" => (field.s, defaultValue.s)
      }
      val P                                            = """req\.headers\.(.*):(.*)""".r
      val s: PartialFunction[String, (String, String)] = { case P(f, d) => (f, d) }
      agree(k, s, Seq("req.headers.Host:none", "req.headers.X"))
    }

    "agree on `req.query.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"req.query.$field(.*)" => field.s }
      val P                                  = """req\.query\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("req.query.x", "req.headers.x"))
    }

    "agree on `req.cookies.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"req.cookies.$field(.*)" => field.s }
      val P                                  = """req\.cookies\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("req.cookies.session", "req.headers.x"))
    }

    "agree on `req.pathparams.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"req.pathparams.$field(.*)" => field.s }
      val P                                  = """req\.pathparams\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("req.pathparams.id", "req.headers.x"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — JWT-related patterns
  // -----------------------------------------------------------------------------

  "el.scala — JWT lookup patterns" should {

    "agree on `in_jwt.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"in_jwt.$field(.*)" => field.s }
      val P                                  = """in_jwt\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("in_jwt.sub", "out_jwt.sub"))
    }

    "agree on `out_jwt.$field(.*)`" in {
      val k: PartialFunction[String, String] = { case r"out_jwt.$field(.*)" => field.s }
      val P                                  = """out_jwt\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(f) => f }
      agree(k, s, Seq("out_jwt.sub", "in_jwt.sub"))
    }
  }

  // -----------------------------------------------------------------------------
  // el.scala — global_config patterns
  // -----------------------------------------------------------------------------

  "el.scala — global_config patterns" should {

    "agree on `global_config.metadata.$name(.*)`" in {
      val k: PartialFunction[String, String] = { case r"global_config.metadata.$name(.*)" => name.s }
      val P                                  = """global_config\.metadata\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(n) => n }
      agree(k, s, Seq("global_config.metadata.tenant", "global_config.env.x"))
    }

    "agree on `global_config.env.$path(.*)`" in {
      val k: PartialFunction[String, String] = { case r"global_config.env.$path(.*)" => path.s }
      val P                                  = """global_config\.env\.(.*)""".r
      val s: PartialFunction[String, String] = { case P(p) => p }
      agree(k, s, Seq("global_config.env.HOME", "global_config.metadata.x"))
    }
  }
}
*/
