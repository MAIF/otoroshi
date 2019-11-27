package controllers

import java.security.cert.X509Certificate
import java.security.{KeyPair, KeyPairGenerator}
import java.util.Base64
import java.util.concurrent.TimeUnit

import actions.{BackOfficeAction, BackOfficeActionAuth}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import auth.GenericOauth2ModuleConfig
import ch.qos.logback.classic.{Level, LoggerContext}
import com.google.common.base.Charsets
import com.nimbusds.jose.jwk.KeyType
import env.Env
import events._
import models._
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{EmptyBody, SourceBody}
import play.api.mvc._
import security._
import ssl.FakeKeyStore.KeystoreSettings
import ssl.{Cert, CertificateData, FakeKeyStore, PemHeaders}
import utils.LocalCache

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

class BackOfficeController(BackOfficeAction: BackOfficeAction,
                           BackOfficeActionAuth: BackOfficeActionAuth,
                           cc: ControllerComponents)(
    implicit env: Env
) extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val lat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-backoffice-api")

  lazy val commitVersion = Option(System.getenv("COMMIT_ID")).getOrElse(env.otoroshiVersion)

  val sourceBodyParser = BodyParser("BackOfficeApi BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def hasBody(request: Request[_]): Boolean =
    (request.method, request.headers.get("Content-Length")) match {
      case ("GET", Some(_))    => true
      case ("GET", None)       => false
      case ("HEAD", Some(_))   => true
      case ("HEAD", None)      => false
      case ("PATCH", _)        => true
      case ("POST", _)         => true
      case ("PUT", _)          => true
      case ("DELETE", Some(_)) => true
      case ("DELETE", None)    => false
      case _                   => true
    }

  def proxyAdminApi(path: String) = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    val host                   = env.adminApiExposedHost
    val localUrl               = if (env.adminApiProxyHttps) s"https://127.0.0.1:${env.port}" else s"http://127.0.0.1:${env.port}"
    val url                    = if (env.adminApiProxyUseLocal) localUrl else s"https://${env.adminApiExposedHost}"
    lazy val currentReqHasBody = hasBody(ctx.request)
    logger.debug(s"Calling ${ctx.request.method} $url/$path with Host = $host")
    val headers = Seq(
      "Host"                           -> host,
      "X-Forwarded-For"                -> ctx.request.headers.get("X-Forwarded-For").getOrElse(ctx.request.remoteAddress),
      env.Headers.OtoroshiVizFromLabel -> "Otoroshi Admin UI",
      env.Headers.OtoroshiVizFrom      -> "otoroshi-admin-ui",
      env.Headers.OtoroshiClientId     -> env.backOfficeApiKey.clientId,
      env.Headers.OtoroshiClientSecret -> env.backOfficeApiKey.clientSecret,
      env.Headers.OtoroshiAdminProfile -> Base64.getUrlEncoder.encodeToString(
        Json.stringify(ctx.user.profile).getBytes(Charsets.UTF_8)
      )
    ) ++ ctx.request.headers.get("Content-Type").filter(_ => currentReqHasBody).map { ctype =>
      "Content-Type" -> ctype
    } ++ ctx.request.headers.get("Accept").map { accept =>
      "Accept" -> accept
    }
    env.Ws
      .url(s"$url/$path")
      .withHttpHeaders(headers: _*)
      .withFollowRedirects(false)
      .withMethod(ctx.request.method)
      .withRequestTimeout(1.minute)
      .withQueryStringParameters(ctx.request.queryString.toSeq.map(t => (t._1, t._2.head)): _*)
      .withBody(if (currentReqHasBody) SourceBody(ctx.request.body) else EmptyBody)
      .stream()
      .fast
      .map { res =>
        val ctype = res.headers.get("Content-Type").flatMap(_.headOption).getOrElse("application/json")
        Status(res.status)
          .sendEntity(
            HttpEntity.Streamed(
              Source.lazily(() => res.bodyAsSource),
              res.headers.get("Content-Length").flatMap(_.lastOption).map(_.toInt),
              res.headers.get("Content-Type").flatMap(_.headOption)
            )
          )
          .withHeaders(
            res.headers
              .mapValues(_.head)
              .toSeq
              .filter(_._1 != "Content-Type")
              .filter(_._1 != "Content-Length")
              .filter(_._1 != "Transfer-Encoding"): _*
          )
          .as(ctype)
      }
  }

  def robotTxt = Action { req =>
    logger.debug(s"Rendering robot.txt on http://${req.host}/robot.txt")
    Ok("""User-agent: *
    |Disallow: /""".stripMargin)
  }

  def version = BackOfficeActionAuth {
    Ok(
      Json.obj("version"        -> commitVersion,
               "currentVersion" -> env.otoroshiVersion,
               "nextVersion"    -> env.latestVersionHolder.get())
    )
  }

  def getEnv() = BackOfficeActionAuth.async { ctx =>
    val hash = BCrypt.hashpw("password", BCrypt.gensalt())
    env.datastores.globalConfigDataStore.singleton().flatMap { config =>
      env.datastores.simpleAdminDataStore.findAll().map { users =>
        val changePassword = users.filter { user =>
          //(user \ "password").as[String] == hash &&
          (user \ "username").as[String] == "admin@otoroshi.io"
        }.nonEmpty
        Ok(
          Json.obj(
            "scriptingEnabled"   -> env.scriptingEnabled,
            "otoroshiLogo"       -> env.otoroshiLogo,
            "clusterRole"        -> env.clusterConfig.mode.name,
            "snowMonkeyRunning"  -> config.snowMonkeyConfig.enabled,
            "changePassword"     -> changePassword,
            "mailgun"            -> config.mailerSettings.isDefined,
            "clevercloud"        -> config.cleverSettings.isDefined,
            "apiReadOnly"        -> config.apiReadOnly,
            "u2fLoginOnly"       -> config.u2fLoginOnly,
            "env"                -> env.env,
            "redirectToDev"      -> false,
            "clientIdHeader"     -> env.Headers.OtoroshiClientId,
            "clientSecretHeader" -> env.Headers.OtoroshiClientSecret,
            "version"            -> env.latestVersionHolder.get(),
            "currentVersion"     -> env.otoroshiVersion,
            "commitVersion"      -> commitVersion,
            "adminApiId"         -> env.backOfficeServiceId,
            "adminGroupId"       -> env.backOfficeGroupId,
            "adminApikeyId"      -> env.backOfficeApiKeyClientId,
          )
        )
      }
    }
  }

  def index = BackOfficeAction.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().map { config =>
      val thridPartyLoginEnabled = config.backOfficeAuthRef.nonEmpty
      ctx.user match {
        case Some(user)                  => Redirect("/bo/dashboard")
        case None if config.u2fLoginOnly => Redirect(routes.U2FController.loginPage())
        case None if thridPartyLoginEnabled =>
          Ok(views.html.backoffice.index(thridPartyLoginEnabled, ctx.user, ctx.request, env))
        case None if !thridPartyLoginEnabled => Redirect(routes.U2FController.loginPage())
      }
    }
  }

  def dashboard = BackOfficeActionAuth.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().map { config =>
      Ok(views.html.backoffice.dashboard(ctx.user, config, env, env.otoroshiVersion))
    }
  }

  def dashboardRoutes(ui: String) = BackOfficeActionAuth.async { ctx =>

    env.datastores.globalConfigDataStore.singleton().map { config =>
      Ok(views.html.backoffice.dashboard(ctx.user, config, env, env.otoroshiVersion))
    }
  }

  def error(message: Option[String]) = BackOfficeAction { ctx =>
    Ok(views.html.otoroshi.error(message.getOrElse("Error message"), env))
  }

  def documentationFrame(lineId: String, serviceId: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
      case Some(descriptor) => Ok(views.html.backoffice.documentationframe(descriptor, env))
      case None             => NotFound(Json.obj("error" -> s"Service with id $serviceId not found"))
    }
  }

  def documentationFrameDescriptor(lineId: String, serviceId: String) = BackOfficeActionAuth.async {
    import scala.concurrent.duration._
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case Some(service) if service.api.openApiDescriptorUrl.isDefined => {
        val state = IdGenerator.extendedToken(128)
        val claim = OtoroshiClaim(
          iss = env.Headers.OtoroshiIssuer,
          sub = "Documentation",
          aud = service.name,
          exp = DateTime.now().plusSeconds(30).toDate.getTime,
          iat = DateTime.now().toDate.getTime,
          jti = IdGenerator.uuid
        ).serialize(service.secComSettings)(env)
        val url = service.api.openApiDescriptorUrl.get match {
          case uri if uri.startsWith("/") => s"${service.target.scheme}://${service.target.host}${uri}"
          case url                        => url
        }
        env.Ws
          .url(url)
          .withRequestTimeout(10.seconds)
          .withHttpHeaders(
            env.Headers.OtoroshiRequestId -> env.snowflakeGenerator.nextIdStr(),
            env.Headers.OtoroshiState     -> state,
            env.Headers.OtoroshiClaim     -> claim
          )
          .get()
          .map { resp =>
            try {
              val swagger = (resp.json.as[JsObject] \ "swagger").as[String]
              swagger match {
                case "2.0" => Ok(Json.prettyPrint(resp.json)).as("application/json")
                case "3.0" => Ok(Json.prettyPrint(resp.json)).as("application/json")
                case _ =>
                  InternalServerError(views.html.otoroshi.error(s"Swagger version $swagger not supported", env))
              }
            } catch {
              case e: Throwable => InternalServerError(Json.obj("error" -> e.getMessage))
            }
          }
      }
      case _ => FastFuture.successful(NotFound(views.html.otoroshi.error("Service not found", env)))
    }
  }

  case class SearchedService(name: String, id: String, groupId: String, env: String, typ: String)

  def searchServicesApi() = BackOfficeActionAuth.async(parse.json) { ctx =>
    val query = (ctx.request.body \ "query").asOpt[String].getOrElse("--").toLowerCase()
    Audit.send(
      BackOfficeEvent(env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      ctx.user,
                      "SERVICESEARCH",
                      "user searched for a service",
                      ctx.from,
                      ctx.ua,
                      Json.obj(
                        "query" -> query
                      ))
    )
    val fu: Future[Seq[SearchedService]] =
      Option(LocalCache.allServices.getIfPresent("all")).map(_.asInstanceOf[Seq[SearchedService]]) match {
        case Some(descriptors) => FastFuture.successful(descriptors)
        case None =>
          for {
            services   <- env.datastores.serviceDescriptorDataStore.findAll()
            tcServices <- env.datastores.tcpServiceDataStore.findAll()
          } yield {
            val finalServices = (
              services.map(s => SearchedService(s.name, s.id, s.groupId, s.env, "http")) ++
              tcServices.map(s => SearchedService(s.name, s.id, "tcp", "prod", "tcp"))
            )
            LocalCache.allServices.put("all", finalServices)
            finalServices
          }
      }
    fu.map { services =>
      val filtered = services.filter { service =>
        service.id.toLowerCase() == query || service.name.toLowerCase().contains(query) || service.env
          .toLowerCase()
          .contains(query)
      }
      Ok(
        JsArray(
          filtered.map(
            s =>
              Json.obj("groupId" -> s.groupId, "serviceId" -> s.id, "name" -> s.name, "env" -> s.env, "type" -> s.typ)
          )
        )
      )
    }
  }

  def cleverApps() = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
        globalConfig.cleverClient match {
          case Some(client) => {
            client.apps(client.orgaId).map { cleverapps =>
              val apps = cleverapps.value
                .map { app =>
                  val id                 = (app \ "id").as[String]
                  val name               = (app \ "name").as[String]
                  val hosts: Seq[String] = (app \ "vhosts").as[JsArray].value.map(vhost => (vhost \ "fqdn").as[String])
                  val preferedHost       = hosts.filterNot(h => h.contains("cleverapps.io")).headOption.getOrElse(hosts.head)
                  val service            = services.find(s => s.targets.exists(t => hosts.contains(t.host)))
                  Json.obj(
                    "name"    -> (app \ "name").as[String],
                    "id"      -> id,
                    "url"     -> s"https://${preferedHost}/",
                    "console" -> s"https://console.clever-cloud.com/organisations/${client.orgaId}/applications/$id",
                    "exists"  -> service.isDefined,
                    "host"    -> preferedHost,
                    "otoUrl"  -> s"/lines/${service.map(_.env).getOrElse("--")}/services/${service.map(_.id).getOrElse("--")}"
                  )
                }
                .drop(paginationPosition)
                .take(paginationPageSize)
              Ok(JsArray(apps))
            }
          }
          case None => FastFuture.successful(Ok(Json.arr()))
        }
      }
    }
  }

  def sessions() = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.backOfficeUserDataStore.sessions() map { sessions =>
      Ok(JsArray(sessions.drop(paginationPosition).take(paginationPageSize)))
    }
  }

  def discardSession(id: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
      env.datastores.backOfficeUserDataStore.discardSession(id) map { _ =>
        val event = BackOfficeEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          ctx.user,
          "DISCARD_SESSION",
          s"Admin discarded an Admin session",
          ctx.from,
          ctx.ua,
          Json.obj("sessionId" -> id)
        )
        Audit.send(event)
        Alerts.send(SessionDiscardedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
        Ok(Json.obj("done" -> true))
      }
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def discardAllSessions() = BackOfficeActionAuth.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
      env.datastores.backOfficeUserDataStore.discardAllSessions() map { _ =>
        val event = BackOfficeEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          ctx.user,
          "DISCARD_SESSIONS",
          s"Admin discarded Admin sessions",
          ctx.from,
          ctx.ua,
          Json.obj()
        )
        Audit.send(event)
        Alerts.send(SessionsDiscardedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
        Ok(Json.obj("done" -> true))
      }
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def privateAppsSessions() = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.privateAppsUserDataStore.findAll() map { sessions =>
      Ok(JsArray(sessions.drop(paginationPosition).take(paginationPageSize).map(_.toJson)))
    }
  }

  def discardPrivateAppsSession(id: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
      env.datastores.privateAppsUserDataStore.delete(id) map { _ =>
        val event = BackOfficeEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          ctx.user,
          "DISCARD_PRIVATE_APPS_SESSION",
          s"Admin discarded a private app session",
          ctx.from,
          ctx.ua,
          Json.obj("sessionId" -> id)
        )
        Audit.send(event)
        Alerts.send(SessionDiscardedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
        Ok(Json.obj("done" -> true))
      }
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def discardAllPrivateAppsSessions() = BackOfficeActionAuth.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
      env.datastores.privateAppsUserDataStore.deleteAll() map { _ =>
        val event = BackOfficeEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          ctx.user,
          "DISCARD_PRIVATE_APPS_SESSIONS",
          s"Admin discarded private apps sessions",
          ctx.from,
          ctx.ua,
          Json.obj()
        )
        Audit.send(event)
        Alerts.send(SessionsDiscardedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
        Ok(Json.obj("done" -> true))
      }
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def panicMode() = BackOfficeActionAuth.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { c =>
      c.copy(u2fLoginOnly = true, apiReadOnly = true).save()
    } flatMap { _ =>
      env.datastores.backOfficeUserDataStore.discardAllSessions()
    } map { _ =>
      val event = BackOfficeEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        ctx.user,
        "ACTIVATE_PANIC_MODE",
        s"Admin activated panic mode",
        ctx.from,
        ctx.ua,
        Json.obj()
      )
      Audit.send(event)
      Alerts.send(PanicModeAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
      Ok(Json.obj("done" -> true))
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def auditEvents() = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.auditDataStore.findAllRaw().map { elems =>
      val filtered = elems.drop(paginationPosition).take(paginationPageSize)
      Ok.chunked(
          Source
            .single(ByteString("["))
            .concat(
              Source
                .apply(scala.collection.immutable.Iterable.empty[ByteString] ++ filtered)
                .intersperse(ByteString(","))
            )
            .concat(Source.single(ByteString("]")))
        )
        .as("application/json")
    }
  }

  def alertEvents() = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.alertDataStore.findAllRaw().map { elems =>
      val filtered = elems.drop(paginationPosition).take(paginationPageSize)
      Ok.chunked(
          Source
            .single(ByteString("["))
            .concat(
              Source
                .apply(scala.collection.immutable.Iterable.empty[ByteString] ++ filtered)
                .intersperse(ByteString(","))
            )
            .concat(Source.single(ByteString("]")))
        )
        .as("application/json")
    }
  }

  def changeLogLevel(name: String, newLevel: Option[String]) = BackOfficeActionAuth { ctx =>
    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val _logger       = loggerContext.getLogger(name)
    val oldLevel      = Option(_logger.getLevel).map(_.levelStr).getOrElse(Level.OFF.levelStr)
    _logger.setLevel(newLevel.map(v => Level.valueOf(v)).getOrElse(Level.ERROR))
    Ok(Json.obj("name" -> name, "oldLevel" -> oldLevel, "newLevel" -> _logger.getLevel.levelStr))
  }

  def getLogLevel(name: String) = BackOfficeActionAuth { ctx =>
    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val _logger       = loggerContext.getLogger(name)
    Ok(Json.obj("name" -> name, "level" -> _logger.getLevel.levelStr))
  }

  def getAllLoggers() = BackOfficeActionAuth { ctx =>
    import collection.JavaConverters._

    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize

    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val rawLoggers    = loggerContext.getLoggerList.asScala.drop(paginationPosition).take(paginationPageSize)
    val loggers = JsArray(rawLoggers.map(logger => {
      val level: String = Option(logger.getLevel).map(_.levelStr).getOrElse("OFF")
      Json.obj("name" -> logger.getName, "level" -> level)
    }))
    Ok(loggers)
  }

  case class ServiceRate(rate: Double, name: String, id: String)

  def mostCalledServices() = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(10)
    val paginationPosition = (paginationPage - 1) * paginationPageSize

    env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
      Future.sequence(
        services.map(
          s => env.datastores.serviceDescriptorDataStore.callsPerSec(s.id).map(rate => ServiceRate(rate, s.name, s.id))
        )
      )
    } map { items =>
      items.sortWith(_.rate > _.rate).drop(paginationPosition).take(paginationPageSize)
    } map { items =>
      items.map { i =>
        val value: Double = Option(i.rate).filterNot(_.isInfinity).getOrElse(0.0)
        Json.obj(
          "rate" -> value,
          "name" -> i.name,
          "id"   -> i.id
        )
      }
    } map { items =>
      Ok(JsArray(items))
    }
  }

  def servicesMap() = BackOfficeActionAuth.async { ctx =>
    env.datastores.serviceGroupDataStore.findAll().flatMap { groups =>
      Future.sequence(
        groups.map { group =>
          env.datastores.serviceDescriptorDataStore.findByGroup(group.id).flatMap { services =>
            Future.sequence(services.map { service =>
              env.datastores.serviceDescriptorDataStore.callsPerSec(service.id).map(cps => (service, cps))
            })
          } map {
            case services if services.isEmpty => Json.obj()
            case services if services.nonEmpty =>
              Json.obj(
                "name" -> group.name,
                "children" -> JsArray(services.map {
                  case (service, cps) =>
                    val size: Int = ((1.0 + cps) * 1000.0).toInt
                    Json.obj(
                      "name" -> service.name,
                      "env"  -> service.env,
                      "id"   -> service.id,
                      "size" -> size
                    )
                })
              )
          }
        }
      )
    } map { children =>
      Json.obj("name" -> "Otoroshi Services", "children" -> children.filterNot(_ == Json.obj()))
    } map { json =>
      Ok(json)
    }
  }

  def resetCircuitBreakers(id: String) = BackOfficeActionAuth { ctx =>
    env.circuitBeakersHolder.resetCircuitBreakersFor(id)
    Ok(Json.obj("done" -> true))
  }

  def certificateData(): Action[Source[ByteString, _]] = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).map { body =>
      Try {
        val parts: Seq[String] = body.utf8String.split("-----BEGIN CERTIFICATE-----").toSeq
        parts.tail.headOption.map { cert =>
          val content: String = cert.replace("-----END CERTIFICATE-----", "")
          Ok(CertificateData(content))
        } getOrElse {
          Try {
            val content: String =
              body.utf8String.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "")
            Ok(CertificateData(content))
          } recover {
            case e =>
              e.printStackTrace()
              BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
          } get
        }
      } recover {
        case e =>
          e.printStackTrace()
          BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
      } get
    }
  }

  def certificateIsValid(): Action[Source[ByteString, _]] = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).map { body =>
      Try {
        Cert.fromJsonSafe(Json.parse(body.utf8String)) match {
          case JsSuccess(cert, _) => Ok(Json.obj("valid"         -> cert.isValid))
          case JsError(e)         => BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
        }
      } recover {
        case e =>
          e.printStackTrace()
          BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
      } get
    }
  }

  def selfSignedCert(): Action[Source[ByteString, _]] = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).map { body =>
      Try {
        Json.parse(body.utf8String).\("host").asOpt[String] match {
          case Some(host) => {
            env.datastores.certificatesDataStore.findById(Cert.OtoroshiCA).map {
              case None => NotFound(Json.obj("error" -> s"No CA found"))
              case Some(ca) => {
                val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
                keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
                val keyPair = keyPairGenerator.generateKeyPair()
                val cert = FakeKeyStore.createCertificateFromCA(host,
                                                                FiniteDuration(365, TimeUnit.DAYS),
                                                                keyPair,
                                                                ca.certificate.get,
                                                                ca.keyPair)
                Ok(Cert(cert, keyPair, ca, false).enrich().toJson)
              }
            }
            Ok(FakeKeyStore.generateCert(host).toJson)
          }
          case None => BadRequest(Json.obj("error" -> s"No host provided"))
        }
      } recover {
        case e =>
          e.printStackTrace()
          BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
      } get
    }
  }

  def caCert(): Action[Source[ByteString, _]] = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).map { body =>
      Try {
        Json.parse(body.utf8String).\("cn").asOpt[String] match {
          case Some(cn) => {
            val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
            keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
            val keyPair = keyPairGenerator.generateKeyPair()
            val ca      = FakeKeyStore.createCA(s"CN=$cn", FiniteDuration(365, TimeUnit.DAYS), keyPair)
            val cert = Cert(
              id = IdGenerator.token(32),
              chain =
                s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(ca.getEncoded)}\n${PemHeaders.EndCertificate}",
              privateKey =
                s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(keyPair.getPrivate.getEncoded)}\n${PemHeaders.EndPrivateKey}",
              caRef = None,
              autoRenew = false,
              client = false
            ).enrich()
            Ok(cert.toJson)
          }
          case None => BadRequest(Json.obj("error" -> s"No host provided"))
        }
      } recover {
        case e =>
          e.printStackTrace()
          BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
      } get
    }
  }

  def caSignedCert(): Action[Source[ByteString, _]] = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
      Try {
        (Json.parse(body.utf8String).\("id").asOpt[String], Json.parse(body.utf8String).\("host").asOpt[String]) match {
          case (Some(id), Some(host)) => {
            env.datastores.certificatesDataStore.findById(id).map {
              case None => NotFound(Json.obj("error" -> s"No CA found"))
              case Some(ca) => {
                val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
                keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
                val keyPair = keyPairGenerator.generateKeyPair()
                val cert = FakeKeyStore.createCertificateFromCA(host,
                                                                FiniteDuration(365, TimeUnit.DAYS),
                                                                keyPair,
                                                                ca.certificate.get,
                                                                ca.keyPair)
                Ok(Cert(cert, keyPair, ca, false).enrich().toJson)
              }
            }
          }
          case _ => FastFuture.successful(BadRequest(Json.obj("error" -> s"No host provided")))
        }
      } recover {
        case e =>
          e.printStackTrace()
          FastFuture.successful(BadRequest(Json.obj("error" -> s"Bad certificate : $e")))
      } get
    }
  }

  def renew(id: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.certificatesDataStore.findById(id).flatMap {
      case None => FastFuture.successful(NotFound(Json.obj("error" -> s"No Certificate found")))
      case Some(original) if original.ca && original.selfSigned => {
        val keyPair: KeyPair      = original.keyPair
        val cert: X509Certificate = FakeKeyStore.createCA(original.subject, FiniteDuration(365, TimeUnit.DAYS), keyPair)
        val certificate: Cert     = Cert(cert, keyPair, None, original.client).enrich().copy(id = original.id)
        certificate.save().map { _ =>
          Ok(certificate.toJson)
        }
      }
      case Some(original) if original.selfSigned => {
        val keyPair: KeyPair = original.keyPair
        val cert: X509Certificate =
          FakeKeyStore.createSelfSignedCertificate(original.domain, FiniteDuration(365, TimeUnit.DAYS), keyPair)
        val certificate: Cert = Cert(cert, keyPair, None, original.client).enrich().copy(id = original.id)
        certificate.save().map { _ =>
          Ok(certificate.toJson)
        }
      }
      case Some(original) if original.caRef.isDefined => {
        env.datastores.certificatesDataStore.findById(original.caRef.get).flatMap {
          case None => FastFuture.successful(NotFound(Json.obj("error" -> s"No Certificate found")))
          case Some(ca) => {
            val keyPair: KeyPair = original.keyPair
            val cert: X509Certificate = FakeKeyStore.createCertificateFromCA(original.domain,
                                                                             FiniteDuration(365, TimeUnit.DAYS),
                                                                             keyPair,
                                                                             ca.certificate.get,
                                                                             ca.keyPair)
            val certificate: Cert = Cert(cert, keyPair, None, original.client).enrich().copy(id = original.id)
            certificate.save().map { _ =>
              Ok(certificate.toJson)
            }
          }
        }
      }
      case _ => {
        FastFuture.successful(BadRequest(Json.obj("error" -> s"Bad renew")))
      }
    }
  }

  def fetchOpenIdConfiguration() = BackOfficeActionAuth.async(parse.json) { ctx =>
    import utils.http.Implicits._

    import scala.concurrent.duration._

    val id           = (ctx.request.body \ "id").asOpt[String].getOrElse(IdGenerator.token(64))
    val name         = (ctx.request.body \ "name").asOpt[String].getOrElse("new oauth config")
    val desc         = (ctx.request.body \ "desc").asOpt[String].getOrElse("new oauth config")
    val clientId     = (ctx.request.body \ "clientId").asOpt[String].getOrElse("client")
    val clientSecret = (ctx.request.body \ "clientSecret").asOpt[String].getOrElse("secret")
    (ctx.request.body \ "url").asOpt[String] match {
      case None =>
        FastFuture.successful(
          Ok(
            GenericOauth2ModuleConfig(
              id = id,
              name = name,
              desc = desc,
              clientId = clientId,
              clientSecret = clientSecret,
              oidConfig = None
            ).asJson
          )
        )
      case Some(url) => {
        // TODO: use a possible proxy ????
        env.Ws.url(url).withRequestTimeout(10.seconds).get().map { resp =>
          if (resp.status == 200) {
            Try {
              val config = GenericOauth2ModuleConfig(
                id = id,
                name = name,
                desc = desc,
                oidConfig = Some(url)
              )
              val body             = Json.parse(resp.body)
              val issuer           = (body \ "issuer").asOpt[String].getOrElse("http://localhost:8082/")
              val tokenUrl         = (body \ "token_endpoint").asOpt[String].getOrElse(config.tokenUrl)
              val authorizeUrl     = (body \ "authorization_endpoint").asOpt[String].getOrElse(config.authorizeUrl)
              val userInfoUrl      = (body \ "userinfo_endpoint").asOpt[String].getOrElse(config.userInfoUrl)
              val introspectionUrl = (body \ "introspection_endpoint").asOpt[String].getOrElse(config.introspectionUrl)
              val loginUrl         = (body \ "authorization_endpoint").asOpt[String].getOrElse(authorizeUrl)
              val logoutUrl = (body \ "end_session_endpoint")
                .asOpt[String]
                .orElse((body \ "ping_end_session_endpoint").asOpt[String])
                .getOrElse((issuer + "/logout").replace("//logout", "/logout"))
              val jwksUri = (body \ "jwks_uri").asOpt[String]
              val scope = (body \ "scopes_supported")
                .asOpt[Seq[String]]
                .map(_.mkString(" "))
                .getOrElse("openid profile email name")
              val claims =
                (body \ "claims_supported").asOpt[JsArray].map(Json.stringify).getOrElse("""["email","name"]""")
              Ok(
                config
                  .copy(
                    clientId = clientId,
                    clientSecret = clientSecret,
                    tokenUrl = tokenUrl,
                    authorizeUrl = authorizeUrl,
                    userInfoUrl = userInfoUrl,
                    introspectionUrl = introspectionUrl,
                    loginUrl = loginUrl,
                    logoutUrl = logoutUrl,
                    callbackUrl =
                      s"${env.rootScheme}${env.privateAppsHost}${env.privateAppsPort.map(v => ":" + v).getOrElse("")}/privateapps/generic/callback",
                    scope = scope,
                    claims = "",
                    accessTokenField = "access_token", // jwksUri.map(_ => "id_token").getOrElse("access_token"),
                    useJson = false,
                    useCookie = false,
                    readProfileFromToken = false,
                    nameField = (if (scope.contains(config.nameField)) config.nameField else config.emailField),
                    oidConfig = Some(url),
                    jwtVerifier = jwksUri.map(
                      url =>
                        JWKSAlgoSettings(
                          url = url,
                          headers = Map.empty[String, String],
                          timeout = FiniteDuration(2000, TimeUnit.MILLISECONDS),
                          ttl = FiniteDuration(60 * 60 * 1000, TimeUnit.MILLISECONDS),
                          kty = KeyType.RSA,
                          None
                      )
                    )
                  )
                  .asJson
              )
            } getOrElse {
              resp.ignore()
              Ok(
                GenericOauth2ModuleConfig(
                  id = id,
                  name = name,
                  desc = desc,
                  clientId = clientId,
                  clientSecret = clientSecret,
                  oidConfig = Some(url)
                ).asJson
              )
            }
          } else {
            resp.ignore()
            Ok(
              GenericOauth2ModuleConfig(
                id = id,
                name = name,
                desc = desc,
                clientId = clientId,
                clientSecret = clientSecret,
                oidConfig = Some(url)
              ).asJson
            )
          }
        }
      }
    }
  }

  def fetchBodiesFor(serviceId: String, requestId: String) = BackOfficeActionAuth.async { ctx =>
    for {
      req <- env.datastores.rawDataStore.get(s"${env.storageRoot}:bodies:$serviceId:$requestId:request")
      resp <- env.datastores.rawDataStore.get(s"${env.storageRoot}:bodies:$serviceId:$requestId:response")
    } yield {
      if (req.isEmpty && resp.isEmpty) {
        NotFound(Json.obj("error" -> "Bodies not found"))
      } else {
        Ok(Json.obj(
          "response" -> resp.map(_.utf8String).map(Json.parse).getOrElse(JsNull).as[JsValue],
          "request" -> req.map(_.utf8String).map(Json.parse).getOrElse(JsNull).as[JsValue]
        ))
      }
    }
  }

  def fetchLatestGeoLite2() = BackOfficeActionAuth.async { ctx =>
    val dir = java.nio.file.Files.createTempDirectory("oto-geolite-")
    val file = dir.resolve("geolite.tar.gz")
    env.Ws.url("https://geolite.maxmind.com/download/geoip/database/GeoLite2-City.tar.gz").get().flatMap { resp =>
      resp.bodyAsSource.runWith(FileIO.toPath(file)).map { io =>
        if (io.wasSuccessful) {
          val builder = new ProcessBuilder
          builder.command("/bin/sh", "-c", String.format("cd %s; tar -xvf geolite.tar.gz; rm -rf geolite.tar.gz; mv Geo* geolite; mv geolite/GeoLite2-City.mmdb geolite.mmdb; rm -rf ./geolite", dir))
          builder.directory(dir.toFile)
          val process = builder.start
          val exitCode = process.waitFor
          Ok(Json.obj("path" -> dir.resolve("geolite.mmdb").toFile.getAbsolutePath, "exitCode" -> exitCode))
        } else {
          InternalServerError(Json.obj("path" -> file.toFile.getAbsolutePath))
        }
      }
    }
  }
}
