package otoroshi.controllers.adminapi

import akka.NotUsed
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.actions.ApiAction
import otoroshi.cluster._
import otoroshi.env.{Env, JavaVersion, OS}
import otoroshi.models.{PrivateAppsUser, RightsChecker}
import otoroshi.next.proxy.{ProxyEngine, RelayRoutingRequest}
import otoroshi.script.RequestHandler
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.{Cell, RemoteConnection, RequestAttrKey, RequestTarget}
import play.api.mvc._

import java.net.{InetAddress, URI}
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.concurrent.duration.{Duration, FiniteDuration}

class ClusterController(ApiAction: ApiAction, cc: ControllerComponents)(implicit
    env: Env
) extends AbstractController(cc) {

  import otoroshi.cluster.ClusterMode.{Leader, Off, Worker}

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val sourceBodyParser = BodyParser("ClusterController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def liveCluster() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        def healthOf(member: MemberView): String = {
          val value = System.currentTimeMillis() - member.lastSeen.getMillis
          if (value < (member.timeout.toMillis / 2)) {
            "green"
          } else if (value < (3 * (member.timeout.toMillis / 4))) {
            "orange"
          } else {
            "red"
          }
        }

        env.clusterConfig.mode match {
          case Off    => NotFound(Json.obj("error" -> "Cluster API not available")).future
          case Worker => NotFound(Json.obj("error" -> "Cluster API not available")).future
          case Leader => {
            val every  = ctx.request.getQueryString("every").map(_.toInt).getOrElse(2000)
            val source = Source
              .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(every, TimeUnit.MILLISECONDS), NotUsed)
              .mapAsync(1) { _ =>
                for {
                  members <- env.datastores.clusterStateDataStore.getMembers()
                  inOut   <- env.datastores.clusterStateDataStore.dataInAndOut()
                } yield (members, inOut)
              }
              .recover { case e =>
                Cluster.logger.error("Error", e)
                (Seq.empty[MemberView], (0L, 0L))
              }
              .map { case (members, inOut) =>
                val payloadIn: Long  = inOut._1
                val payloadOut: Long = inOut._2
                val healths          = members.map(healthOf)
                val foundOrange      = healths.contains("orange")
                val foundRed         = healths.contains("red")
                val health           = if (foundRed) "red" else (if (foundOrange) "orange" else "green")
                Json.obj(
                  "workers"    -> members.size,
                  "health"     -> health,
                  "payloadIn"  -> payloadIn,
                  "payloadOut" -> payloadOut
                )
              }
              .map(Json.stringify)
              .map(slug => s"data: $slug\n\n")
            Ok.chunked(source).as("text/event-stream").future
          }
        }
      }
    }

  def getClusterMembers() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Leader => {
            val time = Json.obj("time" -> DateTime.now().getMillis)
            env.datastores.clusterStateDataStore.getMembers().map { members =>
              Ok(JsArray(members.map(_.asJson.as[JsObject] ++ time)))
            }
          }
        }
      }
    }

  def clearClusterMembers() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Leader => {
            val time = Json.obj("time" -> DateTime.now().getMillis)
            env.datastores.clusterStateDataStore.clearMembers().map { members =>
              Ok(Json.obj("done" -> true))
            }
          }
        }
      }
    }

  def isLoginTokenValid(token: String) =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => {
            env.clusterAgent.isLoginTokenValid(token).map {
              case true  => Ok(Json.obj("token" -> token))
              case false => NotFound(Json.obj("error" -> "Login token not found"))
            }
          }
          case Leader => {
            if (Cluster.logger.isDebugEnabled)
              Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] valid login token $token")
            env.datastores.authConfigsDataStore.validateLoginToken(token).map {
              case true  =>
                if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"Login token $token is valid")
                Ok(Json.obj("token" -> token))
              case false =>
                if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"Login token $token not valid")
                NotFound(Json.obj("error" -> "Login token not found"))
            }
          }
        }
      }
    }

  def getUserToken(token: String) =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => {
            env.clusterAgent.getUserToken(token).map {
              case Some(token) => Ok(Json.obj("token" -> token))
              case None        => NotFound(Json.obj("error" -> "User token not found"))
            }
          }
          case Leader => {
            if (Cluster.logger.isDebugEnabled)
              Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] valid user token $token")
            env.datastores.authConfigsDataStore.getUserForToken(token).map {
              case Some(user) =>
                if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"User token $token is valid")
                Ok(user)
              case None       =>
                if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"User token $token not found")
                NotFound(Json.obj("error" -> "User token not found"))
            }
          }
        }
      }
    }

  def createLoginToken(token: String) =
    ApiAction.async(parse.json) { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => {
            env.clusterAgent.createLoginToken(token).map {
              case Some(_) => Created(Json.obj("token" -> token))
              case _       => InternalServerError(Json.obj("error" -> "Failed to create login token on leader"))
            }
          }
          case Leader => {
            if (Cluster.logger.isDebugEnabled)
              Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] creating login token $token")
            env.datastores.authConfigsDataStore
              .generateLoginToken(Some(token))
              .map(t => Created(Json.obj("token" -> t)))
          }
        }
      }
    }

  def setUserToken() =
    ApiAction.async(parse.json) { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => {
            val token = (ctx.request.body \ "token").as[String]
            val usr   = (ctx.request.body \ "user").as[JsValue]
            env.clusterAgent.setUserToken(token, usr).map {
              case Some(_) => Created(Json.obj("token" -> token))
              case _       => InternalServerError(Json.obj("error" -> "Failed to create user token on leader"))
            }
          }
          case Leader => {
            val token = (ctx.request.body \ "token").as[String]
            val usr   = (ctx.request.body \ "user").as[JsValue]
            if (Cluster.logger.isDebugEnabled)
              Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] creating user token $token")
            env.datastores.authConfigsDataStore
              .setUserForToken(token, usr)
              .map(_ => Created(Json.obj("token" -> token)))
          }
        }
      }
    }

  def isSessionValid(sessionId: String) =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => {
            env.clusterAgent.isSessionValid(sessionId, None).map {
              case Some(user) => Ok(user.toJson)
              case None       => NotFound(Json.obj("error" -> "Session not found"))
            }
          }
          case Leader => {
            if (Cluster.logger.isDebugEnabled)
              Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] valid session $sessionId")
            env.datastores.privateAppsUserDataStore.findById(sessionId).map {
              case Some(user) =>
                if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"Session $sessionId is valid")
                Ok(user.toJson)
              case None       =>
                if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"Session $sessionId not valid")
                NotFound(Json.obj("error" -> "Session not found"))
            }
          }
        }
      }
    }

  def createSession() =
    ApiAction.async(parse.json) { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => {
            PrivateAppsUser.fmt.reads(ctx.request.body) match {
              case JsError(e)         => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad session format")))
              case JsSuccess(user, _) => {
                env.clusterAgent.createSession(user).map {
                  case Some(session) => Created(session.toJson)
                  case _             => InternalServerError(Json.obj("error" -> "Failed to create session on leader"))
                }
              }
            }
          }
          case Leader => {
            if (Cluster.logger.isDebugEnabled)
              Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] creating session")
            PrivateAppsUser.fmt.reads(ctx.request.body) match {
              case JsError(e)         => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad session format")))
              case JsSuccess(user, _) =>
                if (Cluster.logger.isDebugEnabled)
                  Cluster.logger.debug(
                    s"Saving session for user ${user.name} for the next ${Duration(user.expiredAt.getMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)} ms"
                  )
                user.save(Duration(user.expiredAt.getMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)).map {
                  session =>
                    Created(session.toJson)
                }
            }
          }
        }
      }
    }

  def updateQuotas() =
    ApiAction.async(sourceBodyParser) { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
          case Worker => {
            ctx.request.body
              .via(env.clusterConfig.gunzip())
              .via(Framing.delimiter(ByteString("\n"), 32 * 1024 * 1024))
              .mapAsync(4) { item =>
                val jsItem = Json.parse(item.utf8String)
                ClusterQuotaIncr.read(jsItem) match {
                  case None => FastFuture.successful(())
                  case Some(quota) => quota.updateWorker()
                }
                // (jsItem \ "typ").asOpt[String] match {
                //   case Some("globstats") => {
                //     // TODO: membership + global stats ?
                //     FastFuture.successful(())
                //   }
                //   case Some("srvincr")   => {
                //     val id      = (jsItem \ "srv").asOpt[String].getOrElse("--")
                //     val calls   = (jsItem \ "c").asOpt[Long].getOrElse(0L)
                //     val dataIn  = (jsItem \ "di").asOpt[Long].getOrElse(0L)
                //     val dataOut = (jsItem \ "do").asOpt[Long].getOrElse(0L)
                //     env.clusterAgent.incrementService(id, dataIn, dataOut)
                //     if (calls - 1 > 0) {
                //       (0L to (calls - 1L)).foreach { _ =>
                //         env.clusterAgent.incrementService(id, 0L, 0L)
                //       }
                //     }
                //     FastFuture.successful(())
                //   }
                //   case Some("apkincr")   => {
                //     val id        = (jsItem \ "apk").asOpt[String].getOrElse("--")
                //     val increment = (jsItem \ "i").asOpt[Long].getOrElse(0L)
                //     env.clusterAgent.incrementApi(id, increment)
                //     FastFuture.successful(())
                //   }
                //   case _                 => FastFuture.successful(())
                // }
              }
              .runWith(Sink.ignore)
              .map { _ =>
                Ok(Json.obj("done" -> true))
              }
              .recover { case e =>
                Cluster.logger.error("Error while updating quotas", e)
                InternalServerError(Json.obj("error" -> e.getMessage))
              }
          }
          case Leader => {
            // Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] updating quotas")
            val budget: Long = ctx.request.getQueryString("budget").map(_.toLong).getOrElse(2000L)
            val start: Long  = System.currentTimeMillis()
            val bytesCounter = new AtomicLong(0L)
            env.datastores.globalConfigDataStore.singleton().flatMap { config =>
              ctx.request.body
                .map(bs => {
                  bytesCounter.addAndGet(bs.size)
                  bs
                })
                .via(env.clusterConfig.gunzip())
                .via(Framing.delimiter(ByteString("\n"), 32 * 1024 * 1024))
                .mapAsync(4) { item =>
                  val jsItem = Json.parse(item.utf8String)
                  ClusterQuotaIncr.read(jsItem) match {
                    case Some(quota) => quota.updateLeader()
                    case None => {
                      (jsItem \ "typ").asOpt[String] match {
                        case Some("globstats") => {
                          ctx.request.headers
                            .get(ClusterAgent.OtoroshiWorkerNameHeader)
                            .map { name =>
                              env.datastores.clusterStateDataStore.registerMember(
                                MemberView(
                                  id = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerIdHeader)
                                    .getOrElse(s"tmpnode_${IdGenerator.uuid}"),
                                  name = name,
                                  os = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerOsHeader)
                                    .map(OS.fromString)
                                    .getOrElse(OS.default),
                                  version = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerVersionHeader)
                                    .getOrElse("undefined"),
                                  javaVersion = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerJavaVersionHeader)
                                    .map(JavaVersion.fromString)
                                    .getOrElse(JavaVersion.default),
                                  memberType = ClusterMode.Worker,
                                  location =
                                    ctx.request.headers.get(ClusterAgent.OtoroshiWorkerLocationHeader).getOrElse("--"),
                                  httpPort = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerHttpPortHeader)
                                    .map(_.toInt)
                                    .getOrElse(env.exposedHttpPortInt),
                                  httpsPort = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerHttpsPortHeader)
                                    .map(_.toInt)
                                    .getOrElse(env.exposedHttpsPortInt),
                                  internalHttpPort = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerInternalHttpPortHeader)
                                    .map(_.toInt)
                                    .getOrElse(env.httpPort),
                                  internalHttpsPort = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader)
                                    .map(_.toInt)
                                    .getOrElse(env.httpsPort),
                                  lastSeen = DateTime.now(),
                                  timeout = Duration(
                                    env.clusterConfig.worker.retries * env.clusterConfig.worker.state.pollEvery,
                                    TimeUnit.MILLISECONDS
                                  ),
                                  stats = jsItem.as[JsObject],
                                  tunnels = Seq.empty,
                                  relay = ctx.request.headers
                                    .get(ClusterAgent.OtoroshiWorkerRelayRoutingHeader)
                                    .flatMap(RelayRouting.parse)
                                    .getOrElse(RelayRouting.default)
                                )
                              )
                            }
                            .getOrElse(FastFuture.successful(()))
                        }
                        case _ => FastFuture.successful(())
                      }
                    }
                  }
                  //(jsItem \ "typ").asOpt[String] match {
                  //  case Some("globstats") => {
                  //    ctx.request.headers
                  //      .get(ClusterAgent.OtoroshiWorkerNameHeader)
                  //      .map { name =>
                  //        env.datastores.clusterStateDataStore.registerMember(
                  //          MemberView(
                  //            id = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerIdHeader)
                  //              .getOrElse(s"tmpnode_${IdGenerator.uuid}"),
                  //            name = name,
                  //            os = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerOsHeader)
                  //              .map(OS.fromString)
                  //              .getOrElse(OS.default),
                  //            version = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerVersionHeader)
                  //              .getOrElse("undefined"),
                  //            javaVersion = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerJavaVersionHeader)
                  //              .map(JavaVersion.fromString)
                  //              .getOrElse(JavaVersion.default),
                  //            memberType = ClusterMode.Worker,
                  //            location =
                  //              ctx.request.headers.get(ClusterAgent.OtoroshiWorkerLocationHeader).getOrElse("--"),
                  //            httpPort = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerHttpPortHeader)
                  //              .map(_.toInt)
                  //              .getOrElse(env.exposedHttpPortInt),
                  //            httpsPort = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerHttpsPortHeader)
                  //              .map(_.toInt)
                  //              .getOrElse(env.exposedHttpsPortInt),
                  //            internalHttpPort = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerInternalHttpPortHeader)
                  //              .map(_.toInt)
                  //              .getOrElse(env.httpPort),
                  //            internalHttpsPort = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader)
                  //              .map(_.toInt)
                  //              .getOrElse(env.httpsPort),
                  //            lastSeen = DateTime.now(),
                  //            timeout = Duration(
                  //              env.clusterConfig.worker.retries * env.clusterConfig.worker.state.pollEvery,
                  //              TimeUnit.MILLISECONDS
                  //            ),
                  //            stats = jsItem.as[JsObject],
                  //            tunnels = Seq.empty,
                  //            relay = ctx.request.headers
                  //              .get(ClusterAgent.OtoroshiWorkerRelayRoutingHeader)
                  //              .flatMap(RelayRouting.parse)
                  //              .getOrElse(RelayRouting.default)
                  //          )
                  //        )
                  //      }
                  //      .getOrElse(FastFuture.successful(()))
                  //  }
                  //  case Some("srvincr")   => {
                  //    val id      = (jsItem \ "srv").asOpt[String].getOrElse("--")
                  //    val calls   = (jsItem \ "c").asOpt[Long].getOrElse(0L)
                  //    val dataIn  = (jsItem \ "di").asOpt[Long].getOrElse(0L)
                  //    val dataOut = (jsItem \ "do").asOpt[Long].getOrElse(0L)
                  //    env.datastores.serviceDescriptorDataStore.findById(id).flatMap {
                  //      case Some(_) =>
                  //        env.datastores.serviceDescriptorDataStore
                  //          .updateIncrementableMetrics(id, calls, dataIn, dataOut, config)
                  //      case None    => FastFuture.successful(())
                  //    }
                  //  }
                  //  case Some("apkincr")   => {
                  //    val id        = (jsItem \ "apk").asOpt[String].getOrElse("--")
                  //    val increment = (jsItem \ "i").asOpt[Long].getOrElse(0L)
                  //    env.datastores.apiKeyDataStore.findById(id).flatMap {
                  //      case Some(apikey) => env.datastores.apiKeyDataStore.updateQuotas(apikey, increment)
                  //      case None         => FastFuture.successful(())
                  //    }
                  //  }
                  //  case _                 => FastFuture.successful(())
                  //}

                }
                .runWith(Sink.ignore)
                .andThen { case _ =>
                  if (Cluster.logger.isTraceEnabled)
                    Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] updated quotas (${bytesCounter.get()} b)")
                  env.datastores.clusterStateDataStore.updateDataIn(bytesCounter.get())
                  if ((System.currentTimeMillis() - start) > budget) {
                    Cluster.logger.warn(
                      s"[${env.clusterConfig.mode.name}] Quotas update from worker ran over time budget, maybe the datastore is slow ?"
                    )
                  }
                }
                .map(_ => Ok(Json.obj("done" -> true)))
                .recover { case e =>
                  Cluster.logger.error("Error while updating quotas", e)
                  InternalServerError(Json.obj("error" -> e.getMessage))
                }
            }
          }
        }
      }
    }

  val caching      = new AtomicBoolean(false)
  val cachedAt     = new AtomicLong(0L)
  val cachedRef    = new AtomicReference[ByteString]()
  val cachedCount  = new AtomicLong(0L)
  val cachedDigest = new AtomicReference[String]("--")

  def internalState() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        env.clusterConfig.mode match {
          case Off    => NotFound(Json.obj("error" -> "Cluster API not available")).future
          case Worker => {
            // TODO: cluster membership
            Ok.sendEntity(
              HttpEntity.Streamed(
                env.datastores
                  .rawExport(env.clusterConfig.leader.groupingBy)
                  .map { item =>
                    ByteString(Json.stringify(item) + "\n")
                  }
                  .via(env.clusterConfig.gzip()),
                None,
                Some("application/x-ndjson")
              )
            ).future
          }
          case Leader => {

            val budget: Long = ctx.request.getQueryString("budget").map(_.toLong).getOrElse(2000L)
            val cachedValue  = cachedRef.get()

            ctx.request.headers.get(ClusterAgent.OtoroshiWorkerNameHeader).map { name =>
              env.datastores.clusterStateDataStore.registerMember(
                MemberView(
                  id = ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerIdHeader)
                    .getOrElse(s"tmpnode_${IdGenerator.uuid}"),
                  name = name,
                  os = ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerOsHeader)
                    .map(OS.fromString)
                    .getOrElse(OS.default),
                  version = ctx.request.headers.get(ClusterAgent.OtoroshiWorkerVersionHeader).getOrElse("undefined"),
                  javaVersion = ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerJavaVersionHeader)
                    .map(JavaVersion.fromString)
                    .getOrElse(JavaVersion.default),
                  memberType = ClusterMode.Worker,
                  location = ctx.request.headers.get(ClusterAgent.OtoroshiWorkerLocationHeader).getOrElse("--"),
                  httpPort = ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerHttpPortHeader)
                    .map(_.toInt)
                    .getOrElse(env.exposedHttpPortInt),
                  httpsPort = ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerHttpsPortHeader)
                    .map(_.toInt)
                    .getOrElse(env.exposedHttpsPortInt),
                  internalHttpPort = ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerInternalHttpPortHeader)
                    .map(_.toInt)
                    .getOrElse(env.httpPort),
                  internalHttpsPort = ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader)
                    .map(_.toInt)
                    .getOrElse(env.httpsPort),
                  lastSeen = DateTime.now(),
                  timeout = Duration(
                    env.clusterConfig.worker.retries * env.clusterConfig.worker.state.pollEvery,
                    TimeUnit.MILLISECONDS
                  ),
                  tunnels = Seq.empty,
                  relay = ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerRelayRoutingHeader)
                    .flatMap(RelayRouting.parse)
                    .getOrElse(RelayRouting.default)
                )
              )
            }

            /*
            def sendAndCache(): Future[Result] = {
              // Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Exporting raw state")
              if (caching.compareAndSet(false, true)) {
                val start: Long = System.currentTimeMillis()
                // var stateCache = ByteString.empty
                val counter     = new AtomicLong(0L)
                val digest      = MessageDigest.getInstance("SHA-256")
                env.datastores
                  .rawExport(env.clusterConfig.leader.groupingBy)
                  .map { item =>
                    ByteString(Json.stringify(item) + "\n")
                  }
                  .alsoTo(Sink.foreach { item =>
                    digest.update(item.asByteBuffer)
                    counter.incrementAndGet()
                  })
                  .via(env.clusterConfig.gzip())
                  // .alsoTo(Sink.fold(ByteString.empty)(_ ++ _))
                  // .alsoTo(Sink.foreach(bs => stateCache = stateCache ++ bs))
                  // .alsoTo(Sink.onComplete {
                  //   case Success(_) =>
                  //     if ((System.currentTimeMillis() - start) > budget) {
                  //       Cluster.logger.warn(
                  //         s"[${env.clusterConfig.mode.name}] Datastore export to worker ran over time budget, maybe the datastore is slow ?"
                  //       )
                  //     }
                  //     cachedRef.set(stateCache)
                  //     cachedAt.set(System.currentTimeMillis())
                  //     caching.compareAndSet(true, false)
                  //     env.datastores.clusterStateDataStore.updateDataOut(stateCache.size)
                  //     env.clusterConfig.leader.stateDumpPath
                  //       .foreach(path => Future(Files.write(stateCache.toArray, new File(path))))
                  //     Cluster.logger.debug(
                  //       s"[${env.clusterConfig.mode.name}] Exported raw state (${stateCache.size / 1024} Kb) in ${System.currentTimeMillis - start} ms."
                  //     )
                  //   case Failure(e) =>
                  //     Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Stream error while exporting raw state",
                  //       e)
                  // })
                  .runWith(Sink.fold(ByteString.empty)(_ ++ _))
                  .fold {
                    case Success(stateCache) => {
                      if ((System.currentTimeMillis() - start) > budget) {
                        Cluster.logger.warn(
                          s"[${env.clusterConfig.mode.name}] Datastore export to worker ran over time budget, maybe the datastore is slow ?"
                        )
                      }
                      cachedRef.set(stateCache)
                      cachedAt.set(System.currentTimeMillis())
                      cachedCount.set(counter.get())
                      cachedDigest.set(Hex.encodeHexString(digest.digest()))
                      caching.compareAndSet(true, false)
                      env.datastores.clusterStateDataStore.updateDataOut(stateCache.size)
                      env.clusterConfig.leader.stateDumpPath
                        .foreach(path => Future(Files.write(stateCache.toArray, new File(path))))
                      Cluster.logger.debug(
                        s"[${env.clusterConfig.mode.name}] Exported raw state (${stateCache.size / 1024} Kb) in ${System.currentTimeMillis - start} ms."
                      )
                      Ok.sendEntity(
                        HttpEntity.Streamed(
                          Source.single(stateCache),
                          None,
                          Some("application/x-ndjson")
                        )
                      ).withHeaders(
                        "Otoroshi-Leader-Node-Name"    -> env.clusterConfig.leader.name,
                        "Otoroshi-Leader-Node-Version" -> env.otoroshiVersion,
                        "X-Data-Count"                 -> s"${cachedCount.get()}",
                        "X-Data-Digest"                -> cachedDigest.get(),
                        "X-Data-From"                  -> s"${System.currentTimeMillis()}",
                        "X-Data-Fresh"                 -> "true"
                      ) //.withHeaders("Content-Encoding" -> "gzip")
                    }
                    case Failure(err)        =>
                      Cluster.logger.error(
                        s"[${env.clusterConfig.mode.name}] Stream error while exporting raw state",
                        err
                      )
                      InternalServerError(
                        Json.obj("error" -> "Stream error while exporting raw state", "message" -> err.getMessage)
                      )
                  }
              } else {
                Cluster.logger.debug(
                  s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
                )
                Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
                  .withHeaders(
                    "Otoroshi-Leader-Node-Name"    -> env.clusterConfig.leader.name,
                    "Otoroshi-Leader-Node-Version" -> env.otoroshiVersion,
                    "X-Data-Count"                 -> s"${cachedCount.get()}",
                    "X-Data-Digest"                -> cachedDigest.get(),
                    "X-Data-From"                  -> s"${cachedAt.get()}",
                    "X-Data-From-Cache"            -> "true"
                  )
                  .future
              }
            }
             */
            // if (env.clusterConfig.autoUpdateState) {
            if (Cluster.logger.isDebugEnabled)
              Cluster.logger.debug(
                s"[${env.clusterConfig.mode.name}] Sending state from auto cache (${Option(env.clusterLeaderAgent.cachedCount)
                  .getOrElse(0L)} items / ${Option(cachedValue).getOrElse(ByteString.empty).size / 1024} Kb) ..."
              )
            if (env.clusterConfig.streamed) {
              Ok.sendEntity(
                HttpEntity
                  .Streamed(env.clusterLeaderAgent.cachedState.chunks(32 * 1024), None, Some("application/x-ndjson"))
              ).withHeaders(
                "Otoroshi-Leader-Node-Name"    -> env.clusterConfig.leader.name,
                "Otoroshi-Leader-Node-Version" -> env.otoroshiVersion,
                "X-Data-Count"                 -> s"${env.clusterLeaderAgent.cachedCount}",
                "X-Data-Digest"                -> env.clusterLeaderAgent.cachedDigest,
                "X-Data-From"                  -> s"${env.clusterLeaderAgent.cachedTimestamp}",
                "X-Data-Auto"                  -> "true"
              ).vfuture
            } else {
              Ok.sendEntity(
                HttpEntity
                  .Strict(env.clusterLeaderAgent.cachedState, Some("application/x-ndjson"))
              ).withHeaders(
                "Otoroshi-Leader-Node-Name"    -> env.clusterConfig.leader.name,
                "Otoroshi-Leader-Node-Version" -> env.otoroshiVersion,
                "X-Data-Count"                 -> s"${env.clusterLeaderAgent.cachedCount}",
                "X-Data-Digest"                -> env.clusterLeaderAgent.cachedDigest,
                "X-Data-From"                  -> s"${env.clusterLeaderAgent.cachedTimestamp}",
                "X-Data-Auto"                  -> "true"
              ).vfuture
            }
            // } else if (cachedValue == null) {
            //   sendAndCache()
            // } else if (caching.get()) {
            //   Cluster.logger.debug(
            //     s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
            //   )
            //   Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
            //     .withHeaders(
            //       "Otoroshi-Leader-Node-Name"    -> env.clusterConfig.leader.name,
            //       "Otoroshi-Leader-Node-Version" -> env.otoroshiVersion,
            //       "X-Data-Count"                 -> s"${cachedCount.get()}",
            //       "X-Data-Digest"                -> cachedDigest.get(),
            //       "X-Data-From"                  -> s"${cachedAt.get()}",
            //       "X-Data-From-Cache"            -> "true"
            //     )
            //     .future
            // } else if ((cachedAt.get() + env.clusterConfig.leader.cacheStateFor) < System.currentTimeMillis()) {
            //   sendAndCache()
            // } else {
            //   Cluster.logger.debug(
            //     s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
            //   )
            //   Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
            //     .withHeaders(
            //       "Otoroshi-Leader-Node-Name"    -> env.clusterConfig.leader.name,
            //       "Otoroshi-Leader-Node-Version" -> env.otoroshiVersion,
            //       "X-Data-Count"                 -> s"${cachedCount.get()}",
            //       "X-Data-Digest"                -> cachedDigest.get(),
            //       "X-Data-From"                  -> s"${cachedAt.get()}",
            //       "X-Data-From-Cache"            -> "true"
            //     )
            //     .future
            // }
          }
        }
      }
    }

  def relayRouting() = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.clusterConfig.mode match {
        case Off => NotFound(Json.obj("error" -> "Cluster API not available")).future
        case _   => {
          val engine     = env.scriptManager.getAnyScript[RequestHandler](s"cp:${classOf[ProxyEngine].getName}").right.get
          val cookies    = ctx.request.headers
            .get("Otoroshi-Relay-Routing-Cookies")
            .map(c => Cookies.decodeCookieHeader(c))
            .getOrElse(Seq.empty[Cookie])
          val certs      = ctx.request.headers.headers
            .filter(_._1.startsWith("Otoroshi-Relay-Routing-Certs-"))
            .map { case (key, value) => (key.replace("Otoroshi-Relay-Routing-Certs-", "").toInt, value) }
            .sortWith((a, b) => a._1.compareTo(b._1) < 0)
            .map { case (_, value) =>
              value.trim.toCertificate
            }
            .applyOn { seq =>
              if (seq.isEmpty) {
                None
              } else {
                seq.some
              }
            }
          val request    = new RelayRoutingRequest(ctx.request, Cookies(cookies), certs)
          val routeName  = ctx.request.headers.get("Otoroshi-Relay-Routing-Route-Name").getOrElse("--")
          val callerName = ctx.request.headers.get("Otoroshi-Relay-Routing-Caller-Name").getOrElse("--")
          if (RelayRouting.logger.isDebugEnabled)
            RelayRouting.logger.debug(s"routing relay call to '${routeName}' from '${callerName}'")
          engine.handle(request, _ => Results.InternalServerError("bad default routing").vfuture).map { resp =>
            resp.copy(
              header = resp.header.copy(
                headers = resp.header.headers.map { case (key, value) =>
                  (s"Otoroshi-Relay-Routing-Response-Header-$key", value)
                }
              )
            )
          }
        }
      }
    }
  }
}
