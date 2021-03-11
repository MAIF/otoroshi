package otoroshi.script.plugins

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.script._
import otoroshi.utils.RegexPool
import play.api.libs.json.{Format, JsArray, JsError, JsResult, JsString, JsSuccess, JsValue, Json}
import play.api.mvc.{RequestHeader, Result}
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

object Plugins {
  val format = new Format[Plugins] {
    override def writes(o: Plugins): JsValue             =
      Json.obj(
        "enabled"  -> o.enabled,
        "refs"     -> JsArray(o.refs.map(JsString.apply)),
        "config"   -> o.config,
        "excluded" -> JsArray(o.excluded.map(JsString.apply))
      )
    override def reads(json: JsValue): JsResult[Plugins] =
      Try {
        JsSuccess(
          Plugins(
            refs = (json \ "refs")
              .asOpt[Seq[String]]
              .getOrElse(Seq.empty),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            config = (json \ "config").asOpt[JsValue].getOrElse(Json.obj()),
            excluded = (json \ "excluded").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class Plugins(
    enabled: Boolean = false,
    excluded: Seq[String] = Seq.empty[String],
    refs: Seq[String] = Seq.empty,
    config: JsValue = Json.obj()
) {

  private val transformers = new AtomicReference[Seq[String]](null)

  private def plugin[A](ref: String)(implicit ec: ExecutionContext, env: Env, ct: ClassTag[A]): Option[A] = {
    env.scriptManager.getAnyScript[NamedPlugin](ref) match {
      case Right(validator) if ct.runtimeClass.isAssignableFrom(validator.getClass) => validator.asInstanceOf[A].some
      case _                                                                        => None
    }
  }

  private def getPlugins[A](
      req: RequestHeader
  )(implicit ec: ExecutionContext, env: Env, ct: ClassTag[A]): Seq[String] = {
    val globalPlugins = env.datastores.globalConfigDataStore.latestSafe
      .map(_.plugins)
      .filter(p => p.enabled && p.refs.nonEmpty)
      .filter(pls => pls.excluded.isEmpty || !pls.excluded.exists(p => utils.RegexPool.regex(p).matches(req.thePath)))
      .getOrElse(Plugins())
      .refs
      .map(r => (r, plugin[A](r)))
      .collect { case (ref, Some(_)) =>
        ref
      }
    val localPlugins  = Some(this)
      .filter(p => p.enabled && p.refs.nonEmpty)
      .filter(pls => pls.excluded.isEmpty || !pls.excluded.exists(p => RegexPool.regex(p).matches(req.thePath)))
      .getOrElse(Plugins())
      .refs
      .map(r => (r, plugin[A](r)))
      .collect { case (ref, Some(_)) =>
        ref
      }
    (globalPlugins ++ localPlugins).distinct
  }

  def json: JsValue = Plugins.format.writes(this)

  def sinks(req: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    getPlugins[RequestSink](req)
  }

  def preRoutings(req: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    getPlugins[PreRouting](req)
  }

  def accessValidators(req: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    getPlugins[AccessValidator](req)
  }

  def requestTransformers(req: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    val cachedTransformers = transformers.get()
    if (cachedTransformers == null) {
      val trs = getPlugins[RequestTransformer](req)
      transformers.compareAndSet(null, trs)
      trs
    } else {
      cachedTransformers
    }
  }
}
