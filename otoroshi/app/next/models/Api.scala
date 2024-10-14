package next.models

import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.models.NgRoute
import play.api.libs.json.{Format, JsResult, JsValue}

case class ApiState(started: Boolean, published: Boolean, public: Boolean, deprecated: Boolean)

case class Api(
   location: EntityLocation,
   id: String,
   name: String,
   description: String,
   tags: Seq[String],
   metadata: Map[String, String],
   version: String, // or Seq[ApiVersion] with ApiVersion being the following ?
   //// ApiVersion
   state: ApiState,
   blueprint: ApiBlueprint,
   routes: Seq[ApiRoute],
   backends: Map[String, ApiBackend],
   plugins: Map[String, ApiPlugins],
   documentation: ApiDocumentation,
   consumers: Seq[ApiConsumer],
   deployments: Seq[ApiDeployment],
   //// ApiVersion
 ) extends EntityLocationSupport {
  override def internalId: String = id
  override def json: JsValue = Api.format.writes(this)
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata

  def toRoutes: Seq[NgRoute] = ???
}

object Api {
  val format = new Format[Api] {
    override def reads(json: JsValue): JsResult[Api] = ???
    override def writes(o: Api): JsValue = ???
  }
}
