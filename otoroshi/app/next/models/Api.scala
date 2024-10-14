package next.models

import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.models.{EntityLocation, EntityLocationSupport, LoadBalancing}
import otoroshi.next.models.{NgDomainAndPath, NgPlugins, NgRoute, NgTarget}
import play.api.libs.json.{Format, JsResult, JsValue}

case class ApiState(started: Boolean, published: Boolean, public: Boolean, deprecated: Boolean)

case class ApiBackend(name: String, targets: Seq[NgTarget], root: String, rewrite: Boolean, loadBalancing: LoadBalancing)

case class ApiFrontend(domains: Seq[NgDomainAndPath], headers: Map[String, String], query: Map[String, String], methods: Seq[String], stripPath: Boolean, exact: Boolean)

case class ApiRoute(frontend: ApiFrontend, plugins: Option[String], backend: String)

case class ApiPredicate()

case class ApiPlugins(name: String, predicate: ApiPredicate, plugins: NgPlugins)

case class ApiDeploymentRef(ref: String, at: DateTime)

sealed trait ApiSpecification {
  def content: JsValue
}
object ApiSpecification {
  case class OpenApiSpecification(content: JsValue) extends ApiSpecification
  case class AsyncApiSpecification(content: JsValue) extends ApiSpecification
}

trait ApiPage {
  def path: String
  def name: String
}

object ApiPage {
  case class ApiPageDir(path: String, name: String, leafs: Seq[ApiPage])
  case class ApiPageLeaf(path: String, name: String, content: ByteString)
}

case class ApiDocumentation(specification: ApiSpecification, home: ApiPage, pages: Seq[ApiPage])

trait ApiBlueprint {
  def name: String
}

object ApiBlueprint {
  case class REST() extends ApiBlueprint { def name: String = "REST" }
  case class GraphQL() extends ApiBlueprint { def name: String = "GraphQL" }
  case class gRPC() extends ApiBlueprint { def name: String = "gRPC" }
}

case class Api(
   location: EntityLocation,
   id: String,
   name: String,
   description: String,
   tags: Seq[String],
   metadata: Map[String, String],
   version: String,
   // or versions: Seq[ApiVersion] with ApiVersion being the following ?
   //// ApiVersion
   state: ApiState,
   blueprint: ApiBlueprint,
   routes: Seq[ApiRoute],
   backends: Seq[ApiBackend],
   plugins: Seq[ApiPlugins],
   documentation: ApiDocumentation,
   consumers: Seq[ApiConsumer],
   deployments: Seq[ApiDeploymentRef],
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
