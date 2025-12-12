package plugins

import akka.http.scaladsl.model.headers.RawHeader
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.AppenderBase
import functional.PluginsTestSpec
import org.slf4j.LoggerFactory
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{LimitHeaderOutTooLong, OverrideHost, RejectHeaderConfig}
import play.api.http.Status
import play.api.libs.json._

class LimitHeadersOutTooLongTests(parent: PluginsTestSpec) {
  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[LimitHeaderOutTooLong],
        config = NgPluginInstanceConfig(
          RejectHeaderConfig(
            value = 20
          ).json.as[JsObject]
        )
      )
    ),
    responseStatus = Status.OK,
    result = _ => Json.obj(),
    responseHeaders = List(RawHeader("foo", "bar"), RawHeader("baz", "very very very very very long header value"))
  ).futureValue

  val logger = LoggerFactory.getLogger("otoroshi-plugin-limit-headers-out-too-long").asInstanceOf[LogbackLogger]

  val events   = scala.collection.mutable.ListBuffer.empty[ILoggingEvent]
  val appender = new AppenderBase[ILoggingEvent]() {
    override def append(eventObject: ILoggingEvent): Unit = events += eventObject
  }
  appender.start()
  logger.addAppender(appender)

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK

  assert(events.exists(_.getMessage.contains("limiting header")))
  assert(events.exists(_.getMessage.contains("baz")))
  assert(events.exists(_.getLevel == Level.ERROR))

  logger.detachAppender(appender)

  deleteOtoroshiRoute(route).futureValue
}
