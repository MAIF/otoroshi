package otoroshi.next.extensions

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import otoroshi.actions.{ApiAction, BackOfficeAction, PrivateAppsAction}
import otoroshi.api.Resource
import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser, EntityLocationSupport, PrivateAppsUser}
import otoroshi.storage.BasicStore
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.Configuration
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc._

import scala.concurrent.Future
import scala.reflect.ClassTag

case class AdminExtensionId(value: String) {
  lazy val cleanup: String = value.replace(".", "_").toLowerCase()
}
case class AdminExtensionEntity[A <: EntityLocationSupport](resource: otoroshi.api.Resource, datastore: BasicStore[A])

case class AdminExtensionFrontendExtension()
trait AdminExtensionRoute {
  def method: String
  def path: String
  def wantsBody: Boolean
  def routeId: String = s"${method}${path}"
}

class AdminExtensionRouterContext[A <: AdminExtensionRoute](underlying: org.bigtesting.routd.Route, val adminRoute: A, method: String, path: String, matchPath: String) {
  def named(name: String): Option[String] = Option(underlying.getNamedParameter(name, matchPath))
  def splat(index: Int): Option[String] = Option(underlying.getSplatParameter(index, matchPath))
}

class AdminExtensionRouter[A <: AdminExtensionRoute](routes: Seq[A]) {
  private val (router, config) = {
    var m = Map.empty[String, A]
    val r = new org.bigtesting.routd.TreeRouter()
    routes.foreach { route =>
      r.add(new org.bigtesting.routd.Route(route.routeId))
      m = m.+((route.routeId, route))
    }
    (r, m)
  }
  def find(request: RequestHeader): Option[AdminExtensionRouterContext[A]] = {
    val matchedPath = s"${request.method}${request.path}"
    Option(router.route(matchedPath)).flatMap { r =>
      config.get(r.getResourcePath).map(rr => new AdminExtensionRouterContext[A](r, rr, request.method, request.path, matchedPath))
    }
  }
}

case class AdminExtensionAssetRoute(path: String, handle: (AdminExtensionRouterContext[AdminExtensionAssetRoute], RequestHeader) => Future[Result]) extends AdminExtensionRoute {
  override def method: String = "GET"
  override def wantsBody: Boolean = false
}
case class AdminExtensionBackofficeAuthRoute(method: String, path: String, wantsBody: Boolean, handle: (AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], RequestHeader, Option[BackOfficeUser], Option[Source[ByteString, _]]) => Future[Result]) extends AdminExtensionRoute
case class AdminExtensionBackofficePublicRoute(method: String, path: String, wantsBody: Boolean, handle: (AdminExtensionRouterContext[AdminExtensionBackofficePublicRoute], RequestHeader, Option[Source[ByteString, _]]) => Future[Result]) extends AdminExtensionRoute
case class AdminExtensionAdminApiRoute(method: String, path: String, wantsBody: Boolean, handle: (AdminExtensionRouterContext[AdminExtensionAdminApiRoute], RequestHeader, ApiKey, Option[Source[ByteString, _]]) => Future[Result]) extends AdminExtensionRoute
case class AdminExtensionPrivateAppAuthRoute(method: String, path: String, wantsBody: Boolean, handle: (AdminExtensionRouterContext[AdminExtensionPrivateAppAuthRoute], RequestHeader, Option[PrivateAppsUser], Option[Source[ByteString, _]]) => Future[Result]) extends AdminExtensionRoute
case class AdminExtensionPrivateAppPublicRoute(method: String, path: String, wantsBody: Boolean, handle: (AdminExtensionRouterContext[AdminExtensionPrivateAppPublicRoute], RequestHeader, Option[Source[ByteString, _]]) => Future[Result]) extends AdminExtensionRoute
case class AdminExtensionWellKnownRoute(method: String, path: String, wantsBody: Boolean, handle: (AdminExtensionRouterContext[AdminExtensionWellKnownRoute], RequestHeader, Option[Source[ByteString, _]]) => Future[Result]) extends AdminExtensionRoute


case class AdminExtensionConfig(enabled: Boolean)

trait AdminExtension {

  def env: Env

  def id: AdminExtensionId
  def enabled: Boolean
  def name: String
  def description: Option[String]

  def start(): Unit = ()
  def stop(): Unit = ()
  def syncStates(): Future[Unit] = ().vfuture

  def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = Seq.empty
  def frontendExtension(): Seq[AdminExtensionFrontendExtension] = Seq.empty
  def assets(): Seq[AdminExtensionAssetRoute] = Seq.empty
  def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq.empty
  def backofficePublicRoutes(): Seq[AdminExtensionBackofficePublicRoute] = Seq.empty
  def adminApiRoutes(): Seq[AdminExtensionAdminApiRoute] = Seq.empty
  def privateAppAuthRoutes(): Seq[AdminExtensionPrivateAppAuthRoute] = Seq.empty
  def privateAppPublicRoutes(): Seq[AdminExtensionPrivateAppPublicRoute] = Seq.empty
  def wellKnownRoutes(): Seq[AdminExtensionWellKnownRoute] = Seq.empty

  def configuration: Configuration = env.configuration.getOptional[Configuration](s"otoroshi.admin-extensions.configurations.${id.cleanup}").getOrElse(Configuration.empty)
}

object AdminExtensions {
  def current(env: Env, config: AdminExtensionConfig): AdminExtensions = {
    if (config.enabled) {
      val extensions = env.scriptManager
        .adminExtensionNames
        .map { name =>
          try {
            val clazz = this.getClass.getClassLoader.loadClass(name)
            val constructor = (Option(clazz.getDeclaredConstructor(classOf[Env])).toSeq ++ Option(clazz.getConstructor(classOf[Env])).toSeq).head
            val inst = constructor.newInstance(env)
            Right(inst.asInstanceOf[AdminExtension])
          } catch {
            case e: Throwable =>
              e.printStackTrace()
              Left(e)
          }
        }
        .collect {
          case Right(ext) => ext
        }
      new AdminExtensions(env, extensions)
    } else {
      new AdminExtensions(env, Seq.empty)
    }
  }
}

class AdminExtensions(env: Env, _extensions: Seq[AdminExtension]) {

  private implicit val ec = env.otoroshiExecutionContext
  private implicit val mat = env.otoroshiMaterializer
  private implicit val ev = env

  private val hasExtensions = _extensions.nonEmpty

  private val extensions: Seq[AdminExtension] = _extensions.filter(_.enabled)
  private val entitiesMap: Map[String, Seq[AdminExtensionEntity[EntityLocationSupport]]] = extensions.map(v => (v.id.cleanup, v.entities())).toMap
  private val entities: Seq[AdminExtensionEntity[EntityLocationSupport]] = entitiesMap.values.flatten.toSeq

  private val frontendExtension: Seq[AdminExtensionFrontendExtension] = extensions.flatMap(_.frontendExtension())

  private val assets: Seq[AdminExtensionAssetRoute] = extensions.flatMap(_.assets())
  private val assetsRouter = new AdminExtensionRouter[AdminExtensionAssetRoute](assets)

  private val backofficeAuthRoutes: Seq[AdminExtensionBackofficeAuthRoute] = extensions.flatMap(_.backofficeAuthRoutes())
  private val backofficeAuthRouter = new AdminExtensionRouter[AdminExtensionBackofficeAuthRoute](backofficeAuthRoutes)
  private val backofficePublicRoutes: Seq[AdminExtensionBackofficePublicRoute] = extensions.flatMap(_.backofficePublicRoutes())
  private val backofficePublicRouter = new AdminExtensionRouter[AdminExtensionBackofficePublicRoute](backofficePublicRoutes)

  private val adminApiRoutes: Seq[AdminExtensionAdminApiRoute] = extensions.flatMap(_.adminApiRoutes())
  private val adminApiRouter = new AdminExtensionRouter[AdminExtensionAdminApiRoute](adminApiRoutes)

  private val privateAppAuthRoutes: Seq[AdminExtensionPrivateAppAuthRoute] = extensions.flatMap(_.privateAppAuthRoutes())
  private val privateAppAuthRouter = new AdminExtensionRouter[AdminExtensionPrivateAppAuthRoute](privateAppAuthRoutes)
  private val privateAppPublicRoutes: Seq[AdminExtensionPrivateAppPublicRoute] = extensions.flatMap(_.privateAppPublicRoutes())
  private val privateAppPublicRouter = new AdminExtensionRouter[AdminExtensionPrivateAppPublicRoute](privateAppPublicRoutes)

  private val wellKnownRoutes: Seq[AdminExtensionWellKnownRoute] = extensions.flatMap(_.wellKnownRoutes())
  private val wellKnownRouter = new AdminExtensionRouter[AdminExtensionWellKnownRoute](wellKnownRoutes)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private val extCache = new LegitTrieMap[Class[_], Any]

  def extension[A](implicit ct: ClassTag[A]): Option[A] = {
    if (hasExtensions) {
      extCache.get(ct.runtimeClass) match {
        case Some(any) => any.asInstanceOf[A].some
        case None => {
          val opt = extensions.find { e =>
            e.getClass == ct.runtimeClass
          }.map(_.asInstanceOf[A])
          opt.foreach(ex => extCache.put(ct.runtimeClass, ex))
          opt
        }
      }
    } else {
      None
    }
  }

  def handleWellKnownCall(request: RequestHeader, actionBuilder: ActionBuilder[Request, AnyContent], sourceBodyParser: BodyParser[Source[ByteString, _]])(f: => Option[Handler]): Option[Handler] = {
    if (hasExtensions && request.path.startsWith("/.well-known/otoroshi/extensions/") && wellKnownRoutes.nonEmpty) {
      wellKnownRouter.find(request) match {
        case None => f
        case Some(route) if route.adminRoute.wantsBody => Some(actionBuilder.async(sourceBodyParser) { req => route.adminRoute.handle(route, req, req.body.some) })
        case Some(route) if !route.adminRoute.wantsBody => Some(actionBuilder.async { req => route.adminRoute.handle(route, req, None) })
      }
    } else {
      f
    }
  }

  def handleAdminApiCall(request: RequestHeader, actionBuilder: ActionBuilder[Request, AnyContent], ApiAction: ApiAction, sourceBodyParser: BodyParser[Source[ByteString, _]])(f: => Option[Handler]): Option[Handler] = {
    if (hasExtensions && request.path.startsWith("/api/extensions/") && adminApiRoutes.nonEmpty) {
      adminApiRouter.find(request) match {
        case Some(route) if route.adminRoute.wantsBody => Some(ApiAction.async(sourceBodyParser) { ctx => route.adminRoute.handle(route, ctx.request, ctx.apiKey, ctx.request.body.some) })
        case Some(route) if !route.adminRoute.wantsBody => Some(ApiAction.async { ctx => route.adminRoute.handle(route, ctx.request, ctx.apiKey, None) })
        case None => f
      }
    } else f
  }

  def handleBackofficeCall(request: RequestHeader, actionBuilder: ActionBuilder[Request, AnyContent], BackOfficeAction: BackOfficeAction, sourceBodyParser: BodyParser[Source[ByteString, _]])(f: => Option[Handler]): Option[Handler] = {
    if (hasExtensions && request.path.startsWith("/extensions/") && !(backofficeAuthRoutes.isEmpty && backofficePublicRoutes.isEmpty && assets.isEmpty)) {
      backofficePublicRouter.find(request) match {
        case Some(route) if route.adminRoute.wantsBody => Some(actionBuilder.async(sourceBodyParser) { req => route.adminRoute.handle(route, req, req.body.some) })
        case Some(route) if !route.adminRoute.wantsBody => Some(actionBuilder.async { req => route.adminRoute.handle(route, req, None) })
        case None => {
          backofficeAuthRouter.find(request) match {
            case Some(route) if route.adminRoute.wantsBody => Some(BackOfficeAction.async(sourceBodyParser) { ctx => route.adminRoute.handle(route, ctx.request, ctx.user, ctx.request.body.some) })
            case Some(route) if !route.adminRoute.wantsBody => Some(BackOfficeAction.async { ctx => route.adminRoute.handle(route, ctx.request, ctx.user, None) })
            case None => {
              assetsRouter.find(request) match {
                case Some(route) => Some(actionBuilder.async { ctx => route.adminRoute.handle(route, ctx) })
                case None => f
              }
            }
          }
        }
      }
    } else f
  }

  def handlePrivateAppsCall(request: RequestHeader, actionBuilder: ActionBuilder[Request, AnyContent], PrivateAppsAction: PrivateAppsAction, sourceBodyParser: BodyParser[Source[ByteString, _]])(f: => Option[Handler]): Option[Handler] = {
    if (hasExtensions && request.path.startsWith("/extensions/") && !(privateAppAuthRoutes.isEmpty && privateAppPublicRoutes.isEmpty && assets.isEmpty)) {
      privateAppPublicRouter.find(request) match {
        case Some(route) if route.adminRoute.wantsBody => Some(actionBuilder.async(sourceBodyParser) { req => route.adminRoute.handle(route, req, req.body.some) })
        case Some(route) if !route.adminRoute.wantsBody => Some(actionBuilder.async { req => route.adminRoute.handle(route, req, None) })
        case None => {
          privateAppAuthRouter.find(request) match {
            case Some(route) if route.adminRoute.wantsBody => Some(PrivateAppsAction.async(sourceBodyParser) { ctx => route.adminRoute.handle(route, ctx.request, ctx.user, ctx.request.body.some) })
            case Some(route) if !route.adminRoute.wantsBody => Some(PrivateAppsAction.async { ctx => route.adminRoute.handle(route, ctx.request, ctx.user, None) })
            case None => {
              assetsRouter.find(request) match {
                case Some(route) => Some(actionBuilder.async { ctx => route.adminRoute.handle(route, ctx) })
                case None => f
              }
            }
          }
        }
      }
    } else f
  }

  def resources(): Seq[Resource] = {
    if (hasExtensions) {
      entities.map(_.resource)
    } else {
      Seq.empty
    }
  }

  def start(): Unit = {
    if (hasExtensions) {
      extensions.foreach(_.start())
    } else {
      ()
    }
  }

  def stop(): Unit = {
    if (hasExtensions) {
      extensions.foreach(_.stop())
    } else {
      ()
    }
  }

  def syncStates(): Future[Unit] = {
    if (hasExtensions) {
      Source(extensions.toList)
        .mapAsync(1) { extension =>
          extension.syncStates()
        }
        .runWith(Sink.ignore)
        .map(_ => ())
    } else {
      ().vfuture
    }
  }

  def exportAllEntities(): Future[Map[String, Map[String, Seq[JsValue]]]] = {
    if (hasExtensions) {
      Source(entitiesMap.toList)
        .mapAsync(1) {
          case (group, ett) => {
            Source(ett.toList)
              .mapAsync(1) { entity =>
                entity.datastore.findAll().map(values => (entity.resource.pluralName, values))
              }
              .runFold((group, Map.empty[String, Seq[JsValue]])) { (tuple, elem) =>
                val newMap = tuple._2 + (elem._1 -> elem._2.map(_.json))
                (tuple._1, newMap)
              }
          }
        }
        .runFold(Map.empty[String, Map[String, Seq[JsValue]]])((map, elem) => map + elem)
    } else {
      Map.empty[String, Map[String, Seq[JsValue]]].vfuture
    }
  }

  def importAllEntities(source: JsObject): Future[Unit] = {
    if (hasExtensions) {
      val extensions: Map[String, Map[String, Seq[JsValue]]] = source.asOpt[Map[String, Map[String, Seq[JsValue]]]].getOrElse(Map.empty[String, Map[String, Seq[JsValue]]])
      Source(extensions.mapValues(_.toSeq).toSeq.flatMap {
        case (key, items) => items.map(tuple => (key, tuple._1, tuple._2))
      }.toList).mapAsync(1) {
        case (entityGroup, entityPluralName, entities) => {
          entitiesMap.get(entityGroup).flatMap(_.find(_.resource.pluralName == entityPluralName)) match {
            case None => ().vfuture
            case Some(ent) => {
              Source(entities.toList)
                .mapAsync(1) { value =>
                  env.datastores.rawDataStore.set(ent.datastore.key(value.select("id").asString), value.stringify.byteString, None)
                }
                .runWith(Sink.ignore)
            }
          }
        }
      }
        .runWith(Sink.ignore).map(_ => ())
    } else {
      ().vfuture
    }
  }
}
