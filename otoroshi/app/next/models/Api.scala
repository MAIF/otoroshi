package next.models

import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.models.{EntityLocation, EntityLocationSupport, LoadBalancing}
import otoroshi.next.models.{NgClientConfig, NgDomainAndPath, NgPlugins, NgRoute, NgTarget}
import play.api.libs.json.{Format, JsResult, JsValue}

case class ApiState(started: Boolean, published: Boolean, public: Boolean, deprecated: Boolean)

case class ApiBackend(name: String, targets: Seq[NgTarget], root: String, rewrite: Boolean, loadBalancing: LoadBalancing)

case class ApiFrontend(domains: Seq[NgDomainAndPath], headers: Map[String, String], query: Map[String, String], methods: Seq[String], stripPath: Boolean, exact: Boolean)

case class ApiRoute(frontend: ApiFrontend, plugins: Option[String], backend: String)

case class ApiPredicate()

case class ApiPlugins(name: String, predicate: ApiPredicate, plugins: NgPlugins)

case class ApiDeploymentRef(ref: String, at: DateTime)

case class ApiDeployment(location: EntityLocation, id: String, apiRef: String, owner: String, at: DateTime, apiDefinition: JsValue) extends EntityLocationSupport {
  override def internalId: String = id
  override def json: JsValue = ApiDeployment.format.writes(this)
  override def theName: String = id
  override def theDescription: String = id
  override def theTags: Seq[String] = Seq.empty
  override def theMetadata: Map[String, String] = Map.empty
}

object ApiDeployment {
  val format = new Format[ApiDeployment] {
    override def reads(json: JsValue): JsResult[ApiDeployment] = ???
    override def writes(o: ApiDeployment): JsValue = ???
  }
}

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

case class ApiDocumentation(specification: ApiSpecification, home: ApiPage, pages: Seq[ApiPage], metadata: Map[String, String])

trait ApiBlueprint {
  def name: String
}

object ApiBlueprint {
  case class REST() extends ApiBlueprint { def name: String = "REST" }
  case class GraphQL() extends ApiBlueprint { def name: String = "GraphQL" }
  case class gRPC() extends ApiBlueprint { def name: String = "gRPC" }
  case class Http() extends ApiBlueprint { def name: String = "Http" }
  case class Websocket() extends ApiBlueprint { def name: String = "Websocket" }
}

case class ApiConsumer(name: String, description: String, autoValidation: Boolean, kind: ApiConsumerKind, status: ApiConsumerStatus, subscriptions: Seq[ApiConsumerSubscriptionRef])

case class ApiConsumerSubscriptionDates(
  created_at: DateTime,
  processed_at: DateTime,
  started_at: DateTime,
  ending_at: DateTime,
  closed_at: DateTime,
)

case class ApiConsumerSubscription(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  enabled: Boolean,
  dates: ApiConsumerSubscriptionDates,
  ownerRef: String,
  consumerRef: Option[String],
  kind: ApiConsumerKind,
  tokenRefs: Seq[String] // ref to apikey, cert, etc
) extends EntityLocationSupport {
  override def internalId: String = id
  override def json: JsValue = ApiConsumerSubscription.format.writes(this)
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
}

object ApiConsumerSubscription {
  val format = new Format[ApiConsumerSubscription] {
    override def reads(json: JsValue): JsResult[ApiConsumerSubscription] = ???
    override def writes(o: ApiConsumerSubscription): JsValue = ???
  }
}

case class ApiConsumerSubscriptionRef(ref: String)

trait ApiConsumerKind
object ApiConsumerKind {
  case object Apikey extends ApiConsumerKind
  case object Mtls extends ApiConsumerKind
  case object Keyless extends ApiConsumerKind
  case object OAuth2 extends ApiConsumerKind
  case object JWT extends ApiConsumerKind
}

trait ApiConsumerStatus
object ApiConsumerStatus {
  case object Staging extends ApiConsumerStatus
  case object Published extends ApiConsumerStatus
  case object Deprecated extends ApiConsumerStatus
  case object Closed extends ApiConsumerStatus
}
case class ApiBackendClient(name: String, client: NgClientConfig)

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
   debugFlow: Boolean,
   capture: Boolean,
   exportReporting: Boolean,
   state: ApiState,
   blueprint: ApiBlueprint,
   routes: Seq[ApiRoute],
   backends: Seq[ApiBackend],
   plugins: Seq[ApiPlugins],
   clients: Seq[ApiBackendClient],
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
