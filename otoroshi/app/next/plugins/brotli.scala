package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.nixxcode.jvmbrotli.common.BrotliLoader
import com.nixxcode.jvmbrotli.enc.Encoder
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.RegexPool
import otoroshi.utils.gzip.GzipConfig
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.http.HeaderNames.{ACCEPT_ENCODING, CONTENT_ENCODING, VARY}
import play.api.http.{MediaType, Status}
import play.api.libs.json._
import play.api.mvc.{Headers, RequestHeader, Result}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

case class NgBrotliConfig(
  whiteList: Seq[String] = Seq("text/*", "application/javascript", "application/json"),
  blackList: Seq[String] = Seq.empty[String],
  bufferSize: Int = 8192,
  chunkedThreshold: Int = 102400,
  compressionLevel: Int = 5
) extends NgPluginConfig {
  def json: JsValue = NgBrotliConfig.format.writes(this)
}

object NgBrotliConfig {
  val format: Format[NgBrotliConfig]                   = new Format[NgBrotliConfig] {
    override def reads(json: JsValue): JsResult[NgBrotliConfig] =
      Try {
        NgBrotliConfig(
          whiteList = (json \ "allowed_list").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          blackList = (json \ "blocked_list").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          bufferSize = (json \ "buffer_size").asOpt[Int].getOrElse(8192),
          chunkedThreshold = (json \ "chunked_threshold").asOpt[Int].getOrElse(102400),
          compressionLevel = (json \ "compression_level").asOpt[Int].getOrElse(5)
        )
      } match {
        case Success(entity) => JsSuccess(entity)
        case Failure(err)    => JsError(err.getMessage)
      }

    override def writes(o: NgBrotliConfig): JsValue =
      Json.obj(
        "allowed_list"      -> o.whiteList,
        "blocked_list"      -> o.blackList,
        "buffer_size"       -> o.bufferSize,
        "chunked_threshold" -> o.chunkedThreshold,
        "compression_level" -> o.compressionLevel
      )
  }
}

class BrotliResponseCompressor extends NgRequestTransformer {

  private val configReads: Reads[NgBrotliConfig] = NgBrotliConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Brotli compression"
  override def description: Option[String]                 = "This plugin can compress responses using brotli".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgGzipConfig().some

  override def transformResponseSync(
    ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(NgBrotliConfig())
    val request = ctx.request
    if (mayCompress(request) && shouldCompress(ctx.otoroshiResponse) && shouldBrotli(config, request, ctx.otoroshiResponse)) {
      BrotliLoader.isBrotliAvailable()
      val params = new Encoder.Parameters().setQuality(config.compressionLevel)
      val vary = varyWith(ctx.otoroshiResponse.headers, ACCEPT_ENCODING)
      ctx.otoroshiResponse.copy(
        headers = ctx.otoroshiResponse.headers - "Content-Length" ++ Map("Content-Encoding" -> "br", "Transfer-Encoding" -> "chunked", vary._1 -> vary._2),
        body = ctx.otoroshiResponse.body.map { bs =>
          ByteString.apply(Encoder.compress(bs.toArray, params))
        }
      ).right
    } else {
      ctx.otoroshiResponse.right
    }
  }

  private def mayCompress(request: RequestHeader) =
    request.method != "HEAD" && brotliIsAcceptedAndPreferredBy(request)

  private def acceptHeader(headers: Headers, headerName: String): Seq[(Double, String)] = {
    for {
      header <- headers.get(headerName).toList
      value0 <- header.split(',')
      value = value0.trim
    } yield {
      RequestHeader.qPattern.findFirstMatchIn(value) match {
        case Some(m) => (m.group(1).toDouble, m.before.toString)
        case None => (1.0, value) // “The default value is q=1.”
      }
    }
  }

  private def brotliIsAcceptedAndPreferredBy(request: RequestHeader) = {
    val codings = acceptHeader(request.headers, ACCEPT_ENCODING)

    def explicitQValue(coding: String) = codings.collectFirst { case (q, c) if c.equalsIgnoreCase(coding) => q }

    def defaultQValue(coding: String) = if (coding == "identity") 0.001d else 0d

    def qvalue(coding: String) = explicitQValue(coding).orElse(explicitQValue("*")).getOrElse(defaultQValue(coding))

    qvalue("br") > 0d && qvalue("br") >= qvalue("identity")
  }

  private def shouldCompress(result: NgPluginHttpResponse) =
    isAllowedContent(result) && isNotAlreadyCompressed(result)

  private def isAllowedContent(header: NgPluginHttpResponse) =
    header.status != Status.NO_CONTENT && header.status != Status.NOT_MODIFIED

  private def isNotAlreadyCompressed(header: NgPluginHttpResponse) = header.headers.getIgnoreCase(CONTENT_ENCODING).isEmpty

  private def varyWith(rh: Map[String, String], headerValues: String*): (String, String) = {
    val newValue = rh.getIgnoreCase(VARY) match {
      case Some(existing) if existing.nonEmpty =>
        val existingSet: Set[String] = existing.split(",").map(_.trim.toLowerCase)(collection.breakOut)
        val newValuesToAdd = headerValues.filterNot(v => existingSet.contains(v.trim.toLowerCase))
        s"$existing${newValuesToAdd.map(v => s",$v").mkString}"
      case _ =>
        headerValues.mkString(",")
    }
    VARY -> newValue
  }

  private def parseConfigMediaTypes(types: Seq[String]): Seq[MediaType] = {
    val mediaTypes = types.flatMap {
      case "*" => Some(MediaType("*", "*", Seq.empty))
      case MediaType.parse(mediaType) => Some(mediaType)
      case invalid =>
        GzipConfig.logger.error(s"Failed to parse the configured MediaType mask '$invalid'")
        None
    }
    mediaTypes.foreach {
      case MediaType("*", "*", _) =>
      case _ => () // the configured MediaType mask is valid
    }
    mediaTypes
  }

  private def matches(outgoing: MediaType, mask: MediaType): Boolean = {
    def capturedByMask(value: String, mask: String): Boolean = {
      mask == "*" || value.equalsIgnoreCase(mask)
    }

    capturedByMask(outgoing.mediaType, mask.mediaType) && capturedByMask(outgoing.mediaSubType, mask.mediaSubType)
  }

  private def shouldBrotli(config: NgBrotliConfig, req: RequestHeader, res: NgPluginHttpResponse): Boolean = {
    lazy val whiteListParsed = parseConfigMediaTypes(config.whiteList)
    lazy val blackListParsed = parseConfigMediaTypes(config.blackList)
    if (whiteListParsed.isEmpty) {

      if (blackListParsed.isEmpty) {
        true // default case, both whitelist and blacklist are empty so we brotli it.
      } else {
        // The blacklist is defined, so we brotli the result if it's not blacklisted.
        res.contentType match {
          case Some(MediaType.parse(outgoing)) => blackListParsed.forall(mask => !matches(outgoing, mask))
          case _ => true // Fail open (to brotling), since blacklists have a tendency to fail open.
        }
      }
    } else {
      // The whitelist is defined. We brotli the result IFF there is a matching whitelist entry.
      res.contentType match {
        case Some(MediaType.parse(outgoing)) => whiteListParsed.exists(mask => matches(outgoing, mask))
        case _ => false // Fail closed (to not brotling), since whitelists are intentionally strict.
      }
    }
  }
}

