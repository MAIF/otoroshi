package utils

import java.util.zip.Deflater

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream._
import akka.util.ByteString
import play.api.Logger
import play.api.http._
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object GzipConfig {

  lazy val logger = Logger("otoroshi-gzip-config")


  val _fmt: Format[GzipConfig] = new Format[GzipConfig] {
    override def reads(json: JsValue): JsResult[GzipConfig] =
      Try {
        GzipConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          whiteList = (json \ "whiteList").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          blackList = (json \ "blackList").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          bufferSize = (json \ "bufferSize").asOpt[Int].getOrElse(8192),
          chunkedThreshold = (json \ "chunkedThreshold").asOpt[Int].getOrElse(102400),
          compressionLevel = (json \ "compressionLevel").asOpt[Int].getOrElse(5)
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading ServiceDescriptor", t)
          JsError(t.getMessage)
      } get

    override def writes(o: GzipConfig): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "excludedPatterns" -> o.excludedPatterns,
      "whiteList" -> o.whiteList,
      "blackList" -> o.blackList,
      "bufferSize" -> o.bufferSize,
      "chunkedThreshold" -> o.chunkedThreshold,
      "compressionLevel" -> o.compressionLevel,
    )
  }
  def toJson(value: GzipConfig): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): GzipConfig =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[GzipConfig] = _fmt.reads(value)
}

case class GzipConfig(
  enabled: Boolean = false,
  excludedPatterns: Seq[String] = Seq.empty[String],
  whiteList: Seq[String] = Seq("text/*", "application/javascript", "application/json"),
  blackList: Seq[String] = Seq.empty[String],
  bufferSize: Int = 8192,
  chunkedThreshold: Int = 102400,
  compressionLevel: Int = 5
) {

  def asJson: JsValue = GzipConfig._fmt.writes(this)

  import play.api.http.HeaderNames._
  import utils.RequestImplicits._

  private def createGzipFlow: Flow[ByteString, ByteString, _] = GzipFlow.gzip(bufferSize, compressionLevel)

  def handleResult(request: RequestHeader, result: Result)(implicit ec: ExecutionContext, mat: Materializer): Future[Result] = {
    implicit val ec = mat.executionContext

    if (enabled && (!excludedPatterns.exists(p => utils.RegexPool.regex(p).matches(request.relativeUri)))) {
      if (shouldCompress(result) && shouldGzip(request, result)) {

        val header = result.header.copy(headers = setupHeader(result.header))

        result.body match {

          case HttpEntity.Strict(data, contentType) =>
            compressStrictEntity(Source.single(data), contentType)
              .map(entity => result.copy(header = header, body = entity))

          case entity@HttpEntity.Streamed(_, Some(contentLength), contentType)
            if contentLength <= chunkedThreshold =>
            // It's below the chunked threshold, so buffer then compress and send
            compressStrictEntity(entity.data, contentType)
              .map(strictEntity => result.copy(header = header, body = strictEntity))

          case HttpEntity.Streamed(data, _, contentType) if request.version == HttpProtocol.HTTP_1_0 =>
            // It's above the chunked threshold, but we can't chunk it because we're using HTTP 1.0.
            // Instead, we use a close delimited body (ie, regular body with no content length)
            val gzipped = data.via(createGzipFlow)
            FastFuture.successful(
              result.copy(header = header, body = HttpEntity.Streamed(gzipped, None, contentType))
            )

          case HttpEntity.Streamed(data, _, contentType) =>
            // It's above the chunked threshold, compress through the gzip flow, and send as chunked
            val gzipped = data.via(createGzipFlow).map(d => HttpChunk.Chunk(d))
            FastFuture.successful(
              result.copy(header = header, body = HttpEntity.Chunked(gzipped, contentType))
            )

          case HttpEntity.Chunked(chunks, contentType) =>
            val gzipFlow = Flow.fromGraph(GraphDSL.create[FlowShape[HttpChunk, HttpChunk]]() { implicit builder =>
              import GraphDSL.Implicits._

              val extractChunks = Flow[HttpChunk].collect { case HttpChunk.Chunk(data) => data }
              val createChunks = Flow[ByteString].map[HttpChunk](HttpChunk.Chunk.apply)
              val filterLastChunk = Flow[HttpChunk]
                .filter(_.isInstanceOf[HttpChunk.LastChunk])
                // Since we're doing a merge by concatenating, the filter last chunk won't receive demand until the gzip
                // flow is finished. But the broadcast won't start broadcasting until both flows start demanding. So we
                // put a buffer of one in to ensure the filter last chunk flow demands from the broadcast.
                .buffer(1, OverflowStrategy.backpressure)

              val broadcast = builder.add(Broadcast[HttpChunk](2))
              val concat = builder.add(Concat[HttpChunk]())

              // Broadcast the stream through two separate flows, one that collects chunks and turns them into
              // ByteStrings, sends those ByteStrings through the Gzip flow, and then turns them back into chunks,
              // the other that just allows the last chunk through. Then concat those two flows together.
              broadcast.out(0) ~> extractChunks ~> createGzipFlow ~> createChunks ~> concat.in(0)
              broadcast.out(1) ~> filterLastChunk ~> concat.in(1)

              new FlowShape(broadcast.in, concat.out)
            })

            FastFuture.successful(
              result.copy(header = header, body = HttpEntity.Chunked(chunks.via(gzipFlow), contentType))
            )
        }
      } else {
        FastFuture.successful(result)
      }
    } else {
      FastFuture.successful(result)
    }
  }

  private def compressStrictEntity(source: Source[ByteString, Any], contentType: Option[String])(
    implicit ec: ExecutionContext, mat: Materializer
  ) = {
    val compressed = source.via(createGzipFlow).runFold(ByteString.empty)(_ ++ _)
    compressed.map(data => HttpEntity.Strict(data, contentType))
  }

  private def mayCompress(request: RequestHeader) =
    request.method != "HEAD" && gzipIsAcceptedAndPreferredBy(request)

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

  private def gzipIsAcceptedAndPreferredBy(request: RequestHeader) = {
    val codings                        = acceptHeader(request.headers, ACCEPT_ENCODING)
    def explicitQValue(coding: String) = codings.collectFirst { case (q, c) if c.equalsIgnoreCase(coding) => q }
    def defaultQValue(coding: String)  = if (coding == "identity") 0.001d else 0d
    def qvalue(coding: String)         = explicitQValue(coding).orElse(explicitQValue("*")).getOrElse(defaultQValue(coding))

    qvalue("gzip") > 0d && qvalue("gzip") >= qvalue("identity")
  }

  private def shouldCompress(result: Result) =
    isAllowedContent(result.header) &&
      isNotAlreadyCompressed(result.header) &&
      !result.body.isKnownEmpty

  private def isAllowedContent(header: ResponseHeader) = header.status != Status.NO_CONTENT && header.status != Status.NOT_MODIFIED

  private def isNotAlreadyCompressed(header: ResponseHeader) = header.headers.get(CONTENT_ENCODING).isEmpty

  private def varyWith(rh: ResponseHeader, headerValues: String*): (String, String) = {
    val newValue = rh.headers.get(VARY) match {
      case Some(existing) if existing.nonEmpty =>
        val existingSet: Set[String] = existing.split(",").map(_.trim.toLowerCase)(collection.breakOut)
        val newValuesToAdd = headerValues.filterNot(v => existingSet.contains(v.trim.toLowerCase))
        s"$existing${newValuesToAdd.map(v => s",$v").mkString}"
      case _ =>
        headerValues.mkString(",")
    }
    VARY -> newValue
  }

  private def setupHeader(rh: ResponseHeader): Map[String, String] = {
    rh.headers + (CONTENT_ENCODING -> "gzip") + varyWith(rh, ACCEPT_ENCODING)
  }

  private def parseConfigMediaTypes(types: Seq[String]): Seq[MediaType] = {
    val mediaTypes = types.flatMap {
      case "*" => Some(MediaType("*", "*", Seq.empty))
      case MediaType.parse(mediaType) => Some(mediaType)
      case invalid =>
        GzipConfig. logger.error (s"Failed to parse the configured MediaType mask '$invalid'")
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

  private lazy val whiteListParsed = parseConfigMediaTypes(whiteList)
  private lazy val blackListParsed = parseConfigMediaTypes(blackList)
  private def shouldGzip(req: RequestHeader, res: Result): Boolean = {
    if (whiteListParsed.isEmpty) {

      if (blackListParsed.isEmpty) {
        true // default case, both whitelist and blacklist are empty so we gzip it.
      } else {
        // The blacklist is defined, so we gzip the result if it's not blacklisted.
        res.body.contentType match {
          case Some(MediaType.parse(outgoing)) => blackListParsed.forall(mask => !matches(outgoing, mask))
          case _                               => true // Fail open (to gziping), since blacklists have a tendency to fail open.
        }
      }
    } else {
      // The whitelist is defined. We gzip the result IFF there is a matching whitelist entry.
      res.body.contentType match {
        case Some(MediaType.parse(outgoing)) => whiteListParsed.exists(mask => matches(outgoing, mask))
        case _                               => false // Fail closed (to not gziping), since whitelists are intentionally strict.
      }
    }
  }
}

object GzipFlow {

  def gzip(bufferSize: Int = 512, compressionLevel: Int = 5): Flow[ByteString, ByteString, _] = {
    Flow[ByteString].via(new Chunker(bufferSize)).via(Compression.gzip)
  }

  // http://doc.akka.io/docs/akka/2.4.14/scala/stream/stream-cookbook.html#Chunking_up_a_stream_of_ByteStrings_into_limited_size_ByteStrings
  private class Chunker(val chunkSize: Int) extends GraphStage[FlowShape[ByteString, ByteString]] {
    private val in = Inlet[ByteString]("Chunker.in")
    private val out = Outlet[ByteString]("Chunker.out")

    override val shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var buffer = ByteString.empty

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (isClosed(in)) emitChunk()
          else pull(in)
        }
      })
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          buffer ++= elem
          emitChunk()
        }

        override def onUpstreamFinish(): Unit = {
          if (buffer.isEmpty) completeStage()
          else {
            if (isAvailable(out)) emitChunk()
          }
        }
      })

      private def emitChunk(): Unit = {
        if (buffer.isEmpty) {
          if (isClosed(in)) completeStage()
          else pull(in)
        } else {
          val (chunk, nextBuffer) = buffer.splitAt(chunkSize)
          buffer = nextBuffer
          push(out, chunk)
        }
      }

    }
  }

}
