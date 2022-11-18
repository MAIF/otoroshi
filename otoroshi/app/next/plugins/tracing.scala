package otoroshi.next.plugins

import akka.stream.Materializer
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{Span, SpanKind, StatusCode}
import io.opentelemetry.context.propagation.{ContextPropagators, TextMapGetter, TextMapPropagator, TextMapSetter}
import io.opentelemetry.context.{Context, Scope}
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{SimpleSpanProcessor, SpanExporter}
import io.opentelemetry.sdk.trace.data.SpanData
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import java.util.concurrent.TimeUnit
import java.{lang, util}
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.asJavaIterableConverter
import scala.util.{Failure, Success, Try}

object NoopSpanExporter {
  private val INSTANCE: SpanExporter             = new NoopSpanExporter
  private[plugins] def getInstance: SpanExporter = INSTANCE
}

final class NoopSpanExporter extends SpanExporter {
  override def `export`(spans: util.Collection[SpanData]): CompletableResultCode = CompletableResultCode.ofSuccess

  override def flush: CompletableResultCode = CompletableResultCode.ofSuccess

  override def shutdown: CompletableResultCode = CompletableResultCode.ofSuccess

  override def toString: String = "NoopSpanExporter{}"
}

sealed trait W3CTracingConfigKind {
  def name: String
}
object W3CTracingConfigKind       {
  case object Jaeger extends W3CTracingConfigKind { def name: String = "jaeger" }
  case object Zipkin extends W3CTracingConfigKind { def name: String = "zipkin" }
  case object Logger extends W3CTracingConfigKind { def name: String = "logger" }
  case object Noop   extends W3CTracingConfigKind { def name: String = "noop"   }
  def parse(str: String): W3CTracingConfigKind = str.toLowerCase() match {
    case "jaeger" => Jaeger
    case "zipkin" => Zipkin
    case "logger" => Logger
    case _        => Noop
  }
}

case class W3CTracingConfig(
    kind: W3CTracingConfigKind = W3CTracingConfigKind.Noop,
    endpoint: String = "http://localhost:3333/spans",
    timeout: Long = 30000,
    baggage: Map[String, String] = Map.empty
) extends NgPluginConfig {
  def json: JsValue = W3CTracingConfig.format.writes(this)
}

object W3CTracingConfig {
  val format = new Format[W3CTracingConfig] {
    override def writes(o: W3CTracingConfig): JsValue             = Json.obj(
      "kind"     -> o.kind.name,
      "endpoint" -> o.endpoint,
      "timeout"  -> o.timeout,
      "baggage"  -> o.baggage
    )
    override def reads(json: JsValue): JsResult[W3CTracingConfig] = Try {
      W3CTracingConfig(
        kind = json.select("kind").asOpt[String].map(W3CTracingConfigKind.parse).getOrElse(W3CTracingConfigKind.Logger),
        endpoint = json.select("endpoint").asString,
        timeout = json.select("timeout").asLong,
        baggage = json.select("baggage").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

case class SdkWrapper(config: W3CTracingConfig, sdk: OpenTelemetrySdk, traceProvider: SdkTracerProvider) {
  def close(): Unit = {
    traceProvider.close()
  }
  def hasChanged(c: W3CTracingConfig): Boolean = c != config
}

class W3CTracing extends NgRequestTransformer {

  private val opentelemetrysdks = new LegitTrieMap[String, SdkWrapper]()

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Monitoring)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  def buildOpenTelemetry(config: W3CTracingConfig): SdkWrapper = {
    val sdkTracerProvider = config.kind match {
      case W3CTracingConfigKind.Noop   =>
        SdkTracerProvider.builder
          .addSpanProcessor(SimpleSpanProcessor.create(NoopSpanExporter.getInstance))
          .build
      case W3CTracingConfigKind.Logger =>
        SdkTracerProvider.builder
          .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
          .build
      case W3CTracingConfigKind.Jaeger =>
        SdkTracerProvider.builder
          .addSpanProcessor(
            SimpleSpanProcessor.create(
              JaegerGrpcSpanExporter
                .builder()
                .setEndpoint(config.endpoint)
                .setTimeout(config.timeout, TimeUnit.MILLISECONDS)
                .build()
            )
          )
          .build
      case W3CTracingConfigKind.Zipkin =>
        SdkTracerProvider.builder
          .addSpanProcessor(
            SimpleSpanProcessor.create(
              ZipkinSpanExporter
                .builder()
                .setEndpoint(config.endpoint)
                .setReadTimeout(config.timeout, TimeUnit.MILLISECONDS)
                .build()
            )
          )
          .build
    }
    val sdk               = OpenTelemetrySdk.builder
      .setTracerProvider(sdkTracerProvider)
      .setPropagators(
        ContextPropagators.create(
          TextMapPropagator.composite(
            W3CTraceContextPropagator.getInstance(),
            W3CBaggagePropagator.getInstance()
          )
        )
      )
      .build
    SdkWrapper(config, sdk, sdkTracerProvider)
  }

  def getOpenTelemetry(serviceId: String, config: W3CTracingConfig): OpenTelemetrySdk = {
    val wrapper = opentelemetrysdks.getOrElse(
      serviceId, {
        buildOpenTelemetry(config)
      }
    )
    if (wrapper.hasChanged(config)) {
      wrapper.close()
      val nwrapper = buildOpenTelemetry(config)
      opentelemetrysdks.put(serviceId, nwrapper)
      nwrapper.sdk
    } else {
      wrapper.sdk
    }
  }

  private val SpanKey   = TypedKey[Span]("otoroshi.next.plugins.W3CTracing.Span")
  private val ScopesKey = TypedKey[Seq[Scope]]("otoroshi.next.plugins.W3CTracing.Scopes")
  private val TraceKey  = TypedKey[Seq[(String, String)]]("otoroshi.next.plugins.W3CTracing.Trace")

  private val getter = new TextMapGetter[NgTransformerRequestContext] {
    override def keys(carrier: NgTransformerRequestContext): lang.Iterable[String] =
      carrier.otoroshiRequest.headers.keys.asJava
    override def get(carrier: NgTransformerRequestContext, key: String): String = {
      carrier.otoroshiRequest.headers.getIgnoreCase(key).getOrElse("")
    }
  }

  private val setter = new TextMapSetter[NgTransformerRequestContext] {
    override def set(carrier: NgTransformerRequestContext, key: String, value: String): Unit = {
      val seq: Seq[(String, String)] = carrier.attrs.get(TraceKey).getOrElse(Seq.empty)
      carrier.attrs.put(TraceKey -> (seq :+ (key, value)))
    }
  }

  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "W3C Trace Context"
  override def description: Option[String]                 =
    "This plugin propagates W3C Trace Context spans and can export it to Jaeger or Zipkin".some
  override def defaultConfigObject: Option[NgPluginConfig] = W3CTracingConfig().some

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config     = ctx.cachedConfig(internalName)(W3CTracingConfig.format).getOrElse(W3CTracingConfig())
    val telemetry  = getOpenTelemetry(ctx.route.id, config)
    val propagator = telemetry.getPropagators.getTextMapPropagator
    val tracer     = telemetry.getTracer("otoroshi")

    val context = propagator.extract(Context.current(), ctx, getter)
    val span    = tracer.spanBuilder("http_proxy").setParent(context).setSpanKind(SpanKind.SERVER).startSpan()
    val baggage = Baggage.fromContext(context)
    val scope1  = span.makeCurrent()
    val scope2  = baggage.makeCurrent()
    val current = Context.current()

    span.setAttribute("lc", "otoroshi")
    span.addEvent("process_request")
    span.setAttribute("service.id", ctx.route.id)
    span.setAttribute("service.name", ctx.route.name)
    span.setAttribute("http.method", ctx.request.method)
    span.setAttribute("http.version", ctx.request.version)
    span.setAttribute("http.path", ctx.request.thePath)
    span.setAttribute("http.domain", ctx.request.theDomain)
    span.setAttribute("http.scheme", ctx.request.theProtocol)
    span.setAttribute("http.from", ctx.request.theIpAddress)

    val newContext = if (config.baggage.nonEmpty) {
      config.baggage
        .foldLeft(baggage.toBuilder) { case (builder, (key, value)) =>
          builder.put(
            key,
            GlobalExpressionLanguage.apply(
              value = value,
              req = ctx.request.some,
              service = ctx.route.serviceDescriptor.some,
              apiKey = ctx.apikey,
              user = ctx.user,
              context = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
              attrs = ctx.attrs,
              env = env
            )
          )
        }
        .build()
        .storeInContext(current)
    } else {
      current
    }

    ctx.attrs.put(ScopesKey -> Seq(scope1, scope2))
    ctx.attrs.put(SpanKey   -> span)
    ctx.attrs.put(TraceKey  -> Seq.empty)

    propagator.inject(newContext, ctx, setter)
    val headers = ctx.attrs.get(TraceKey).get
    span.addEvent("forward_request")
    ctx.otoroshiRequest
      .copy(
        headers = ctx.otoroshiRequest.headers ++ headers.toMap
      )
      .right
      .vfuture
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    ctx.attrs.get(SpanKey).foreach { span =>
      span.addEvent("process_response")
      span.setAttribute("http.response", ctx.otoroshiResponse.status)
      span.setStatus(StatusCode.OK)
      span.end()
    }
    ctx.attrs.get(ScopesKey).foreach(_.foreach(_.close()))
    ctx.otoroshiResponse.right.vfuture
  }

  override def transformError(
      ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    ctx.attrs.get(SpanKey).foreach { span =>
      span.addEvent("process_error")
      span.setAttribute("http.response", ctx.otoroshiResponse.status)
      span.setStatus(StatusCode.ERROR)
      span.end()
    }
    ctx.attrs.get(ScopesKey).foreach(_.foreach(_.close()))
    ctx.otoroshiResponse.vfuture
  }
}
