package otoroshi.next.plugins

import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Materializer, Outlet}
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.{ByteString, ByteStringBuilder}
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.{Encoder, EncoderJNI}
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.utils.RegexPool
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.http.HeaderNames.{ACCEPT_ENCODING, CONTENT_ENCODING, CONTENT_LENGTH, TRANSFER_ENCODING, VARY}
import play.api.http.{MediaType, Status}
import play.api.libs.json.*
import play.api.mvc.{Headers, RequestHeader, Result}

import java.io.IOException
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
  val format: Format[NgBrotliConfig] = new Format[NgBrotliConfig] {
    override def reads(json: JsValue): JsResult[NgBrotliConfig] =
      Try {
        NgBrotliConfig(
          whiteList = (json \ "allowed_list").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq,
          blackList = (json \ "blocked_list").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq,
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

object BrotliSupport {

  val logger = Logger("otoroshi-plugins-brotli")

  val defaultBufferSize = 8192

  /** Whether the brotli native library could be loaded for the current os and architecture.
    *
    * brotli4j tries to load it once, when its loader class is initialized. That never throws, but a missing
    * or broken brotli4j jar would surface here as a LinkageError, that Try and NonFatal let through.
    */
  lazy val available: Boolean =
    try {
      if (Brotli4jLoader.isAvailable) true
      else {
        unavailable(Brotli4jLoader.getUnavailabilityCause)
        false
      }
    } catch {
      case t: Throwable =>
        unavailable(t)
        false
    }

  private def unavailable(cause: Throwable): Unit =
    logger.warn(
      s"the brotli native library cannot be loaded on ${sys.props("os.name")}/${sys.props("os.arch")}, the brotli compression plugin will send responses uncompressed",
      cause
    )

  def compressor(quality: Int, bufferSize: Int): Flow[ByteString, ByteString, ?] =
    Flow.fromGraph(
      new BrotliCompressorStage(
        quality = math.max(0, math.min(11, quality)),
        bufferSize = if (bufferSize > 0) bufferSize else defaultBufferSize
      )
    )
}

/** Compresses a whole body into a single brotli stream.
  *
  * Brotli streams cannot be concatenated: compressing each chunk on its own gives a body that decoders
  * reject, or silently cut after the first chunk as browsers do. Here every chunk goes through the same
  * encoder and is flushed, so a streamed response (sse, long polling, ...) keeps flowing to the client,
  * and the stream is only closed when the body completes.
  */
class BrotliCompressorStage(quality: Int, bufferSize: Int) extends GraphStage[FlowShape[ByteString, ByteString]] {

  private val in  = Inlet[ByteString]("BrotliCompressor.in")
  private val out = Outlet[ByteString]("BrotliCompressor.out")

  override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      private var encoder: EncoderJNI.Wrapper = null

      override def preStart(): Unit =
        encoder = native(new EncoderJNI.Wrapper(bufferSize, quality, -1, Encoder.Mode.GENERIC))

      override def onPush(): Unit = {
        val chunk = grab(in)
        if (chunk.isEmpty) pull(in)
        else {
          val compressed = native(encode(chunk, EncoderJNI.Operation.FLUSH))
          if (compressed.isEmpty) pull(in) else push(out, compressed)
        }
      }

      override def onPull(): Unit = pull(in)

      override def onUpstreamFinish(): Unit = {
        val last = native(encode(ByteString.empty, EncoderJNI.Operation.FINISH))
        release()
        if (last.isEmpty) completeStage() else emit(out, last, () => completeStage())
      }

      // no end of stream marker here: the client has to see a broken body, not a shorter one that decodes fine
      override def onUpstreamFailure(ex: Throwable): Unit = {
        release()
        failStage(ex)
      }

      // downstream cancellation or abrupt termination: the native encoder is never garbage collected
      override def postStop(): Unit = release()

      private def encode(data: ByteString, op: EncoderJNI.Operation): ByteString = {
        val builder   = ByteString.newBuilder
        var remaining = data
        while (remaining.nonEmpty) {
          val input  = encoder.getInputBuffer
          input.clear()
          val copied = remaining.copyToBuffer(input)
          remaining = remaining.drop(copied)
          run(EncoderJNI.Operation.PROCESS, copied, builder)
        }
        run(op, 0, builder)
        builder.result()
      }

      // same loop as brotli4j's own Encoder: push once, then collect the output until the input is consumed
      private def run(op: EncoderJNI.Operation, length: Int, builder: ByteStringBuilder): Unit = {
        encoder.push(op, length)
        while (encoder.hasMoreOutput || encoder.hasRemainingInput) {
          if (encoder.hasMoreOutput) builder.append(ByteString(encoder.pull()))
          else encoder.push(op, 0)
        }
      }

      private def release(): Unit =
        if (encoder != null) {
          val current = encoder
          encoder = null
          native(current.destroy())
        }

      // a LinkageError is fatal for pekko: thrown from a stage it shuts the whole actor system down.
      // As an IOException it only fails the response being compressed.
      private def native[A](f: => A): A =
        try f
        catch {
          case e: LinkageError => throw new IOException("brotli native call failed", e)
        }

      setHandlers(in, out, this)
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
  override def defaultConfigObject: Option[NgPluginConfig] = NgBrotliConfig().some

  // without the native library the response goes out untouched: the headers are sent before the body is
  // compressed, so failing later would leave the client with a `Content-Encoding: br` it cannot decode
  protected def brotliAvailable: Boolean = BrotliSupport.available

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val config  = ctx.cachedConfig(internalName)(configReads).getOrElse(NgBrotliConfig())
    val request = ctx.request
    if (
      brotliAvailable && mayCompress(request) && shouldCompress(ctx.otoroshiResponse) && shouldBrotli(
        config,
        request,
        ctx.otoroshiResponse
      )
    ) {
      val vary = varyWith(ctx.otoroshiResponse.headers, ACCEPT_ENCODING)
      ctx.otoroshiResponse
        .copy(
          // header names come in any case from the backend, and the compressed length is not known upfront
          headers = ctx.otoroshiResponse.headers.filterNot { case (name, _) =>
            name.equalsIgnoreCase(CONTENT_LENGTH) || name.equalsIgnoreCase(TRANSFER_ENCODING) || name
              .equalsIgnoreCase(VARY)
          } ++ Map(
            CONTENT_ENCODING  -> "br",
            TRANSFER_ENCODING -> "chunked",
            vary._1           -> vary._2
          ),
          body = ctx.otoroshiResponse.body.via(BrotliSupport.compressor(config.compressionLevel, config.bufferSize))
        )
        .right
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
      value   = value0.trim
    } yield {
      RequestHeader.qPattern.findFirstMatchIn(value) match {
        case Some(m) => (m.group(1).toDouble, m.before.toString)
        case None    => (1.0, value) // “The default value is q=1.”
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

  private def isNotAlreadyCompressed(header: NgPluginHttpResponse) =
    header.headers.getIgnoreCase(CONTENT_ENCODING).isEmpty

  private def varyWith(rh: Map[String, String], headerValues: String*): (String, String) = {
    val newValue = rh.getIgnoreCase(VARY) match {
      case Some(existing) if existing.nonEmpty =>
        val existingSet: Set[String] = existing.split(",").map(_.trim.toLowerCase).toSet
        val newValuesToAdd           = headerValues.filterNot(v => existingSet.contains(v.trim.toLowerCase))
        s"$existing${newValuesToAdd.map(v => s",$v").mkString}"
      case _                                   =>
        headerValues.mkString(",")
    }
    VARY -> newValue
  }

  private def parseConfigMediaTypes(types: Seq[String]): Seq[MediaType] = {
    val mediaTypes = types.flatMap {
      case "*"                        => Some(MediaType("*", "*", Seq.empty))
      case MediaType.parse(mediaType) => Some(mediaType)
      case invalid                    =>
        BrotliSupport.logger.error(s"Failed to parse the configured MediaType mask '$invalid'")
        None
    }
    mediaTypes.foreach {
      case MediaType("*", "*", _) =>
      case _                      => () // the configured MediaType mask is valid
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
          case _                               => true // Fail open (to brotling), since blacklists have a tendency to fail open.
        }
      }
    } else {
      // The whitelist is defined. We brotli the result IFF there is a matching whitelist entry.
      res.contentType match {
        case Some(MediaType.parse(outgoing)) => whiteListParsed.exists(mask => matches(outgoing, mask))
        case _                               => false // Fail closed (to not brotling), since whitelists are intentionally strict.
      }
    }
  }
}
