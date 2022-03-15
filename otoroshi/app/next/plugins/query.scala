package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.ExecutionContext
import scala.util._

case class QueryTransformerConfig(remove: Seq[String] = Seq.empty, rename: Map[String, String] = Map.empty, add: Map[String, List[String]] = Map.empty) extends NgPluginConfig {
  def json: JsValue = QueryTransformerConfig.format.writes(this)
}

object QueryTransformerConfig {
  val format = new Format[QueryTransformerConfig] {
    override def writes(o: QueryTransformerConfig): JsValue = Json.obj(
      "remove" -> o.remove,
      "rename" -> o.rename,
      "add" -> o.add
    )
    override def reads(json: JsValue): JsResult[QueryTransformerConfig] = Try {
      QueryTransformerConfig(
        remove = json.select("remove").asOpt[Seq[String]].getOrElse(Seq.empty),
        rename = json.select("rename").asOpt[Map[String, String]].getOrElse(Map.empty),
        add = json.select("add").asOpt[Map[String, List[String]]].getOrElse(Map.empty),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
  }
}

class QueryTransformer extends NgRequestTransformer {

  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String                = "Query param transformer"
  override def description: Option[String] = "This plugin can modify the query params of the request".some
  override def defaultConfigObject: Option[NgPluginConfig] = QueryTransformerConfig().some

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformsError: Boolean = false
  override def transformsResponse: Boolean = false
  override def isTransformRequestAsync: Boolean = false

  override def transformRequestSync(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx.cachedConfig(internalName)(QueryTransformerConfig.format).getOrElse(QueryTransformerConfig())
    val uri = ctx.otoroshiRequest.uri
    val query: Map[String, List[String]] = uri.query().toMultiMap
    val queryRemoved: Map[String, List[String]] = config.remove.foldLeft(query) {
      case (query, name) => query.-(name)
    }
    val queryRenamed: Map[String, List[String]] = config.rename.foldLeft(queryRemoved) {
      case (query, (key, value)) =>
        query.get(key) match {
          case None => query
          case Some(old) => query.-(key).+((value, old))
        }
    }
    val queryAdded: Map[String, List[String]] = config.add.foldLeft(queryRenamed) {
      case (query, (key, value)) => query.+((key, value))
    }
    if (queryAdded.isEmpty) {
      ctx.otoroshiRequest.right
    } else {
      val newUri = uri.copy(rawQueryString = queryAdded.toString().some)
      ctx.otoroshiRequest.copy(url = newUri.toString()).right
    }
  }
}
