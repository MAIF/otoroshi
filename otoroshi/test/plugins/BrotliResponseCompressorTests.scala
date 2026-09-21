package plugins

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.{Decoder, DecoderJNI}
import com.aayushatharva.brotli4j.encoder.Encoder
import com.microsoft.playwright.*
import functional.{PluginsTestSpecBase, TargetService}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.{`Content-Encoding`, HttpEncoding, HttpEncodings}
import org.apache.pekko.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, StatusCodes}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Seconds, Span}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgTarget}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{BrotliResponseCompressor, BrotliSupport, NgBrotliConfig}
import otoroshi.security.IdGenerator
import play.api.libs.json.*

import java.io.{BufferedReader, ByteArrayOutputStream, IOException, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPOutputStream
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.util.{Failure, Success, Try}

// The brotli compressor sends its headers before the body is compressed: a body that is broken, or cut
// after its first chunk, still comes with a 200 and `Content-Encoding: br`. So every test decodes what
// went over the wire and compares it with what the backend sent. The calls go through the jdk http client,
// that does not decode anything, and through chromium for what browsers make of it.
//
// The backends are local and stream their bodies in small chunks like a real backend does with a large
// response: a single chunk body is the one case the old per chunk compression got right.
class BrotliResponseCompressorTests(parent: PluginsTestSpecBase) {
  import parent.*

  private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  /** A local backend serving each path with its own content type, so one route (one origin for the
    * browser) can carry a page, a script, some json and an event stream.
    */
  private class Backend(handler: PartialFunction[String, HttpResponse]) {
    val port: Int       = TargetService.freePort
    private val binding = Http()
      .newServerAt("127.0.0.1", port)
      .bind(request =>
        Future.successful(
          handler.applyOrElse(request.uri.path.toString, (_: String) => HttpResponse(StatusCodes.NotFound))
        )
      )
      .futureValue
    def stop(): Unit    = binding.unbind().futureValue
  }

  private def withRoute[A](
      handler: PartialFunction[String, HttpResponse],
      plugin: String = NgPluginHelper.pluginId[BrotliResponseCompressor],
      config: NgBrotliConfig = NgBrotliConfig()
  )(f: String => A): A = {
    val backend = new Backend(handler)
    val route   = createRouteWithExternalTarget(
      plugins = Seq(NgPluginInstance(plugin = plugin, config = NgPluginInstanceConfig(config.json.as[JsObject]))),
      domain = Some(s"brotli-${IdGenerator.uuid}.oto.tools"),
      target = Some(NgTarget(id = "local.target", hostname = "127.0.0.1", port = backend.port, tls = false))
    ).futureValue
    try f(route.frontend.domains.head.domain)
    finally {
      deleteOtoroshiRoute(route).futureValue
      backend.stop()
    }
  }

  private def contentType(value: String): ContentType = ContentType.parse(value).toOption.get

  private def strict(ctype: String, body: ByteString): HttpResponse =
    HttpResponse(entity = HttpEntity(contentType(ctype), body))

  private def chunked(ctype: String, body: ByteString): HttpResponse =
    HttpResponse(entity = HttpEntity.Chunked.fromData(contentType(ctype), Source(body.grouped(8192).toList)))

  // compresses well, but not down to a handful of bytes like a repeated line would
  private def text(size: Int): ByteString = {
    val builder = ByteString.newBuilder
    var i       = 0
    while (builder.length < size) {
      builder.putBytes(s"line ${"%07d".format(i)} ${(i * 7919) % 104729} the quick brown fox jumps over the lazy dog\n".getBytes(UTF_8))
      i += 1
    }
    builder.result().take(size)
  }

  private def gzip(data: ByteString): ByteString = {
    val out = new ByteArrayOutputStream()
    val gz  = new GZIPOutputStream(out)
    gz.write(data.toArray)
    gz.close()
    ByteString(out.toByteArray)
  }

  private def request(domain: String, path: String, acceptEncoding: Option[String], method: String): JHttpRequest = {
    val builder = JHttpRequest
      .newBuilder(URI.create(s"http://$domain:$port$path"))
      .method(method, JHttpRequest.BodyPublishers.noBody())
    acceptEncoding.foreach(value => builder.header("Accept-Encoding", value))
    builder.build()
  }

  private def call(
      domain: String,
      path: String = "/",
      acceptEncoding: Option[String] = Some("br"),
      method: String = "GET"
  ): JHttpResponse[Array[Byte]] =
    client.send(request(domain, path, acceptEncoding, method), JHttpResponse.BodyHandlers.ofByteArray())

  private def header(response: JHttpResponse[?], name: String): Option[String] =
    response.headers().allValues(name).asScala.toList match {
      case Nil    => None
      case values => Some(values.mkString(", "))
    }

  private def decode(bytes: Array[Byte]): (DecoderJNI.Status, ByteString) = {
    Brotli4jLoader.ensureAvailability()
    // not even the end of stream marker: brotli4j cannot size a decoder for no input at all
    if (bytes.isEmpty) return (DecoderJNI.Status.NEEDS_MORE_INPUT, ByteString.empty)
    val result = Decoder.decompress(bytes)
    (result.getResultStatus, ByteString(Option(result.getDecompressedData).getOrElse(Array.emptyByteArray)))
  }

  // no mustBe on the bodies themselves: a failure would print hundreds of kilobytes
  private def mustDecodeTo(response: JHttpResponse[Array[Byte]], expected: ByteString): Unit = {
    header(response, "Content-Encoding") mustBe Some("br")
    val (status, decoded) = decode(response.body())
    status mustBe DecoderJNI.Status.DONE
    decoded.length mustBe expected.length
    (decoded == expected) mustBe true
  }

  private def mustBeUntouched(response: JHttpResponse[Array[Byte]], expected: ByteString): Unit = {
    header(response, "Content-Encoding") mustBe None
    response.body().length mustBe expected.length
    (ByteString(response.body()) == expected) mustBe true
  }

  def shipsEveryNative(): Unit = {
    // brotli4j picks its natives through maven os profiles: without the explicit dependencies, only the
    // one of the machine that resolved them would be there, and otoroshi.jar would only work on the ci arch
    val natives = Seq(
      "linux-x86_64/libbrotli.so",
      "linux-aarch64/libbrotli.so",
      "linux-armv7/libbrotli.so",
      "linux-riscv64/libbrotli.so",
      "osx-x86_64/libbrotli.dylib",
      "osx-aarch64/libbrotli.dylib"
    )
    natives.filter(path => classOf[BrotliResponseCompressor].getResource(s"/lib/$path") == null) mustBe Seq.empty
    BrotliSupport.available mustBe true
  }

  def compressesASmallBody(): Unit = {
    val body = ByteString(Json.stringify(Json.obj("message" -> "hello brotli")))
    withRoute { case _ => strict("application/json", body) } { domain =>
      val response = call(domain)
      response.statusCode() mustBe 200
      header(response, "Content-Length") mustBe None
      header(response, "Vary").exists(_.toLowerCase.contains("accept-encoding")) mustBe true
      mustDecodeTo(response, body)
    }
  }

  def keepsALargeChunkedBodyWhole(): Unit = {
    val body = text(512 * 1024)
    withRoute { case _ => chunked("text/plain; charset=UTF-8", body) } { domain =>
      val response = call(domain)
      response.statusCode() mustBe 200
      mustDecodeTo(response, body)
      // really compressed, not just labelled as such
      response.body().length must be < (body.length / 3)
    }
  }

  def closesTheStreamOfAnEmptyBody(): Unit = {
    withRoute { case _ => strict("text/plain; charset=UTF-8", ByteString.empty) } { domain =>
      val response = call(domain)
      response.statusCode() mustBe 200
      mustDecodeTo(response, ByteString.empty)
    }
  }

  def onlyCompressesWhenTheClientAcceptsBrotli(): Unit = {
    val body = text(64 * 1024)
    withRoute { case _ => chunked("text/plain; charset=UTF-8", body) } { domain =>
      Seq(None, Some("gzip, deflate"), Some("br;q=0"), Some("identity;q=1, br;q=0.5")).foreach { accept =>
        withClue(s"Accept-Encoding: $accept") {
          mustBeUntouched(call(domain, acceptEncoding = accept), body)
        }
      }
      Seq("br", "gzip, deflate, br, zstd", "*").foreach { accept =>
        withClue(s"Accept-Encoding: $accept") {
          mustDecodeTo(call(domain, acceptEncoding = Some(accept)), body)
        }
      }
      header(call(domain, method = "HEAD"), "Content-Encoding") mustBe None
    }
  }

  def leavesSomeResponsesAlone(): Unit = {
    // an encoding otoroshi's backend client does not decode (it decodes gzip, deflate, br and zstd)
    val encoded = gzip(text(64 * 1024))
    val image   = text(16 * 1024)
    withRoute {
      case "/encoded"    =>
        HttpResponse(
          headers = List(`Content-Encoding`(HttpEncoding.custom("compress"))),
          entity = HttpEntity(contentType("text/plain; charset=UTF-8"), encoded)
        )
      case "/image"      => chunked("image/png", image)
      case "/no-content" => HttpResponse(StatusCodes.NoContent)
    } { domain =>
      val alreadyEncoded = call(domain, "/encoded")
      header(alreadyEncoded, "Content-Encoding") mustBe Some("compress")
      (ByteString(alreadyEncoded.body()) == encoded) mustBe true
      // outside of the default allowed list
      mustBeUntouched(call(domain, "/image"), image)
      val noContent = call(domain, "/no-content")
      noContent.statusCode() mustBe 204
      header(noContent, "Content-Encoding") mustBe None
    }
    val json = ByteString(Json.stringify(Json.obj("message" -> "not compressed")))
    val page = text(16 * 1024)
    withRoute(
      {
        case "/json" => strict("application/json", json)
        case "/page" => chunked("text/html; charset=UTF-8", page)
      },
      config = NgBrotliConfig(whiteList = Seq.empty, blackList = Seq("application/json"))
    ) { domain =>
      mustBeUntouched(call(domain, "/json"), json)
      mustDecodeTo(call(domain, "/page"), page)
    }
  }

  def recompressesWhatComesCompressedFromTheBackend(): Unit = {
    // otoroshi's backend client decodes gzip and br bodies (br since brotli4j is on the classpath), the
    // plugin then sees plain text: the client must get it encoded once, never gzip or br wrapped in br
    val body = text(64 * 1024)
    withRoute {
      case "/gzip" =>
        HttpResponse(
          headers = List(`Content-Encoding`(HttpEncodings.gzip)),
          entity = HttpEntity(contentType("text/plain; charset=UTF-8"), gzip(body))
        )
      case "/br"   =>
        HttpResponse(
          headers = List(`Content-Encoding`(HttpEncoding.custom("br"))),
          entity = HttpEntity(contentType("text/plain; charset=UTF-8"), ByteString(Encoder.compress(body.toArray)))
        )
    } { domain =>
      Seq("/gzip", "/br").foreach { path =>
        withClue(s"backend encoding $path") {
          mustDecodeTo(call(domain, path), body)
          mustBeUntouched(call(domain, path, acceptEncoding = None), body)
        }
      }
    }
  }

  def passesThroughWithoutTheNativeLibrary(): Unit = {
    val body = text(64 * 1024)
    withRoute(
      { case _ => chunked("text/plain; charset=UTF-8", body) },
      plugin = NgPluginHelper.pluginId[UnavailableBrotliResponseCompressor]
    ) { domain =>
      val response = call(domain)
      response.statusCode() mustBe 200
      mustBeUntouched(response, body)
    }
  }

  def neverTurnsABackendFailureIntoAValidBody(): Unit = {
    val chunks = text(256 * 1024).grouped(8192).toList.take(8)
    withRoute {
      case "/broken" =>
        HttpResponse(entity =
          HttpEntity.Chunked.fromData(
            contentType("text/plain; charset=UTF-8"),
            Source(chunks).concat(Source.failed(new RuntimeException("the backend went away")))
          )
        )
      case "/fine"   => chunked("text/plain; charset=UTF-8", text(64 * 1024))
    } { domain =>
      Try(call(domain, "/broken")) match {
        case Failure(_: IOException) => () // the connection was cut, as it should
        case Failure(e)              => fail(e)
        case Success(response)       =>
          // whatever came back, it must not decode as a complete, shorter, body
          decode(response.body())._1 must not be DecoderJNI.Status.DONE
      }
      otoroshiComponents.system.whenTerminated.isCompleted mustBe false
      mustDecodeTo(call(domain, "/fine"), text(64 * 1024))
    }
  }

  def keepsTheActorSystemAliveUnderLoad(): Unit = {
    val body = text(256 * 1024)
    withRoute { case _ => chunked("text/plain; charset=UTF-8", body) } { domain =>
      val responses = Future
        .sequence(
          (1 to 32).map(_ =>
            client
              .sendAsync(request(domain, "/", Some("br"), "GET"), JHttpResponse.BodyHandlers.ofByteArray())
              .asScala
          )
        )
        .futureValue(Timeout(Span(60, Seconds)))
      responses.foreach(response => mustDecodeTo(response, body))
      otoroshiComponents.system.whenTerminated.isCompleted mustBe false
    }
  }

  /** Decodes a brotli stream as it arrives, like a browser does: whatever the bytes received so far allow. */
  private class StreamingDecoder {
    private val decoder = new DecoderJNI.Wrapper(64 * 1024)

    def status: DecoderJNI.Status = decoder.getStatus

    def feed(bytes: Array[Byte], length: Int): ByteString = {
      val out    = ByteString.newBuilder
      var offset = 0
      while (offset < length) {
        val input = decoder.getInputBuffer
        input.clear()
        val size  = math.min(input.remaining(), length - offset)
        input.put(bytes, offset, size)
        offset += size
        decoder.push(size)
        var more  = true
        while (more) {
          decoder.getStatus match {
            case DecoderJNI.Status.OK                                        => decoder.push(0)
            case DecoderJNI.Status.NEEDS_MORE_OUTPUT                         => out.append(ByteString(decoder.pull()))
            case DecoderJNI.Status.NEEDS_MORE_INPUT | DecoderJNI.Status.DONE =>
              if (decoder.hasOutput) out.append(ByteString(decoder.pull())) else more = false
            case status                                                      => fail(s"brotli decoding failed: $status")
          }
        }
      }
      out.result()
    }

    def close(): Unit = decoder.destroy()
  }

  def flushesEveryChunk(): Unit = {
    val lines = (0 until 6).map(i => s"event-$i\n")
    withRoute { case _ =>
      HttpResponse(entity =
        HttpEntity.Chunked.fromData(
          contentType("text/plain; charset=UTF-8"),
          Source(lines.toList).throttle(1, 500.millis).map(line => ByteString(line))
        )
      )
    } { domain =>
      val start    = System.nanoTime()
      def elapsed  = (System.nanoTime() - start) / 1000000L
      val response = client.send(request(domain, "/", Some("br"), "GET"), JHttpResponse.BodyHandlers.ofInputStream())
      header(response, "Content-Encoding") mustBe Some("br")
      val input    = response.body()
      val decoder  = new StreamingDecoder()
      val buffer   = new Array[Byte](16 * 1024)
      val received = new StringBuilder()
      var firstAt  = -1L
      var read     = input.read(buffer)
      while (read != -1) {
        received.append(decoder.feed(buffer, read).utf8String)
        if (firstAt < 0 && received.toString.contains(lines.head)) firstAt = elapsed
        read = input.read(buffer)
      }
      val lastAt   = elapsed
      decoder.status mustBe DecoderJNI.Status.DONE
      decoder.close()
      received.toString mustBe lines.mkString
      // the backend needs 2.5 seconds to send everything: the first line must not wait for the last one
      firstAt must (be >= 0L and be < 1500L)
      (lastAt - firstAt) must be >= 1500L
    }
  }

  // browsers ------------------------------------------------------------------------------------------

  // chromium only asks for br over https (or on localhost): pages are loaded from the https listener, that
  // serves the auto-generated *.oto.tools certificate the browser is told to accept
  private def withBrowser[A](f: Page => A): A = {
    val playwright = Playwright.create()
    try {
      val browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
      val context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))
      f(context.newPage())
    } finally playwright.close()
  }

  // the https listener has no certificate until the initial certificates job ran, a few seconds after startup
  private def open(page: Page, domain: String): Response = {
    val url      = s"https://$domain:$httpsPort/"
    val deadline = System.currentTimeMillis() + 30000L
    var response = Option.empty[Response]
    while (response.isEmpty) {
      try response = Some(page.navigate(url))
      catch {
        case e: PlaywrightException =>
          if (System.currentTimeMillis() > deadline) throw e
          Thread.sleep(500)
      }
    }
    response.get
  }

  private val emptyPage = ByteString("<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>")

  private def evaluateInt(page: Page, expression: String): Int =
    page.evaluate(expression).asInstanceOf[Number].intValue()

  // the response whose url ends with path, among the ones triggered by action
  private def waitForResponse(page: Page, path: String)(action: => Any): Response = {
    val predicate: java.util.function.Predicate[Response] = response => response.url().endsWith(path)
    val callback: Runnable                                = () => action
    page.waitForResponse(predicate, callback)
  }

  def browserGetsALargePageWhole(): Unit = {
    val content = text(600 * 1024)
    val html    = ByteString(
      "<!doctype html><html><head><meta charset=\"utf-8\"><title>brotli</title></head><body><pre id=\"content\">"
    ) ++ content ++ ByteString("</pre><div id=\"end\">end of page</div></body></html>")
    withRoute { case "/" => chunked("text/html; charset=UTF-8", html) } { domain =>
      withBrowser { page =>
        val response = open(page, domain)
        response.status() mustBe 200
        response.headerValue("content-encoding") mustBe "br"
        val received = ByteString(response.body())
        received.length mustBe html.length
        (received == html) mustBe true
        page.textContent("#end") mustBe "end of page"
        evaluateInt(page, "() => document.getElementById('content').textContent.length") mustBe content.length
      }
    }
  }

  def browserParsesALargeJson(): Unit = {
    val items = JsArray(
      (0 until 8000).map(i =>
        Json.obj("id" -> i, "name" -> s"item-$i", "description" -> s"the quick brown fox jumps over the lazy dog $i")
      )
    )
    withRoute {
      case "/"           => strict("text/html; charset=UTF-8", emptyPage)
      case "/items.json" => chunked("application/json", ByteString(Json.stringify(items)))
    } { domain =>
      withBrowser { page =>
        open(page, domain)
        val result = page
          .evaluate("""async () => {
            |  const response = await fetch('/items.json');
            |  const items = await response.json();
            |  return { encoding: response.headers.get('content-encoding'), count: items.length, last: items[items.length - 1].name };
            |}""".stripMargin)
          .asInstanceOf[java.util.Map[String, Any]]
        result.get("encoding") mustBe "br"
        result.get("count") mustBe 8000
        result.get("last") mustBe "item-7999"
      }
    }
  }

  def browserRunsALargeScript(): Unit = {
    val script = ByteString(
      s"window.__items = [${(0 until 20000).map(i => s"\"item-$i\"").mkString(",")}];\nwindow.__loaded = window.__items.length;\n"
    )
    val html   = ByteString(
      "<!doctype html><html><head><meta charset=\"utf-8\"><script src=\"/app.js\"></script></head><body></body></html>"
    )
    withRoute {
      case "/"       => strict("text/html; charset=UTF-8", html)
      case "/app.js" => chunked("application/javascript; charset=UTF-8", script)
    } { domain =>
      withBrowser { page =>
        open(page, domain)
        val scriptResponse = waitForResponse(page, "/app.js")(page.reload())
        scriptResponse.headerValue("content-encoding") mustBe "br"
        // a truncated script is a syntax error: it never gets to the last line
        page.waitForFunction("() => window.__loaded !== undefined", null, new Page.WaitForFunctionOptions().setTimeout(10000))
        evaluateInt(page, "() => window.__loaded") mustBe 20000
      }
    }
  }

  def browserReceivesServerSentEventsAsTheyCome(): Unit = {
    withRoute {
      case "/"       => strict("text/html; charset=UTF-8", emptyPage)
      case "/events" =>
        HttpResponse(entity =
          HttpEntity.Chunked.fromData(
            contentType("text/event-stream"),
            Source(0 until 6).throttle(1, 400.millis).map(i => ByteString(s"data: event-$i\n\n"))
          )
        )
    } { domain =>
      withBrowser { page =>
        open(page, domain)
        val eventsResponse = waitForResponse(page, "/events") {
          page.evaluate("""() => {
            |  window.__events = [];
            |  window.__startedAt = performance.now();
            |  const source = new EventSource('/events');
            |  source.onmessage = (e) => {
            |    window.__events.push({ data: e.data, at: performance.now() - window.__startedAt });
            |    if (e.data === 'event-5') source.close();
            |  };
            |}""".stripMargin)
        }
        eventsResponse.headerValue("content-encoding") mustBe "br"
        page.waitForFunction("() => window.__events.length === 6", null, new Page.WaitForFunctionOptions().setTimeout(15000))
        page.evaluate("() => window.__events.map(e => e.data).join(',')") mustBe (0 until 6).map(i => s"event-$i").mkString(",")
        // sent 400 ms apart: flushed chunk by chunk, the first one shows up long before the last one
        val firstAt = page.evaluate("() => window.__events[0].at").asInstanceOf[Number].doubleValue()
        val lastAt  = page.evaluate("() => window.__events[5].at").asInstanceOf[Number].doubleValue()
        firstAt must be < 1500.0
        (lastAt - firstAt) must be >= 1500.0
      }
    }
  }
}

// the plugin as it behaves on a platform brotli4j has no native library for
class UnavailableBrotliResponseCompressor extends BrotliResponseCompressor {
  override protected def brotliAvailable: Boolean = false
}
