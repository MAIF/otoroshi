package functional

import com.typesafe.config.ConfigFactory
import otoroshi.env.Env
import otoroshi.utils.{FastJsonPath, JsonPathUtils, JsonPathValidator}
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*

import scala.util.{Failure, Try}

/**
 * Differential test and comparison report for the opt in fast json path reader
 * ([[otoroshi.utils.JsonPathDocument]] / [[otoroshi.utils.FastJsonPath]]) against the regular
 * reader ([[otoroshi.utils.JsonPathValidator.validate(ctx:play\.api\.libs\.json\.JsValue)*]]),
 * which the rest of Otoroshi uses and whose behaviour must not move.
 *
 * Runs on its own so that the report can be read without wading through another suite:
 *
 * {{{
 * sbt 'testOnly JsonPathTests'
 * }}}
 *
 * An env has to be booted: `validate` takes a `using Env`, and `JsonPathUtils` reads
 * `jsonPathNullReadIsJsNull` off the global env holder.
 */
class JsonPathFastReaderSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(ConfigFactory.parseString("{}").resolve()).withFallback(configurationSpec).withFallback(configuration)

  private lazy implicit val env: Env = otoroshiComponents.env

  // ------------------------------------------------------------------------------------------------
  // payloads, shaped like a real plugin context. the route and the attrs are what actually weigh in
  // one, so the number of plugin slots is what makes a payload small or large.
  // ------------------------------------------------------------------------------------------------

  private def pluginSlot(idx: Int): JsObject = Json.obj(
    "plugin"          -> s"cp:otoroshi.next.plugins.Plugin$idx",
    "enabled"         -> true,
    "debug"           -> false,
    "include"         -> Json.arr(),
    "exclude"         -> Json.arr(),
    "bound_listeners" -> Json.arr(),
    "config"          -> Json.obj(
      "name"    -> s"plugin-$idx",
      "ttl"     -> (idx * 1000),
      "enabled" -> (idx % 2 == 0),
      "values"  -> Json.arr(s"a$idx", s"b$idx", s"c$idx")
    )
  )

  private def route(plugins: Int): JsObject = Json.obj(
    "id"       -> "route_1234",
    "name"     -> "my-route",
    "enabled"  -> true,
    "tags"     -> Json.arr("prod", "public", "team-a"),
    "metadata" -> Json.obj("tier" -> "gold", "owner" -> "team-a", "cost.center" -> "42"),
    "frontend" -> Json.obj(
      "domains"    -> Json.arr("api.oto.tools/v1", "api2.oto.tools/v1"),
      "strip_path" -> true,
      "exact"      -> false,
      "methods"    -> Json.arr("GET", "POST")
    ),
    "backend"  -> Json.obj(
      "targets" -> Json.arr(
        Json.obj("hostname" -> "backend1.oto.tools", "port" -> 443, "tls" -> true, "weight" -> 1),
        Json.obj("hostname" -> "backend2.oto.tools", "port" -> 443, "tls" -> true, "weight" -> 2)
      ),
      "root"    -> "/",
      "rewrite" -> false
    ),
    "plugins"  -> JsArray((1 to plugins).map(pluginSlot))
  )

  private def payloadWith(plugins: Int): JsObject = Json.obj(
    "snowflake"     -> "1516772930422308903",
    "apikey"        -> Json.obj(
      "clientId"   -> "vrmElDerycXrofar",
      "clientName" -> "default-apikey",
      "metadata"   -> Json.obj("tier" -> "gold", "count" -> 3, "ratio" -> 1.5, "beta" -> true, "empty" -> ""),
      "tags"       -> Json.arr("alpha", "beta", "gamma")
    ),
    "user"          -> JsNull,
    "request"       -> Json.obj(
      "id"                -> 1,
      "method"            -> "GET",
      "headers"           -> Json.obj(
        "Host"       -> "api.oto.tools",
        "cond"       -> "on",
        "x-tier"     -> "gold",
        "user_agent" -> "curl/8.4.0",
        "accept"     -> "*/*"
      ),
      "cookies"           -> Json.arr(),
      "tls"               -> false,
      "uri"               -> "/v1/things?page=2",
      "query"             -> Json.obj("page" -> "2"),
      "path"              -> "/v1/things",
      "version"           -> "HTTP/1.1",
      "has_body"          -> false,
      "remote"            -> "10.0.0.17",
      "client_cert_chain" -> JsNull,
      "path_params"       -> Json.obj()
    ),
    "backend"       -> Json.obj("hostname" -> "backend1.oto.tools", "port" -> 443),
    "config"        -> Json.obj("plugin" -> "cp:otoroshi.next.plugins.ApikeyCalls"),
    "global_config" -> Json.obj("maintenance_mode" -> false, "lines" -> Json.arr("prod")),
    "attrs"         -> Json.obj(
      "otoroshi.next.core.Route"      -> route(plugins),
      "otoroshi.core.SnowFlake"       -> "1516772930422308903",
      "otoroshi.core.RequestStart"    -> 1650461821545L,
      "otoroshi.core.RemainingQuotas" -> Json.obj(
        "authorizedCallsPerWindow" -> 10000000,
        "remainingCallsPerWindow"  -> 9999999
      ),
      "otoroshi.core.ElCtx"           -> Json.obj("requestId" -> "1516772930422308903")
    )
  )

  private val payloads: Seq[(String, JsObject)] = Seq(
    "small"  -> payloadWith(2),
    "medium" -> payloadWith(20),
    "large"  -> payloadWith(150)
  )

  private def sizeOf(payload: JsValue): Int = Json.stringify(payload).length

  // ------------------------------------------------------------------------------------------------
  // the corpus: every object path of a payload, crossed with the whole expected value language
  // ------------------------------------------------------------------------------------------------

  private val plainSegment = "^[A-Za-z_][A-Za-z0-9_-]*$".r

  private def harvestPaths(json: JsValue, prefix: String, depth: Int): Seq[String] = {
    if (depth <= 0) Seq.empty
    else
      json match {
        case obj: JsObject =>
          obj.fields.toSeq.flatMap { case (key, value) =>
            val segment = if (plainSegment.matches(key)) s"$prefix.$key" else s"$prefix['$key']"
            Seq(segment) ++ harvestPaths(value, segment, depth - 1)
          }
        case _             => Seq.empty
      }
  }

  // paths that cannot be walked and have to be handed over to jayway
  private val jaywayPaths = Seq(
    "$",
    "$..tier",
    "$..hostname",
    "$.apikey.tags[0]",
    "$.apikey.tags[0:2]",
    "$.apikey.tags[*]",
    "$.request.headers.*",
    "$.attrs['otoroshi.next.core.Route'].plugins[0].plugin",
    "$.attrs['otoroshi.next.core.Route'].plugins[?(@.config.enabled == true)].plugin",
    "$.attrs['otoroshi.next.core.Route'].backend.targets[*].hostname",
    "$.apikey.tags.length()",
    "[?(@.apikey)]"
  )

  // paths that resolve to nothing, at various depths, plus a walk through a non object
  private val missingPaths = Seq(
    "$.nope",
    "$.apikey.nope",
    "$.apikey.metadata.nope",
    "$.apikey.metadata.tier.nope",
    "$.user.name",
    "$.request.method.nope",
    "$.attrs['otoroshi.next.core.Route'].nope.deeper",
    "$.attrs['not.an.attr'].nope"
  )

  // the whole expected value language of JsonPathValidator, sane and hostile values alike
  private val expectedValues: Seq[JsValue] = Seq(
    JsString("gold"),
    JsString("silver"),
    JsString("on"),
    JsString("GET"),
    JsString("3"),
    JsString("1.5"),
    JsString("true"),
    JsString("false"),
    JsString(""),
    JsString("IsDefined()"),
    JsString("NotDefined()"),
    JsString("Not(gold)"),
    JsString("Contains(old)"),
    JsString("ContainsNot(old)"),
    JsString("Regex(go.*)"),
    JsString("RegexNot(go.*)"),
    JsString("Wildcard(go*)"),
    JsString("WildcardNot(go*)"),
    JsString("ContainedIn(gold,silver)"),
    JsString("NotContainedIn(gold,silver)"),
    JsString("StartsWith(alpha)"),
    JsString("DontStartsWith(alpha)"),
    JsString("Size(3)"),
    JsString("SizeNot(3)"),
    JsString("SizeLt(4)"),
    JsString("SizeGt(1)"),
    JsString("SizeLte(3)"),
    JsString("SizeGte(3)"),
    JsString("Contains(alpha)"),
    JsString("ContainsNot(alpha)"),
    JsString("Contains(Regex(al.*))"),
    JsString("Contains(Wildcard(al*))"),
    JsString("ContainsNot(Regex(al.*))"),
    JsString("ContainsNot(Wildcard(al*))"),
    JsString("JsonContains(alpha)"),
    JsString("JsonContainsNot(alpha)"),
    JsString("JsonContains(Regex(al.*))"),
    // hostile: a malformed size makes the validator throw. both roads have to throw the same way.
    JsString("Size(abc)"),
    JsString("SizeLt()"),
    // non string expected values
    JsBoolean(true),
    JsBoolean(false),
    JsNumber(3),
    JsNull,
    Json.arr("alpha", "beta", "gamma"),
    Json.obj("tier" -> "gold")
  )

  // Try, so that a validator blowing up on a malformed expected value is compared too instead of
  // failing the run. Two roads throwing the same exception are still in agreement.
  private def outcome(f: => Boolean): Either[String, Boolean] =
    Try(f).toEither.left.map(e => s"${e.getClass.getName}")

  // ------------------------------------------------------------------------------------------------
  // measurement
  // ------------------------------------------------------------------------------------------------

  private var blackhole: Long = 0L

  private def timeOf(iterations: Int)(f: => Boolean): Long = {
    val start = System.nanoTime()
    var idx   = 0
    var acc   = 0L
    while (idx < iterations) {
      if (f) acc += 1
      idx += 1
    }
    val elapsed = System.nanoTime() - start
    blackhole += acc
    elapsed
  }

  private val targetNanosPerRepetition = 40000000L // 40ms
  private val repetitions              = 9

  // find how many iterations are needed for one repetition to last long enough to be measurable
  private def calibrate(f: => Boolean): Int = {
    var iterations = 1
    while (iterations < 2000000 && timeOf(iterations)(f) < targetNanosPerRepetition) {
      iterations = iterations * 2
    }
    iterations
  }

  private case class Stats(samples: Seq[Double]) {
    private val sorted        = samples.sorted
    val mean: Double          = samples.sum / samples.size
    val median: Double        = sorted(sorted.size / 2)
    val min: Double           = sorted.head
    val p95: Double           = sorted(math.min(sorted.size - 1, (sorted.size * 0.95).toInt))
    val stddev: Double        = {
      val m = mean
      math.sqrt(samples.map(s => (s - m) * (s - m)).sum / samples.size)
    }
    def relativeStddev: Double = if (mean == 0d) 0d else stddev / mean * 100d
  }

  // nanoseconds per round, over `repetitions` repetitions of `iterations` rounds each
  private def measure(f: => Boolean): Stats = {
    timeOf(500)(f) // warmup, let the jit settle
    val iterations = calibrate(f)
    timeOf(iterations)(f) // one more, discarded
    Stats((1 to repetitions).map(_ => timeOf(iterations)(f).toDouble / iterations.toDouble))
  }

  private case class Scenario(name: String, predicates: Seq[JsonPathValidator])

  private val scenarios = Seq(
    Scenario(
      "simple x1",
      Seq(JsonPathValidator("$.apikey.metadata.tier", JsString("gold")))
    ),
    Scenario(
      "simple x3",
      Seq(
        JsonPathValidator("$.apikey.metadata.tier", JsString("gold")),
        JsonPathValidator("$.request.headers.cond", JsString("on")),
        JsonPathValidator("$.request.method", JsString("Not(POST)"))
      )
    ),
    Scenario(
      "simple x6",
      Seq(
        JsonPathValidator("$.apikey.metadata.tier", JsString("gold")),
        JsonPathValidator("$.request.headers.cond", JsString("on")),
        JsonPathValidator("$.request.method", JsString("Not(POST)")),
        JsonPathValidator("$.apikey.tags", JsString("Contains(alpha)")),
        JsonPathValidator("$.attrs['otoroshi.next.core.Route'].metadata.tier", JsString("gold")),
        JsonPathValidator("$.request.remote", JsString("Regex(10\\..*)"))
      )
    ),
    Scenario(
      "simple x12",
      (1 to 2).flatMap(_ =>
        Seq(
          JsonPathValidator("$.apikey.metadata.tier", JsString("gold")),
          JsonPathValidator("$.request.headers.cond", JsString("on")),
          JsonPathValidator("$.request.method", JsString("Not(POST)")),
          JsonPathValidator("$.apikey.tags", JsString("Contains(alpha)")),
          JsonPathValidator("$.attrs['otoroshi.next.core.Route'].metadata.tier", JsString("gold")),
          JsonPathValidator("$.request.remote", JsString("Regex(10\\..*)"))
        )
      )
    ),
    Scenario(
      "jayway x1",
      Seq(JsonPathValidator("$.apikey.tags[0]", JsString("alpha")))
    ),
    Scenario(
      "jayway x3",
      Seq(
        JsonPathValidator("$.apikey.tags[0]", JsString("alpha")),
        JsonPathValidator("$..tier", JsString("SizeGte(2)")),
        JsonPathValidator("$.attrs['otoroshi.next.core.Route'].backend.targets[*].hostname", JsString("Size(2)"))
      )
    ),
    Scenario(
      "mixed 5+1",
      Seq(
        JsonPathValidator("$.apikey.metadata.tier", JsString("gold")),
        JsonPathValidator("$.request.headers.cond", JsString("on")),
        JsonPathValidator("$.request.method", JsString("Not(POST)")),
        JsonPathValidator("$.apikey.tags", JsString("Contains(alpha)")),
        JsonPathValidator("$.attrs['otoroshi.next.core.Route'].metadata.tier", JsString("gold")),
        JsonPathValidator("$.apikey.tags[0]", JsString("alpha"))
      )
    )
  )

  private case class Comparison(payload: String, bytes: Int, scenario: String, legacy: Stats, fast: Stats) {
    val speedup: Double = legacy.mean / fast.mean
  }

  private def report(lines: String): Unit = {
    // println rather than the logger, this is a report and it has to be readable as is
    println(lines)
  }

  private def pad(value: String, width: Int): String =
    if (value.length >= width) value else value + (" " * (width - value.length))

  private def padLeft(value: String, width: Int): String =
    if (value.length >= width) value else (" " * (width - value.length)) + value

  private def micros(nanos: Double): String = f"${nanos / 1000d}%.2f"

  // ------------------------------------------------------------------------------------------------

  "the fast json path reader" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().andThen { case Failure(e) => e.printStackTrace() }.futureValue
    }

    "answer exactly like the regular reader over a generated corpus" in {

      report("")
      report("=" * 110)
      report("  JSON PATH READER - DIFFERENTIAL CORPUS")
      report("=" * 110)

      var totalCases      = 0
      var totalFastLane   = 0
      var totalJaywayLane = 0
      val allMismatches   = scala.collection.mutable.ArrayBuffer.empty[String]

      payloads.foreach { case (label, payload) =>
        val harvested = harvestPaths(payload, "$", 5).distinct
        val paths     = (harvested ++ jaywayPaths ++ missingPaths).distinct
        val document  = JsonPathUtils.document(payload)

        val fastLane   = paths.count(p => FastJsonPath.segmentsOf(p).isDefined)
        val jaywayLane = paths.size - fastLane

        val mismatches = paths.flatMap { path =>
          expectedValues.flatMap { value =>
            val validator = JsonPathValidator(path, value)
            val legacy    = outcome(validator.validate(payload))
            val fast      = outcome(validator.validate(document))
            if (legacy == fast) None
            else Some(s"[$label] $path with ${Json.stringify(value)} -> legacy=$legacy fast=$fast")
          }
        }

        val cases = paths.size * expectedValues.size
        totalCases += cases
        totalFastLane += fastLane
        totalJaywayLane += jaywayLane
        allMismatches ++= mismatches

        report(
          f"  ${pad(label, 8)} ${padLeft(sizeOf(payload).toString, 7)} bytes  " +
            f"${padLeft(paths.size.toString, 4)} paths (${fastLane} walked, ${jaywayLane} via jayway)  " +
            f"x ${expectedValues.size} values = ${padLeft(cases.toString, 6)} cases  " +
            f"${if (mismatches.isEmpty) "OK" else s"${mismatches.size} MISMATCHES"}"
        )
      }

      report("-" * 110)
      report(
        s"  $totalCases cases total, $totalFastLane paths walked directly, $totalJaywayLane handed to jayway, " +
          s"${allMismatches.size} mismatches"
      )
      report("=" * 110)
      report("")

      allMismatches.take(50).foreach(report)

      allMismatches.toSeq mustBe Seq.empty

      // the corpus would agree just as well if every path fell back to jayway, which would make the
      // reader pointless, so the classification itself is pinned down
      totalFastLane must be > 0
      totalJaywayLane must be > 0

      FastJsonPath.segmentsOf("$.apikey.metadata.tier") mustBe Some(List("apikey", "metadata", "tier"))
      FastJsonPath.segmentsOf("$.request.headers.x-tier") mustBe Some(List("request", "headers", "x-tier"))
      FastJsonPath.segmentsOf("$.attrs['otoroshi.next.core.Route'].metadata['cost.center']") mustBe
        Some(List("attrs", "otoroshi.next.core.Route", "metadata", "cost.center"))
      FastJsonPath.segmentsOf("$..tier") mustBe None
      FastJsonPath.segmentsOf("$.apikey.tags[0]") mustBe None
      FastJsonPath.segmentsOf("$.request.headers.*") mustBe None
      FastJsonPath.segmentsOf("$.apikey.tags.length()") mustBe None
      FastJsonPath.segmentsOf("[?(@.foo == 'bar')]") mustBe None
    }

    "be faster than the regular reader" in {

      val comparisons = payloads.flatMap { case (label, payload) =>
        scenarios.map { scenario =>
          // the unit is the realistic one: evaluating a whole predicate list against one payload,
          // which is what a plugin does once per phase. the fast road pays for building its document
          // inside that unit, exactly as the plugin does.
          def legacyRound(): Boolean = scenario.predicates.forall(_.validate(payload))
          def fastRound(): Boolean   = {
            val document = JsonPathUtils.document(payload)
            scenario.predicates.forall(_.validate(document))
          }

          legacyRound() mustBe fastRound()

          Comparison(label, sizeOf(payload), scenario.name, measure(legacyRound()), measure(fastRound()))
        }
      }

      report("")
      report("=" * 110)
      report("  JSON PATH READER - COMPARISON (microseconds per predicate list evaluation)")
      report(s"  $repetitions repetitions per measurement, iterations calibrated for 40ms each, jit warmed up")
      report("=" * 110)
      report(
        "  " + pad("payload", 9) + pad("bytes", 8) + pad("scenario", 12) +
          padLeft("legacy mean", 12) + padLeft("median", 10) + padLeft("p95", 10) +
          padLeft("fast mean", 12) + padLeft("median", 10) + padLeft("p95", 10) + padLeft("speedup", 10)
      )
      report("-" * 110)

      comparisons.foreach { c =>
        report(
          "  " + pad(c.payload, 9) + pad(c.bytes.toString, 8) + pad(c.scenario, 12) +
            padLeft(micros(c.legacy.mean), 12) + padLeft(micros(c.legacy.median), 10) +
            padLeft(micros(c.legacy.p95), 10) +
            padLeft(micros(c.fast.mean), 12) + padLeft(micros(c.fast.median), 10) +
            padLeft(micros(c.fast.p95), 10) +
            padLeft(f"x${c.speedup}%.1f", 10)
        )
      }

      report("-" * 110)
      val noise = comparisons.map(c => math.max(c.legacy.relativeStddev, c.fast.relativeStddev)).max
      report(f"  worst relative standard deviation across all measurements: $noise%.1f%%")
      report("=" * 110)
      report("")

      val walked = comparisons.filter(_.scenario.startsWith("simple"))
      val jayway = comparisons.filter(_.scenario.startsWith("jayway"))
      val mixed  = comparisons.filter(_.scenario.startsWith("mixed"))

      // a plain path never serialises the payload at all, so the gap there is structural and not a
      // matter of a few percent. the threshold is kept well under what is actually measured so that
      // a loaded machine cannot make this flaky.
      walked.foreach { c =>
        withClue(s"${c.payload} / ${c.scenario}: ") {
          c.speedup must be > 2.0
        }
      }
      mixed.foreach { c =>
        withClue(s"${c.payload} / ${c.scenario}: ") {
          c.speedup must be > 1.0
        }
      }
      // a path that needs jayway still wins, because the document is built once for the whole list
      // and from a JsonNode rather than from a serialised string, but the margin is smaller
      jayway.foreach { c =>
        withClue(s"${c.payload} / ${c.scenario}: ") {
          c.speedup must be > 0.9
        }
      }

      blackhole must be > 0L
    }

    "shutdown" in {
      stopAll()
    }
  }
}
