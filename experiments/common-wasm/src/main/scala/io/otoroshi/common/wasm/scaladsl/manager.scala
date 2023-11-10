package io.otoroshi.common.wasm.scaladsl

import io.otoroshi.common.wasm.scaladsl.implicits._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class WasmManagerSettings(
  url: String = "http://localhost:5001",
  clientId: String = "admin-api-apikey-id",
  clientSecret: String = "admin-api-apikey-secret",
  pluginsFilter: Option[String] = Some("*"),
  tokenSecret: Option[String] = Some("secret")
) {
  def json: JsValue = WasmManagerSettings.format.writes(this)
}

object WasmManagerSettings {
  val format = new Format[WasmManagerSettings] {
    override def writes(o: WasmManagerSettings): JsValue =
      Json.obj(
        "url"           -> o.url,
        "clientId"      -> o.clientId,
        "clientSecret"  -> o.clientSecret,
        "pluginsFilter" -> o.pluginsFilter.map(JsString).getOrElse(JsNull).as[JsValue],
        "tokenSecret"   -> o.tokenSecret.map(JsString).getOrElse(JsNull).as[JsValue]
      )

    override def reads(json: JsValue): JsResult[WasmManagerSettings] =
      Try {
        WasmManagerSettings(
          url = (json \ "url").asOpt[String].getOrElse("http://localhost:5001"),
          clientId = (json \ "clientId").asOpt[String].getOrElse("admin-api-apikey-id"),
          clientSecret = (json \ "clientSecret").asOpt[String].getOrElse("admin-api-apikey-secret"),
          pluginsFilter = (json \ "pluginsFilter").asOpt[String].getOrElse("*").some,
          tokenSecret = (json \ "tokenSecret").asOpt[String].getOrElse("secret").some,
        )
      } match {
        case Failure(e)  => JsError(e.getMessage)
        case Success(ac) => JsSuccess(ac)
      }
  }
}