package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import java.nio.charset.{Charset, StandardCharsets}
import java.util.regex.{Matcher, Pattern}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RegexReplacementRule(
    pattern: String,
    replacement: String,
    flags: Option[String] = None
)                           {
  def json: JsValue = RegexReplacementRule.format.writes(this)
}
object RegexReplacementRule {
  val format: Format[RegexReplacementRule] = new Format[RegexReplacementRule] {
    override def reads(json: JsValue): JsResult[RegexReplacementRule] = Try {
      RegexReplacementRule(
        pattern = json.select("pattern").asString,
        replacement = json.select("replacement").asString,
        flags = json.select("flags").asOptString
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }

    override def writes(o: RegexReplacementRule): JsValue = Json.obj(
      "pattern"     -> o.pattern,
      "replacement" -> o.replacement,
      "flags"       -> o.flags
    )
  }
}

case class RegexBodyRewriterConfig(
    contentTypes: Seq[String] = Seq("text/html"),
    rules: Seq[RegexReplacementRule] = Seq.empty,
    autoHrefPrefix: Option[String] = None,
    maxBodySize: Option[Long] = None,
    charsetFallback: Option[String] = Some("UTF-8")
) extends NgPluginConfig {
  def json: JsValue = RegexBodyRewriterConfig.format.writes(this)
}
object RegexBodyRewriterConfig {
  val configFlow: Seq[String] = Seq(
    "content_types",
    "rules",
    "auto_href_prefix",
    "max_body_size",
    "charset_fallback"
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "content_types"    -> Json.obj(
        "type"    -> "array",
        "label"   -> "Target Content-Types",
        "format"  -> "string",
        "array"   -> Json.obj("format" -> "string"),
        "props"   -> Json.obj("placeholder" -> "text/html"),
        "default" -> Json.arr("text/html")
      ),
      "rules"            -> Json.obj(
        "type"   -> "array",
        "label"  -> "Regex rules",
        "array"  -> true,
        "format" -> "form",
        "schema" -> Json.obj(
          "pattern"     -> Json
            .obj("type" -> "string", "label" -> "Pattern", "placeholder" -> "href=([\\\"'])/v1/(.+?)\\1"),
          "replacement" -> Json.obj("type" -> "string", "label" -> "Replacement", "placeholder" -> "href=$1/v2/$2$1"),
          "flags"       -> Json.obj("type" -> "string", "label" -> "Flags (imsu)", "placeholder" -> "i")
        ),
        "flow"   -> Json.arr("pattern", "replacement", "flags")
      ),
      "auto_href_prefix" -> Json.obj(
        "type"        -> "string",
        "label"       -> "Auto prefix for relative href (optional)",
        "placeholder" -> "/proxy"
      ),
      "max_body_size"    -> Json.obj(
        "type"        -> "number",
        "label"       -> "Max body size (bytes)",
        "placeholder" -> "512000",
        "props"       -> Json.obj("suffix" -> "bytes")
      ),
      "charset_fallback" -> Json.obj(
        "type"        -> "string",
        "label"       -> "Charset fallback",
        "placeholder" -> "UTF-8",
        "default"     -> "UTF-8"
      )
    )
  )

  implicit val format: Format[RegexBodyRewriterConfig] = new Format[RegexBodyRewriterConfig] {
    override def reads(json: JsValue): JsResult[RegexBodyRewriterConfig] = Try {
      RegexBodyRewriterConfig(
        contentTypes = json.select("content_types").asOpt[Seq[String]].getOrElse(Seq("text/html")),
        rules = json
          .select("rules")
          .asOpt[Seq[JsObject]]
          .map(seq => seq.flatMap(r => RegexReplacementRule.format.reads(r).asOpt))
          .getOrElse(Seq.empty),
        autoHrefPrefix = json.select("auto_href_prefix").asOpt[String],
        maxBodySize = json.select("max_body_size").asOpt[Long],
        charsetFallback = json.select("charset_fallback").asOpt[String].orElse(Some("UTF-8"))
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: RegexBodyRewriterConfig): JsValue             = Json.obj(
      "content_types"    -> o.contentTypes,
      "rules"            -> JsArray(o.rules.map(_.json)),
      "auto_href_prefix" -> o.autoHrefPrefix,
      "max_body_size"    -> o.maxBodySize,
      "charset_fallback" -> o.charsetFallback
    )
  }
}

/**
 * RegexResponseBodyRewriter
 * ------------------
 *
 * NG Otoroshi plugin (step: TransformResponse) that rewrites the HTTP response body
 * by applying one or more regex rules.
 *
 * Typical use case: rewrite href values in HTML (e.g., prefix relative links with "/proxy").
 *
 * Notes
 * - The body is fully buffered to simplify replacements (no multi-chunk streaming regex).
 * Limit the size via max_response_body_size.
 * - Compressed responses (Content-Encoding: gzip/...) are not decoded by default.
 * You can disable compression on the backend/route, or insert a decompression plugin upstream.
 * - Content-Length and Content-Encoding headers are removed/adjusted after rewriting.
 */
class RegexResponseBodyRewriter extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = false
  override def transformsResponse: Boolean       = true
  override def noJsForm: Boolean                 = true

  override def name: String                = "Regex response body rewriter"
  override def description: Option[String] = Some(
    "Rewrites the HTTP response body using a set of regex rules, with optional auto-prefix for relative hrefs."
  )

  override def documentation: Option[String]               = Some(
    """1) Prefix all relative hrefs with /proxy and rewrite a domain to another
      |
      |```json
      |{
      |  "plugin": "cp:otoroshi.next.plugins.RegexResponseBodyRewriter",
      |  "enabled": true,
      |  "config": {
      |    "content_types": ["text/html"],
      |    "auto_href_prefix": "/proxy",
      |    "rules": [
      |      { "pattern": "(?i)https?://example\.com", "replacement": "https://public.example.com" }
      |    ],
      |    "max_body_size": 1048576
      |  }
      |}
      |```
      |
      |2) Rewrite paths while keeping captured groups
      |
      |```json
      |{
      |  "plugin": "cp:otoroshi.next.plugins.RegexResponseBodyRewriter",
      |  "enabled": true,
      |  "config": {
      |    "content_types": ["text/html", "text/plain"],
      |    "rules": [
      |      { "pattern": "href=([\"'])/v1/(.+?)\1", "replacement": "href=$1/v2/$2$1", "flags": "i" }
      |    ]
      |  }
      |}
      |```
      |
      |Note: replacement supports backrefs $1, $2, ...
      |""".stripMargin
  )
  override def defaultConfigObject: Option[NgPluginConfig] = Some(
    RegexBodyRewriterConfig()
  )
  override def configFlow: Seq[String]                     = RegexBodyRewriterConfig.configFlow
  override def configSchema: Option[JsObject]              = RegexBodyRewriterConfig.configSchema

  private def flagsBits(flags: Option[String]): Int = {
    var bits = Pattern.UNICODE_CASE
    flags.getOrElse("").foreach {
      case 'i' | 'I' => bits |= Pattern.CASE_INSENSITIVE
      case 'm' | 'M' => bits |= Pattern.MULTILINE
      case 's' | 'S' => bits |= Pattern.DOTALL
      case 'u' | 'U' => bits |= Pattern.UNICODE_CASE
      case _         => // ignore
    }
    bits
  }

  private def detectCharset(contentTypeHeader: Option[String], fallback: String): Charset = {
    val fromHeader = contentTypeHeader
      .flatMap { ct =>
        val idx = ct.toLowerCase.indexOf("charset=")
        if (idx > -1) Some(ct.substring(idx + 8).trim.replace("\"", "").replace("'", "")) else None
      }
      .flatMap { name => Try(Charset.forName(name)).toOption }
    fromHeader.getOrElse(Try(Charset.forName(fallback)).getOrElse(StandardCharsets.UTF_8))
  }

  private def applyRegexReplacementRules(input: String, rules: Seq[RegexReplacementRule]): String = {
    rules.foldLeft(input) { case (acc, rule) =>
      val p: Pattern = Pattern.compile(rule.pattern, flagsBits(rule.flags))
      val m: Matcher = p.matcher(acc)
      // IMPORTANT: do not quote replacement on purpose to allow $1, $2, ... backrefs
      m.replaceAll(rule.replacement)
    }
  }

  private def autoPrefixHref(html: String, prefix: String): String = {
    // negative lookahead to skip absolute urls and anchors/protocols
    val hrefRe = Pattern.compile(
      """(?i)\bhref\s*=\s*(['\"])\s*(?!https?://|/|#|mailto:|tel:|javascript:)([^'\"#?]+(?:\?[^'\"]*)?)\1"""
    )
    val sb     = new StringBuffer()
    val m      = hrefRe.matcher(html)
    while (m.find()) {
      val quote   = m.group(1)
      val target  = Option(m.group(2)).getOrElse("")
      val pfx     = if (prefix.endsWith("/")) prefix.dropRight(1) else prefix
      val tgt     = if (target.startsWith("/")) target else "/" + target
      val newHref = s"href=${quote}${pfx}${tgt}${quote}"
      m.appendReplacement(sb, Matcher.quoteReplacement(newHref))
    }
    m.appendTail(sb)
    sb.toString
  }

  private def stripLengthAndEncoding(headers: Map[String, String]): Map[String, String] =
    headers.filterNot { case (k, _) =>
      k.equalsIgnoreCase("Content-Length") || k.equalsIgnoreCase("Content-Encoding")
    }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val conf = ctx
      .cachedConfig(internalName)(RegexBodyRewriterConfig.format)
      .getOrElse(RegexBodyRewriterConfig())

    val ctHeader = ctx.otoroshiResponse.contentType
    val encoding = ctx.otoroshiResponse.contentEncoding.map(_.toLowerCase)

    val targeted = ctHeader.exists(ct => conf.contentTypes.exists(t => ct.toLowerCase.contains(t.toLowerCase)))

    // skip if not targeted content-type or compressed without support
    if (!targeted || encoding.exists(enc => enc == "gzip" || enc == "br" || enc == "deflate")) {
      Right(ctx.otoroshiResponse).vfuture
    } else {
      val charset = detectCharset(ctHeader, conf.charsetFallback.getOrElse("UTF-8"))
      val limited = conf.maxBodySize
        .map { max =>
          ctx.otoroshiResponse.body.limitWeighted(max)(_.size)
        }
        .getOrElse(ctx.otoroshiResponse.body)

      limited.runFold(ByteString.empty)(_ ++ _).map { bs =>
        val in     = bs.decodeString(charset)
        val afterR = applyRegexReplacementRules(in, conf.rules)
        val out    = conf.autoHrefPrefix.filter(_.nonEmpty).map(p => autoPrefixHref(afterR, p)).getOrElse(afterR)
        val bytes  = ByteString(out, charset)

        val newHeaders = stripLengthAndEncoding(ctx.otoroshiResponse.headers) ++ Map(
          "Content-Length" -> bytes.length.toString
        )

        Right(
          ctx.otoroshiResponse.copy(
            headers = newHeaders,
            body = Source.single(bytes)
          )
        )
      }
    }
  }
}

class RegexRequestBodyRewriter extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = false
  override def noJsForm: Boolean                 = true

  override def name: String                                = "Regex request body rewriter"
  override def description: Option[String]                 = Some("Rewrites the HTTP request body using a set of regex rules")
  override def documentation: Option[String]               = Some("""### Examples
      |
      |1) Rewrite a domain to another
      |
      |{
      |  "plugin": "cp:otoroshi.next.plugins.RegexResponseBodyRewriter",
      |  "enabled": true,
      |  "config": {
      |    "content_types": ["text/html"],
      |    "rules": [
      |      { "pattern": "(?i)https?://example\.com", "replacement": "https://public.example.com" }
      |    ],
      |    "max_body_size": 1048576
      |  }
      |}
      |
      |
      |2) Rewrite paths while keeping captured groups
      |
      |{
      |  "plugin": "cp:otoroshi.next.plugins.RegexResponseBodyRewriter",
      |  "enabled": true,
      |  "config": {
      |    "content_types": ["text/html", "text/plain"],
      |    "rules": [
      |      { "pattern": "href=([\"'])/v1/(.+?)\1", "replacement": "href=$1/v2/$2$1", "flags": "i" }
      |    ]
      |  }
      |}
      |
      |
      |Note: replacement supports backrefs $1, $2, ...
      |Remember to properly escape backslashes in JSON.
      |""".stripMargin)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(
    RegexBodyRewriterConfig()
  )
  override def configFlow: Seq[String]                     = RegexBodyRewriterConfig.configFlow.filterNot(_ == "auto_href_prefix")
  override def configSchema: Option[JsObject]              = RegexBodyRewriterConfig.configSchema

  private def flagsBits(flags: Option[String]): Int = {
    var bits = Pattern.UNICODE_CASE
    flags.getOrElse("").foreach {
      case 'i' | 'I' => bits |= Pattern.CASE_INSENSITIVE
      case 'm' | 'M' => bits |= Pattern.MULTILINE
      case 's' | 'S' => bits |= Pattern.DOTALL
      case 'u' | 'U' => bits |= Pattern.UNICODE_CASE
      case _         => // ignore
    }
    bits
  }

  private def detectCharset(contentTypeHeader: Option[String], fallback: String): Charset = {
    val fromHeader = contentTypeHeader
      .flatMap { ct =>
        val idx = ct.toLowerCase.indexOf("charset=")
        if (idx > -1) Some(ct.substring(idx + 8).trim.replace("\"", "").replace("'", "")) else None
      }
      .flatMap { name => Try(Charset.forName(name)).toOption }
    fromHeader.getOrElse(Try(Charset.forName(fallback)).getOrElse(StandardCharsets.UTF_8))
  }

  private def applyRegexReplacementRules(input: String, rules: Seq[RegexReplacementRule]): String = {
    rules.foldLeft(input) { case (acc, rule) =>
      val p: Pattern = Pattern.compile(rule.pattern, flagsBits(rule.flags))
      val m: Matcher = p.matcher(acc)
      // IMPORTANT: do not quote replacement on purpose to allow $1, $2, ... backrefs
      m.replaceAll(rule.replacement)
    }
  }

  private def autoPrefixHref(html: String, prefix: String): String = {
    // negative lookahead to skip absolute urls and anchors/protocols
    val hrefRe = Pattern.compile(
      """(?i)\bhref\s*=\s*(['\"])\s*(?!https?://|/|#|mailto:|tel:|javascript:)([^'\"#?]+(?:\?[^'\"]*)?)\1"""
    )
    val sb     = new StringBuffer()
    val m      = hrefRe.matcher(html)
    while (m.find()) {
      val quote   = m.group(1)
      val target  = Option(m.group(2)).getOrElse("")
      val pfx     = if (prefix.endsWith("/")) prefix.dropRight(1) else prefix
      val tgt     = if (target.startsWith("/")) target else "/" + target
      val newHref = s"href=${quote}${pfx}${tgt}${quote}"
      m.appendReplacement(sb, Matcher.quoteReplacement(newHref))
    }
    m.appendTail(sb)
    sb.toString
  }

  private def stripLengthAndEncoding(headers: Map[String, String]): Map[String, String] =
    headers.filterNot { case (k, _) =>
      k.equalsIgnoreCase("Content-Length") || k.equalsIgnoreCase("Content-Encoding")
    }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val conf = ctx
      .cachedConfig(internalName)(RegexBodyRewriterConfig.format)
      .getOrElse(RegexBodyRewriterConfig())

    val ctHeader = ctx.otoroshiRequest.contentType
    val encoding = ctx.otoroshiRequest.contentEncoding.map(_.toLowerCase)

    val targeted = ctHeader.exists(ct => conf.contentTypes.exists(t => ct.toLowerCase.contains(t.toLowerCase)))

    // skip if not targeted content-type or compressed without support
    if (!targeted || encoding.exists(enc => enc == "gzip" || enc == "br" || enc == "deflate")) {
      Right(ctx.otoroshiRequest).vfuture
    } else {
      val charset = detectCharset(ctHeader, conf.charsetFallback.getOrElse("UTF-8"))
      val limited = conf.maxBodySize
        .map { max =>
          ctx.otoroshiRequest.body.limitWeighted(max)(_.size)
        }
        .getOrElse(ctx.otoroshiRequest.body)

      limited.runFold(ByteString.empty)(_ ++ _).map { bs =>
        val in     = bs.decodeString(charset)
        val afterR = applyRegexReplacementRules(in, conf.rules)
        val out    = conf.autoHrefPrefix.filter(_.nonEmpty).map(p => autoPrefixHref(afterR, p)).getOrElse(afterR)
        val bytes  = ByteString(out, charset)

        val newHeaders = stripLengthAndEncoding(ctx.otoroshiRequest.headers) ++ Map(
          "Content-Length" -> bytes.length.toString
        )

        Right(
          ctx.otoroshiRequest.copy(
            headers = newHeaders,
            body = Source.single(bytes)
          )
        )
      }
    }
  }
}

case class RegexHeaderReplacementRule(
    name: String,
    pattern: String,
    replacement: String,
    flags: Option[String] = None
)                                 {
  def json: JsValue = RegexHeaderReplacementRule.format.writes(this)
}
object RegexHeaderReplacementRule {
  val format: Format[RegexHeaderReplacementRule] = new Format[RegexHeaderReplacementRule] {
    override def reads(json: JsValue): JsResult[RegexHeaderReplacementRule] = Try {
      RegexHeaderReplacementRule(
        name = json.select("name").asString,
        pattern = json.select("pattern").asString,
        replacement = json.select("replacement").asString,
        flags = json.select("flags").asOptString
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }

    override def writes(o: RegexHeaderReplacementRule): JsValue = Json.obj(
      "name"        -> o.name,
      "pattern"     -> o.pattern,
      "replacement" -> o.replacement,
      "flags"       -> o.flags
    )
  }
}

case class RegexHeadersRewriterConfig(
    rules: Seq[RegexHeaderReplacementRule] = Seq.empty
) extends NgPluginConfig {
  def json: JsValue = RegexHeadersRewriterConfig.format.writes(this)
}
object RegexHeadersRewriterConfig {
  val configFlow: Seq[String] = Seq(
    "rules"
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "rules" -> Json.obj(
        "type"   -> "array",
        "label"  -> "Regex rules",
        "array"  -> true,
        "format" -> "form",
        "schema" -> Json.obj(
          "name"        -> Json.obj("type" -> "string", "label" -> "Header name"),
          "pattern"     -> Json
            .obj("type" -> "string", "label" -> "Pattern", "placeholder" -> "href=([\\\"'])/v1/(.+?)\\1"),
          "replacement" -> Json.obj("type" -> "string", "label" -> "Replacement", "placeholder" -> "href=$1/v2/$2$1"),
          "flags"       -> Json.obj("type" -> "string", "label" -> "Flags (imsu)", "placeholder" -> "i")
        ),
        "flow"   -> Json.arr("pattern", "replacement", "flags")
      )
    )
  )

  implicit val format: Format[RegexHeadersRewriterConfig] = new Format[RegexHeadersRewriterConfig] {
    override def reads(json: JsValue): JsResult[RegexHeadersRewriterConfig] = Try {
      RegexHeadersRewriterConfig(
        rules = json
          .select("rules")
          .asOpt[Seq[JsObject]]
          .map(seq => seq.flatMap(r => RegexHeaderReplacementRule.format.reads(r).asOpt))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: RegexHeadersRewriterConfig): JsValue             = Json.obj(
      "rules" -> JsArray(o.rules.map(_.json))
    )
  }
}

class RegexRequestHeadersRewriter extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = false
  override def noJsForm: Boolean                 = true

  override def name: String                                = "Regex request headers rewriter"
  override def description: Option[String]                 = Some("Rewrites the HTTP request headers using a set of regex rules")
  override def defaultConfigObject: Option[NgPluginConfig] = Some(
    RegexHeadersRewriterConfig()
  )
  override def configFlow: Seq[String]                     = RegexHeadersRewriterConfig.configFlow
  override def configSchema: Option[JsObject]              = RegexHeadersRewriterConfig.configSchema

  private def flagsBits(flags: Option[String]): Int = {
    var bits = Pattern.UNICODE_CASE
    flags.getOrElse("").foreach {
      case 'i' | 'I' => bits |= Pattern.CASE_INSENSITIVE
      case 'm' | 'M' => bits |= Pattern.MULTILINE
      case 's' | 'S' => bits |= Pattern.DOTALL
      case 'u' | 'U' => bits |= Pattern.UNICODE_CASE
      case _         => // ignore
    }
    bits
  }

  private def applyRegexReplacementRules(
      originalHeaders: Map[String, String],
      rules: Seq[RegexHeaderReplacementRule],
      attrs: TypedMap
  )(implicit env: Env): Map[String, String] = {
    var headers = Map.empty[String, String] ++ originalHeaders
    rules.foreach { _rule =>
      val rule = RegexHeaderReplacementRule.format.reads(_rule.json.stringify.evaluateEl(attrs).parseJson).get
      headers.getIgnoreCase(rule.name).foreach { headerValue =>
        val p: Pattern     = Pattern.compile(rule.pattern, flagsBits(rule.flags))
        val m: Matcher     = p.matcher(headerValue)
        val newHeaderValue = m.replaceAll(rule.replacement)
        headers += (rule.name -> newHeaderValue)
      }
    }
    headers
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val conf       = ctx
      .cachedConfig(internalName)(RegexHeadersRewriterConfig.format)
      .getOrElse(RegexHeadersRewriterConfig())
    val newHeaders = applyRegexReplacementRules(ctx.otoroshiRequest.headers, conf.rules, ctx.attrs)
    ctx.otoroshiRequest
      .copy(
        headers = newHeaders
      )
      .rightf
  }
}

class RegexResponseHeadersRewriter extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = false
  override def transformsResponse: Boolean       = true
  override def noJsForm: Boolean                 = true

  override def name: String                                = "Regex response headers rewriter"
  override def description: Option[String]                 = Some("Rewrites the HTTP response headers using a set of regex rules")
  override def defaultConfigObject: Option[NgPluginConfig] = Some(
    RegexHeadersRewriterConfig()
  )
  override def configFlow: Seq[String]                     = RegexHeadersRewriterConfig.configFlow
  override def configSchema: Option[JsObject]              = RegexHeadersRewriterConfig.configSchema

  private def flagsBits(flags: Option[String]): Int = {
    var bits = Pattern.UNICODE_CASE
    flags.getOrElse("").foreach {
      case 'i' | 'I' => bits |= Pattern.CASE_INSENSITIVE
      case 'm' | 'M' => bits |= Pattern.MULTILINE
      case 's' | 'S' => bits |= Pattern.DOTALL
      case 'u' | 'U' => bits |= Pattern.UNICODE_CASE
      case _         => // ignore
    }
    bits
  }

  private def applyRegexReplacementRules(
      originalHeaders: Map[String, String],
      rules: Seq[RegexHeaderReplacementRule],
      attrs: TypedMap
  )(implicit env: Env): Map[String, String] = {
    var headers = Map.empty[String, String] ++ originalHeaders
    rules.foreach { _rule =>
      val rule = RegexHeaderReplacementRule.format.reads(_rule.json.stringify.evaluateEl(attrs).parseJson).get
      headers.getIgnoreCase(rule.name).foreach { headerValue =>
        val p: Pattern     = Pattern.compile(rule.pattern, flagsBits(rule.flags))
        val m: Matcher     = p.matcher(headerValue)
        val newHeaderValue = m.replaceAll(rule.replacement)
        headers += (rule.name -> newHeaderValue)
      }
    }
    headers
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val conf       = ctx
      .cachedConfig(internalName)(RegexHeadersRewriterConfig.format)
      .getOrElse(RegexHeadersRewriterConfig())
    val newHeaders = applyRegexReplacementRules(ctx.otoroshiResponse.headers, conf.rules, ctx.attrs)
    ctx.otoroshiResponse
      .copy(
        headers = newHeaders
      )
      .rightf
  }
}
