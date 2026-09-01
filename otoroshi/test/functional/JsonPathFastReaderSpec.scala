package functional

import com.typesafe.config.ConfigFactory
import otoroshi.env.Env
import otoroshi.utils.{FastJsonPath, JsonPathDocument, JsonPathUtils, JsonPathValidator}
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
      "metadata"   -> Json.obj(
        "tier"      -> "gold",
        "count"     -> 3,
        "ratio"     -> 1.5,
        "beta"      -> true,
        "empty"     -> "",
        "nulled"    -> JsNull,
        "a.b"       -> "dotted-key",
        "weird key" -> "spaced-key",
        "clé"       -> "unicode-key",
        "quo'te"    -> "quoted-key",
        "br[a]ck"   -> "bracket-key",
        "0"         -> "numeric-key",
        "nested"    -> Json.obj("deep" -> Json.obj("deeper" -> Json.obj("deepest" -> "bottom")))
      ),
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

  // descends objects AND arrays, and emits both the dotted and the bracket form of a key whenever
  // both are legal, so that the two lanes are compared on the same data seen through both notations
  private def harvestPaths(json: JsValue, prefix: String, depth: Int): Seq[String] = {
    if (depth <= 0) Seq.empty
    else
      json match {
        case obj: JsObject =>
          obj.fields.toSeq.flatMap { case (key, value) =>
            val plain     = plainSegment.matches(key)
            val canonical = if (plain) s"$prefix.$key" else s"$prefix['$key']"
            val alternate = if (plain) Seq(s"$prefix['$key']") else Seq.empty
            Seq(canonical) ++ alternate ++ harvestPaths(value, canonical, depth - 1)
          }
        case arr: JsArray  =>
          arr.value.toSeq.zipWithIndex.take(3).flatMap { case (value, idx) =>
            val segment = s"$prefix[$idx]"
            Seq(segment) ++ harvestPaths(value, segment, depth - 1)
          }
        case _             => Seq.empty
      }
  }

  // plain segments that land on or traverse an array. this is where the direct walk and jayway are
  // the most likely to part ways: jayway can match indefinitely across an array, the walk cannot.
  private val throughArrayPaths = Seq(
    "$.apikey.tags.foo",
    "$.apikey.tags.length",
    "$.apikey.tags.0",
    "$.attrs['otoroshi.next.core.Route'].plugins.plugin",
    "$.attrs['otoroshi.next.core.Route'].plugins.enabled",
    "$.attrs['otoroshi.next.core.Route'].plugins.config",
    "$.attrs['otoroshi.next.core.Route'].plugins.config.name",
    "$.attrs['otoroshi.next.core.Route'].backend.targets.hostname",
    "$.attrs['otoroshi.next.core.Route'].backend.targets.port",
    "$.attrs['otoroshi.next.core.Route'].frontend.domains.nope",
    "$.request.cookies.name"
  )

  // root level bracket notation, and keys that are hostile to either notation
  private val notationPaths = Seq(
    "$['apikey']",
    "$['apikey']['metadata']",
    "$['apikey']['metadata']['tier']",
    "$['apikey'].metadata['tier']",
    "$.apikey['metadata'].tier",
    "$.apikey.metadata['a.b']",
    "$.apikey.metadata['weird key']",
    "$.apikey.metadata['clé']",
    "$.apikey.metadata.clé",
    "$.apikey.metadata[\'quo\'te\']",
    "$.apikey.metadata['br[a]ck']",
    "$.apikey.metadata['0']",
    "$.apikey.metadata.0",
    "$.apikey.metadata.nulled",
    "$.apikey.metadata.nested.deep.deeper.deepest",
    "$.apikey.metadata.nested.deep.deeper.nope",
    "$[\'apikey\'][\'metadata\'][\'a.b\']"
  )

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

  private def outcomeOf(f: => Option[JsValue]): Either[String, Option[JsValue]] =
    Try(f).toEither.left.map(e => s"${e.getClass.getName}")

  // true when two results only differ by how precisely a number is carried
  private def sameIgnoringNumericPrecision(
      a: Either[String, Option[JsValue]],
      b: Either[String, Option[JsValue]]
  ): Boolean = {
    def blunt(value: JsValue): JsValue = value match {
      case JsNumber(n)    => JsString(f"${n.toDouble}%.10e")
      case JsArray(items) => JsArray(items.map(blunt))
      case obj: JsObject  => JsObject(obj.value.map { case (k, v) => k -> blunt(v) }.toMap)
      case other          => other
    }
    (a, b) match {
      case (Right(x), Right(y)) => x.map(blunt) == y.map(blunt)
      case _                    => false
    }
  }

  private def render(outcome: Either[String, Option[JsValue]]): String = outcome match {
    case Left(error)         => s"!$error"
    case Right(None)         => "<none>"
    case Right(Some(value))  =>
      val rendered = Json.stringify(value)
      if (rendered.length > 160) rendered.take(157) + "..." else rendered
  }

  // the whole path set of a payload: harvested from its own structure, plus the shapes that have to
  // be exercised on purpose because harvesting cannot produce them
  private def pathsFor(payload: JsValue): Seq[String] =
    (harvestPaths(payload, "$", 6) ++ throughArrayPaths ++ notationPaths ++ jaywayPaths ++ missingPaths ++
      garbagePaths).distinct

  // malformed, empty or plain nonsensical paths. neither road is expected to resolve them, but both
  // have to fail the same way rather than one of them sneaking a value through.
  private val garbagePaths = Seq(
    "",
    " ",
    "$",
    "$.",
    "..",
    "...",
    "$..",
    "$[",
    "$]",
    "$['unclosed",
    "$.a[[",
    "$.a..b",
    "@.foo",
    "foo",
    "$..*",
    "$[*]",
    "$[?()]",
    "$[?(@)]",
    "$.a[?(",
    "\u0000",
    "$." + ("a." * 40) + "b"
  )

  // payloads that are not json objects, plus degenerate ones. `isObject` drives one branch of the
  // validator, and the reader has to cope with a root that cannot be walked at all.
  private val exoticPayloads: Seq[(String, JsValue)] = Seq(
    "array root"   -> Json.arr(Json.obj("a" -> 1), Json.obj("a" -> 2), "plain", 3, JsNull),
    "string root"  -> JsString("just a string"),
    "number root"  -> JsNumber(42),
    "bool root"    -> JsBoolean(true),
    "null root"    -> JsNull,
    "empty object" -> Json.obj(),
    "empty array"  -> Json.arr(),
    // the regular road goes JsValue -> string -> jackson -> JsonNode -> JsValue, and play json
    // strips trailing zeros on the way. the walk hands back the original JsValue untouched. numbers
    // and exotic strings are where the two can part ways without anyone noticing.
    "tricky scalars" -> Json.obj(
      "trailingZero"   -> JsNumber(BigDecimal("1.50")),
      "trailingZeros"  -> JsNumber(BigDecimal("0.1000")),
      "exponent"       -> JsNumber(BigDecimal("1e10")),
      "negExponent"    -> JsNumber(BigDecimal("1.5e-8")),
      "hugeInt"        -> JsNumber(BigDecimal("123456789012345678901234567890")),
      "highPrecision"  -> JsNumber(BigDecimal("1.234567890123456789012345678901234567890")),
      "negZero"        -> JsNumber(BigDecimal("-0.0")),
      "intLike"        -> JsNumber(BigDecimal("42.0")),
      "controlChars"   -> JsString("line\nbreak\ttab\u0007bell"),
      "quotes"         -> JsString("he said \"hi\" and \\ escaped"),
      "emoji"          -> JsString("héllo 👋🏽 wörld"),
      "surrogate"      -> JsString("\uD83D\uDE00"),
      "longString"     -> JsString("x" * 5000),
      "emptyString"    -> JsString(""),
      "nested"         -> Json.obj("deep" -> JsNumber(BigDecimal("2.500")))
    ),
    "nested arrays" -> Json.obj(
      "matrix" -> Json.arr(Json.arr(1, 2), Json.arr(3, 4)),
      "objs"   -> Json.arr(Json.obj("k" -> Json.arr(Json.obj("deep" -> "value"))))
    )
  )

  private val exoticPaths = Seq(
    "$",
    "$.a",
    "$[0]",
    "$[0].a",
    "$[*]",
    "$[*].a",
    "$..a",
    "$.matrix",
    "$.matrix[0]",
    "$.matrix[0][1]",
    "$.matrix.0",
    "$.objs[0].k[0].deep",
    "$.objs.k.deep",
    "$.trailingZero",
    "$.trailingZeros",
    "$.exponent",
    "$.negExponent",
    "$.hugeInt",
    "$.highPrecision",
    "$.negZero",
    "$.intLike",
    "$.controlChars",
    "$.quotes",
    "$.emoji",
    "$.surrogate",
    "$.longString",
    "$.emptyString",
    "$.nested.deep",
    "$.nope"
  ) ++ garbagePaths

  // ------------------------------------------------------------------------------------------------
  // property based generation. hand picked cases only cover what was thought of; this walks the json
  // value space instead. fixed seed, so a failure is reproducible.
  // ------------------------------------------------------------------------------------------------

  private val seed = 20260901L

  private def randomKey(rng: scala.util.Random): String = rng.nextInt(12) match {
    case 0 => "plain"
    case 1 => s"k${rng.nextInt(1000)}"
    case 2 => "with.dot"
    case 3 => "with space"
    case 4 => "unicodé"
    case 5 => "quo'te"
    case 6 => "br[a]ck"
    case 7 => ""
    case 8 => rng.nextInt(100).toString
    case 9 => "with\"quote"
    case 10 => "with\\backslash"
    case _ => "UPPER_case-dash"
  }

  private def randomString(rng: scala.util.Random): String = rng.nextInt(10) match {
    case 0 => ""
    case 1 => "plain value"
    case 2 => "with\nnewline\ttab"
    case 3 => "\u0000\u0007\u001f control"
    case 4 => "quo\"te and \\ backslash"
    case 5 => "héllo 👋🏽 wörld"
    case 6 => new String(Character.toChars(0x1F600))
    case 7 => "x" * (rng.nextInt(2000) + 1)
    case 8 => rng.nextString(rng.nextInt(20) + 1)
    case _ => s"value-${rng.nextInt(10000)}"
  }

  private def randomNumber(rng: scala.util.Random): JsValue = rng.nextInt(12) match {
    case 0  => JsNumber(BigDecimal(rng.nextInt()))
    case 1  => JsNumber(BigDecimal(rng.nextLong()))
    case 2  => JsNumber(BigDecimal("0"))
    case 3  => JsNumber(BigDecimal("-0.0"))
    case 4  => JsNumber(BigDecimal(rng.nextDouble()))
    // beyond what a double can hold: this is where a string round trip loses information
    case 5  => JsNumber(BigDecimal(BigInt(rng.nextLong()) * BigInt(rng.nextLong()) * BigInt(rng.nextLong())))
    case 6  => JsNumber(BigDecimal(s"1.${"1234567890" * 4}"))
    case 7  => JsNumber(BigDecimal(s"${rng.nextInt(1000)}e${rng.nextInt(40) - 20}"))
    case 8  => JsNumber(BigDecimal(rng.nextInt(1000)).setScale(rng.nextInt(30), BigDecimal.RoundingMode.HALF_UP))
    case 9  => JsNumber(BigDecimal(Long.MaxValue) + BigDecimal(1))
    case 10 => JsNumber(BigDecimal(Long.MinValue) - BigDecimal(1))
    case _  => JsNumber(BigDecimal("1.50"))
  }

  private def randomJson(rng: scala.util.Random, depth: Int): JsValue = {
    if (depth <= 0) {
      rng.nextInt(5) match {
        case 0 => JsString(randomString(rng))
        case 1 => randomNumber(rng)
        case 2 => JsBoolean(rng.nextBoolean())
        case 3 => JsNull
        case _ => JsString(randomString(rng))
      }
    } else {
      rng.nextInt(10) match {
        case 0 | 1 | 2 | 3 =>
          JsObject((0 until rng.nextInt(5)).map(_ => randomKey(rng) -> randomJson(rng, depth - 1)).toMap)
        case 4 | 5         =>
          JsArray((0 until rng.nextInt(4)).map(_ => randomJson(rng, depth - 1)))
        case 6             => JsString(randomString(rng))
        case 7             => randomNumber(rng)
        case 8             => JsBoolean(rng.nextBoolean())
        case _             => JsNull
      }
    }
  }

  // the derived entry points, rebuilt on top of the fast reader so they can be compared to the
  // originals. getAtPolyJsonStr and matchWith are what workflows and the users plugins actually go
  // through, so they are compared directly and not merely reasoned about.
  private def fastPolyJsonStr(document: JsonPathDocument, path: String): String =
    (document.read(path) match {
      case Some(JsString(value))  => value.some
      case Some(JsBoolean(value)) => value.toString.some
      case Some(JsNumber(value))  => value.toString.some
      case Some(o @ JsObject(_))  => o.stringify.some
      case Some(o @ JsArray(_))   => o.stringify.some
      case _                      => "null".some
    }).getOrElse("null")

  private def fastMatchWith(document: JsonPathDocument): String => Boolean =
    (path: String) => document.read(path).isDefined

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

    "read exactly what the regular reader reads, path by path" in {

      // The strong oracle. Comparing validate() booleans is weak: two different reads can both end
      // up false and hide a divergence. Here the raw Option[JsValue] of every entry point is
      // compared instead, which is what a future replacement of JsonPathUtils would rest on.
      report("")
      report("=" * 110)
      report("  JSON PATH READER - RAW READ EQUIVALENCE")
      report("=" * 110)

      var totalPaths      = 0
      var totalFastLane   = 0
      var totalJaywayLane = 0
      val rawMismatches   = scala.collection.mutable.ArrayBuffer.empty[String]

      payloads.foreach { case (label, payload) =>
        val paths    = pathsFor(payload)
        val document = JsonPathUtils.document(payload)

        val fastLane = paths.count(p => FastJsonPath.segmentsOf(p).isDefined)

        val mismatches = paths.flatMap { path =>
          // three ways in: the JsonPathUtils primitive, the JsValue.atPath extension the rest of
          // otoroshi goes through, and the fast reader
          val viaUtils  = outcomeOf(JsonPathUtils.getAtPolyJsonLegacy(payload, path))
          val viaAtPath = outcomeOf(payload.atPathLegacy(path).asOpt[JsValue])
          val viaFast   = outcomeOf(document.read(path))
          if (viaUtils == viaFast && viaUtils == viaAtPath) None
          else
            Some(
              s"[$label] $path -> legacy=${render(viaUtils)} atPathLegacy=${render(viaAtPath)} fast=${render(viaFast)}"
            )
        }

        totalPaths += paths.size
        totalFastLane += fastLane
        totalJaywayLane += (paths.size - fastLane)
        rawMismatches ++= mismatches

        report(
          f"  ${pad(label, 8)} ${padLeft(sizeOf(payload).toString, 7)} bytes  " +
            f"${padLeft(paths.size.toString, 4)} paths (${fastLane} walked, ${paths.size - fastLane} via jayway)  " +
            f"${if (mismatches.isEmpty) "OK" else s"${mismatches.size} MISMATCHES"}"
        )
      }

      report("-" * 110)
      report(
        s"  $totalPaths raw reads compared across 3 entry points, $totalFastLane walked directly, " +
          s"$totalJaywayLane handed to jayway, ${rawMismatches.size} mismatches"
      )
      report("=" * 110)
      report("")

      rawMismatches.take(60).foreach(report)

      rawMismatches.toSeq mustBe Seq.empty
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
        // capped: the cross product with every expected value is what costs here, and the raw read
        // comparison above already covers the full path set
        val paths    = pathsFor(payload).take(160)
        val document = JsonPathUtils.document(payload)

        val fastLane   = paths.count(p => FastJsonPath.segmentsOf(p).isDefined)
        val jaywayLane = paths.size - fastLane

        val mismatches = paths.flatMap { path =>
          expectedValues.flatMap { value =>
            val validator = JsonPathValidator(path, value)
            val legacy    = outcome(validator.validateLegacy(payload))
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

    "answer the same on every derived entry point" in {

      report("")
      report("=" * 110)
      report("  JSON PATH READER - DERIVED ENTRY POINTS")
      report("=" * 110)

      val mismatches = scala.collection.mutable.ArrayBuffer.empty[String]
      var compared   = 0

      payloads.foreach { case (label, payload) =>
        val paths    = pathsFor(payload)
        val document = JsonPathUtils.document(payload)

        paths.foreach { path =>
          compared += 1

          // what workflows go through
          val legacyStr = Try(JsonPathUtils.getAtPolyJsonStr(payload, path)).toEither.left.map(_.getClass.getName)
          val fastStr   = Try(fastPolyJsonStr(document, path)).toEither.left.map(_.getClass.getName)
          if (legacyStr != fastStr) {
            mismatches += s"[$label] getAtPolyJsonStr $path -> legacy=$legacyStr fast=$fastStr"
          }

          // what the users plugins go through
          val legacyMatch = Try(JsonPathUtils.matchWithLegacy(payload)(path)).toEither.left.map(_.getClass.getName)
          val fastMatch   = Try(fastMatchWith(document)(path)).toEither.left.map(_.getClass.getName)
          if (legacyMatch != fastMatch) {
            mismatches += s"[$label] matchWith $path -> legacy=$legacyMatch fast=$fastMatch"
          }

          // the typed reads, which have no caller in app but are public api. they are
          // getAtPoly + asOpt[T], so proving them for several T pins the derivation down too.
          val legacyJs = Try(JsonPathUtils.getAtJson[JsValue](payload, path)).toEither.left.map(_.getClass.getName)
          val fastJs   = Try(document.read(path).flatMap(_.asOpt[JsValue])).toEither.left.map(_.getClass.getName)
          if (legacyJs != fastJs) mismatches += s"[$label] getAtJson[JsValue] $path -> legacy=$legacyJs fast=$fastJs"

          val legacyStrT = Try(JsonPathUtils.getAtJson[String](payload, path)).toEither.left.map(_.getClass.getName)
          val fastStrT   = Try(document.read(path).flatMap(_.asOpt[String])).toEither.left.map(_.getClass.getName)
          if (legacyStrT != fastStrT) mismatches += s"[$label] getAtJson[String] $path -> legacy=$legacyStrT fast=$fastStrT"

          val legacyInt = Try(JsonPathUtils.getAtJson[Int](payload, path)).toEither.left.map(_.getClass.getName)
          val fastInt   = Try(document.read(path).flatMap(_.asOpt[Int])).toEither.left.map(_.getClass.getName)
          if (legacyInt != fastInt) mismatches += s"[$label] getAtJson[Int] $path -> legacy=$legacyInt fast=$fastInt"

          val legacyBool = Try(JsonPathUtils.getAtJson[Boolean](payload, path)).toEither.left.map(_.getClass.getName)
          val fastBool   = Try(document.read(path).flatMap(_.asOpt[Boolean])).toEither.left.map(_.getClass.getName)
          if (legacyBool != fastBool) mismatches += s"[$label] getAtJson[Boolean] $path -> legacy=$legacyBool fast=$fastBool"

          // getAt takes an already serialised payload, the other half of the typed api
          val legacyRaw = Try(JsonPathUtils.getAt[JsValue](Json.stringify(payload), path)).toEither.left
            .map(_.getClass.getName)
          if (legacyRaw != fastJs) mismatches += s"[$label] getAt[JsValue] $path -> legacy=$legacyRaw fast=$fastJs"
        }

        report(f"  ${pad(label, 8)} ${padLeft(paths.size.toString, 4)} paths x 7 entry points  " +
          f"${if (mismatches.isEmpty) "OK" else s"${mismatches.size} MISMATCHES"}")
      }

      report("-" * 110)
      report(s"  $compared paths compared on getAtPolyJsonStr, matchWith, getAtJson[JsValue|String|Int|Boolean] " +
          s"and getAt, ${mismatches.size} mismatches")
      report("=" * 110)
      report("")

      mismatches.take(40).foreach(report)
      mismatches.toSeq mustBe Seq.empty
    }

    "behave the same on payloads that are not objects, and on malformed paths" in {

      report("")
      report("=" * 110)
      report("  JSON PATH READER - EXOTIC PAYLOADS AND MALFORMED PATHS")
      report("=" * 110)

      val mismatches = scala.collection.mutable.ArrayBuffer.empty[String]
      var compared   = 0

      exoticPayloads.foreach { case (label, payload) =>
        val document = JsonPathUtils.document(payload)
        val local    = mismatches.size

        (exoticPaths ++ harvestPaths(payload, "$", 4)).distinct.foreach { path =>
          compared += 1
          val viaUtils  = outcomeOf(JsonPathUtils.getAtPolyJsonLegacy(payload, path))
          val viaAtPath = outcomeOf(payload.atPathLegacy(path).asOpt[JsValue])
          val viaFast   = outcomeOf(document.read(path))
          if (viaUtils != viaFast || viaUtils != viaAtPath) {
            val kind = if (sameIgnoringNumericPrecision(viaUtils, viaFast)) "NUMERIC" else "REAL"
            mismatches +=
              s"$kind [$label] $path -> legacy=${render(viaUtils)} atPathLegacy=${render(viaAtPath)} fast=${render(viaFast)}"
          }
        }

        report(
          f"  ${pad(label, 15)} ${padLeft((exoticPaths.size).toString, 4)}+ paths  " +
            f"isObject=${document.isObject}%-5s  ${if (mismatches.size == local) "OK" else s"${mismatches.size - local} MISMATCHES"}"
        )
      }

      report("-" * 110)
      report(
        s"  $compared reads compared, ${mismatches.count(_.startsWith("REAL"))} real mismatches, " +
          s"${mismatches.count(_.startsWith("NUMERIC"))} numeric precision only"
      )
      report("=" * 110)
      report("")

      mismatches.take(40).foreach(report)
      mismatches.filter(_.startsWith("REAL")).toSeq mustBe Seq.empty
    }

    "answer the same on randomly generated documents" in {

      // the systematic check. every entry point, over documents drawn from the json value space
      // rather than from what happened to come to mind.
      report("")
      report("=" * 110)
      report("  JSON PATH READER - PROPERTY BASED (seed " + seed + ")")
      report("=" * 110)

      val documents = 400
      val rng       = new scala.util.Random(seed)

      var comparedPaths = 0
      var walked        = 0
      var viaJayway     = 0
      val byKind        = scala.collection.mutable.Map.empty[String, Int]
      val samples       = scala.collection.mutable.ArrayBuffer.empty[String]

      (1 to documents).foreach { _ =>
        val payload  = randomJson(rng, 4)
        val document = JsonPathUtils.document(payload)
        val paths    = (harvestPaths(payload, "$", 5) ++ exoticPaths).distinct.take(200)

        paths.foreach { path =>
          comparedPaths += 1
          if (FastJsonPath.segmentsOf(path).isDefined) walked += 1 else viaJayway += 1

          def note(kind: String, legacy: String, fast: String): Unit = {
            byKind.update(kind, byKind.getOrElse(kind, 0) + 1)
            if (samples.size < 40) samples += s"$kind | $path | legacy=$legacy fast=$fast"
          }

          val viaUtils  = outcomeOf(JsonPathUtils.getAtPolyJsonLegacy(payload, path))
          val viaAtPath = outcomeOf(payload.atPathLegacy(path).asOpt[JsValue])
          val viaFast   = outcomeOf(document.read(path))
          // a divergence that goes away once numbers are compared as text is a precision difference
          // and nothing else. counted apart, because that distinction is the whole decision.
          if (viaUtils != viaFast) {
            val kind = if (sameIgnoringNumericPrecision(viaUtils, viaFast)) "getAtPolyJson NUMERIC" else "getAtPolyJson"
            note(kind, render(viaUtils), render(viaFast))
          }
          if (viaUtils != viaAtPath) note("atPathLegacy vs getAtPolyJsonLegacy", render(viaUtils), render(viaAtPath))

          // a divergence on this path is a precision one when the raw reads only differ that way
          val numericOnly = sameIgnoringNumericPrecision(viaUtils, viaFast)
          val suffix      = if (numericOnly) " NUMERIC" else ""

          val legacyStr = Try(JsonPathUtils.getAtPolyJsonStr(payload, path)).toEither.left.map(_.getClass.getName)
          val fastStr   = Try(fastPolyJsonStr(document, path)).toEither.left.map(_.getClass.getName)
          if (legacyStr != fastStr) {
            note(s"getAtPolyJsonStr$suffix", legacyStr.toString.take(80), fastStr.toString.take(80))
          }

          val legacyMatch = Try(JsonPathUtils.matchWithLegacy(payload)(path)).toEither.left.map(_.getClass.getName)
          val fastMatch   = Try(fastMatchWith(document)(path)).toEither.left.map(_.getClass.getName)
          if (legacyMatch != fastMatch) note("matchWith", legacyMatch.toString, fastMatch.toString)

          val legacyJs = Try(JsonPathUtils.getAtJson[JsValue](payload, path)).toEither.left.map(_.getClass.getName)
          val fastJs   = Try(document.read(path).flatMap(_.asOpt[JsValue])).toEither.left.map(_.getClass.getName)
          if (legacyJs != fastJs) note(s"getAtJson$suffix", "", "")

          // and the validator layer on top, with a handful of values rather than the whole language
          Seq(JsString("IsDefined()"), JsString("NotDefined()"), JsString("Contains(a)"), JsNumber(1)).foreach {
            expected =>
              val validator = JsonPathValidator(path, expected)
              val l         = outcome(validator.validateLegacy(payload))
              val f         = outcome(validator.validate(document))
              if (l != f) note(s"validate ${Json.stringify(expected)}", l.toString, f.toString)
          }
        }
      }

      report(s"  $documents documents, $comparedPaths paths ($walked walked, $viaJayway via jayway)")
      report(s"  entry points: getAtPolyJson, atPath, getAtPolyJsonStr, matchWith, getAtJson, validate")
      report("-" * 110)
      if (byKind.isEmpty) report("  no divergence")
      else byKind.toSeq.sortBy(-_._2).foreach { case (kind, count) => report(f"  ${pad(kind, 30)} $count") }
      report("=" * 110)
      report("")
      samples.foreach(report)

      // everything that is not a pure precision difference has to be zero
      byKind.filterNot(_._1.endsWith("NUMERIC")).keys.toSeq.sorted mustBe Seq.empty
    }

    "still be comparing two genuinely different engines" in {

      // If the legacy entry points ever get pointed at the fast reader, this whole suite turns into
      // a comparison of the fast reader with itself: green, and proving nothing. This pins down a
      // value the two engines are known to carry differently, so that day fails loudly here.
      val big     = BigDecimal("1.234567890123456789012345678901234567890")
      val payload = Json.obj("n" -> JsNumber(big))

      val legacy = JsonPathUtils.getAtPolyJsonLegacy(payload, "$.n")
      val fast   = JsonPathUtils.getAtPolyJsonFast(payload, "$.n")

      report(s"  legacy reads $legacy")
      report(s"  fast   reads $fast")

      fast mustBe Some(JsNumber(big))
      legacy must not be fast
    }

    "not depend on the null read flag for an explicit json null" in {

      // The flag guards the branch taken when jayway hands back a java null. With
      // JacksonJsonNodeJsonProvider a json null comes back as a NullNode, which is not a java null,
      // so the branch is not what decides the answer for an explicit null. Pinned down rather than
      // assumed, because the walked lane does not consult the flag at all.
      val payload = Json.obj("a" -> JsNull, "b" -> Json.obj("c" -> JsNull))

      report(s"  jsonPathNullReadIsJsNull = ${env.jsonPathNullReadIsJsNull}")

      JsonPathUtils.getAtPolyJsonLegacy(payload, "$.a") mustBe Some(JsNull)
      JsonPathUtils.getAtPolyJsonLegacy(payload, "$.b.c") mustBe Some(JsNull)

      val document = JsonPathUtils.document(payload)
      document.read("$.a") mustBe Some(JsNull)
      document.read("$.b.c") mustBe Some(JsNull)

      // and a genuinely absent path stays absent on both roads whatever the flag says
      JsonPathUtils.getAtPolyJsonLegacy(payload, "$.nope") mustBe None
      document.read("$.nope") mustBe None
    }

    "give the same answers when hammered from several threads" in {

      // a switch would put this on every request, so the shared caches and the lazy document are
      // exercised concurrently, including their very first use
      val payload  = payloads.last._2
      val paths    = pathsFor(payload).take(120)
      val expected = {
        val document = JsonPathUtils.document(payload)
        paths.map(path => path -> outcomeOf(document.read(path))).toMap
      }

      val threads   = 8
      val rounds    = 40
      val executor  = java.util.concurrent.Executors.newFixedThreadPool(threads)
      val failures  = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val latch     = new java.util.concurrent.CountDownLatch(threads)

      (1 to threads).foreach { _ =>
        executor.submit(new Runnable {
          override def run(): Unit = {
            try {
              (1 to rounds).foreach { _ =>
                // a fresh document per round, as a plugin would build one per phase
                val document = JsonPathUtils.document(payload)
                paths.foreach { path =>
                  val got = outcomeOf(document.read(path))
                  if (got != expected(path)) {
                    failures.add(s"$path -> expected ${render(expected(path))} got ${render(got)}")
                  }
                }
              }
            } catch {
              case e: Throwable => failures.add(s"thread blew up: ${e}")
            } finally latch.countDown()
          }
        })
      }

      latch.await(120, java.util.concurrent.TimeUnit.SECONDS) mustBe true
      executor.shutdownNow()

      report(s"  $threads threads x $rounds rounds x ${paths.size} paths, ${failures.size} failures")
      failures.toArray.take(20).foreach(f => report(s"  $f"))
      failures.isEmpty mustBe true
    }

    "be faster than the regular reader" in {

      val comparisons = payloads.flatMap { case (label, payload) =>
        scenarios.map { scenario =>
          // the unit is the realistic one: evaluating a whole predicate list against one payload,
          // which is what a plugin does once per phase. the fast road pays for building its document
          // inside that unit, exactly as the plugin does.
          def legacyRound(): Boolean = scenario.predicates.forall(_.validateLegacy(payload))
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

    "not be slower on a single shot read, which is how atPath is called" in {

      // rbac, graphql and the auth module user validators call atPath once and move on. a switch
      // would make each of those build a document, so the one shot cost is what decides whether the
      // reader can go under atPath at all.
      report("")
      report("=" * 110)
      report("  JSON PATH READER - SINGLE SHOT READ (microseconds per read)")
      report("=" * 110)
      report(
        "  " + pad("payload", 9) + pad("bytes", 8) + pad("path", 34) +
          padLeft("legacy mean", 12) + padLeft("fast mean", 12) + padLeft("speedup", 10)
      )
      report("-" * 110)

      val singleShotPaths = Seq(
        "$.apikey.metadata.tier",
        "$.attrs['otoroshi.next.core.Route'].metadata.tier",
        "$.apikey.tags[0]",
        "$..tier"
      )

      val results = payloads.flatMap { case (label, payload) =>
        singleShotPaths.map { path =>
          def legacyOne(): Boolean = JsonPathUtils.getAtPolyJsonLegacy(payload, path).isDefined
          def fastOne(): Boolean   = JsonPathUtils.document(payload).read(path).isDefined

          legacyOne() mustBe fastOne()

          val legacy  = measure(legacyOne())
          val fast    = measure(fastOne())
          val speedup = legacy.mean / fast.mean

          report(
            "  " + pad(label, 9) + pad(sizeOf(payload).toString, 8) + pad(path.take(32), 34) +
              padLeft(micros(legacy.mean), 12) + padLeft(micros(fast.mean), 12) +
              padLeft(f"x$speedup%.1f", 10)
          )

          (label, path, speedup)
        }
      }

      report("=" * 110)
      report("")

      // a single shot on a path that needs jayway is the worst case for the reader: it builds a
      // document for one read, exactly like the regular road does. it must not lose ground there.
      results.foreach { case (label, path, speedup) =>
        withClue(s"$label / $path: ") {
          speedup must be > 0.85
        }
      }
    }

    "shutdown" in {
      stopAll()
    }
  }
}


/**
 * The same differential corpus, with `jsonPathNullReadIsJsNull` forced on.
 *
 * The flag is read once per JVM through a `lazy val` in `JsonPathUtils`, so this cannot share a run
 * with [[JsonPathFastReaderSpec]]. It is its own suite for that reason:
 *
 * {{{
 * sbt 'testOnly JsonPathNullReadTests'
 * }}}
 */
class JsonPathNullReadOnSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(
      ConfigFactory
        .parseString("""
          |{
          |  otoroshi.options.jsonPathNullReadIsJsNull = true
          |}
        """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  private lazy implicit val env: Env = otoroshiComponents.env

  private val payload = Json.obj(
    "nulled" -> JsNull,
    "nested" -> Json.obj("nulled" -> JsNull, "value" -> "here"),
    "list"   -> Json.arr(JsNull, "a", 2),
    "value"  -> "here"
  )

  private val paths = Seq(
    "$.nulled",
    "$.nested.nulled",
    "$.nested.value",
    "$.nested.nope",
    "$.list",
    "$.list[0]",
    "$.list[1]",
    "$..nulled",
    "$.nope",
    "$.value.nope"
  )

  "the fast json path reader, with jsonPathNullReadIsJsNull on" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().andThen { case Failure(e) => e.printStackTrace() }.futureValue
    }

    "read exactly what the regular reader reads" in {

      // guard against a silent pass: if the flag did not actually take, this suite proves nothing
      if (!env.jsonPathNullReadIsJsNull) {
        cancel(
          "jsonPathNullReadIsJsNull is still false. JsonPathUtils reads it through a per JVM lazy val, " +
            "so this suite has to run on its own: sbt 'testOnly JsonPathNullReadTests'"
        )
      }

      val document = JsonPathUtils.document(payload)

      val mismatches = paths.flatMap { path =>
        val legacy = Try(JsonPathUtils.getAtPolyJsonLegacy(payload, path)).toEither.left.map(_.getClass.getName)
        val fast   = Try(document.read(path)).toEither.left.map(_.getClass.getName)
        if (legacy == fast) None else Some(s"$path -> legacy=$legacy fast=$fast")
      }

      println(s"  jsonPathNullReadIsJsNull = ${env.jsonPathNullReadIsJsNull}, " +
        s"${paths.size} paths compared, ${mismatches.size} mismatches")
      mismatches.foreach(m => println(s"  $m"))

      mismatches mustBe Seq.empty
    }

    "shutdown" in {
      stopAll()
    }
  }
}
