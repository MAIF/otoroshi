package controllers.adminapi

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import actions.ApiAction
import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.util.ByteString
import cluster.{Cluster, ClusterAgent, ClusterMode, MemberView}
import com.google.common.io.Files
import env.Env
import models.PrivateAppsUser
import org.joda.time.DateTime
import otoroshi.models.RightsChecker
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents, Result}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}

class ClusterController(ApiAction: ApiAction, cc: ControllerComponents)(
  implicit env: Env
) extends AbstractController(cc) {

  import cluster.ClusterMode.{Leader, Off, Worker}

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val sourceBodyParser = BodyParser("ClusterController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def liveCluster() = ApiAction.async { ctx =>
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
        case Off => NotFound(Json.obj("error" -> "Cluster API not available")).future
        case Worker => NotFound(Json.obj("error" -> "Cluster API not available")).future
        case Leader => {
          val every = ctx.request.getQueryString("every").map(_.toInt).getOrElse(2000)
          val source = Source
            .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(every, TimeUnit.MILLISECONDS), NotUsed)
            .mapAsync(1) { _ =>
              for {
                members <- env.datastores.clusterStateDataStore.getMembers()
                inOut <- env.datastores.clusterStateDataStore.dataInAndOut()
              } yield (members, inOut)
            }
            .recover {
              case e =>
                Cluster.logger.error("Error", e)
                (Seq.empty[MemberView], (0L, 0L))
            }
            .map {
              case (members, inOut) =>
                val payloadIn: Long = inOut._1
                val payloadOut: Long = inOut._2
                val healths = members.map(healthOf)
                val foundOrange = healths.contains("orange")
                val foundRed = healths.contains("red")
                val health = if (foundRed) "red" else (if (foundOrange) "orange" else "green")
                Json.obj(
                  "workers" -> members.size,
                  "health" -> health,
                  "payloadIn" -> payloadIn,
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

  def getClusterMembers() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.clusterConfig.mode match {
        case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
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

  def clearClusterMembers() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.clusterConfig.mode match {
        case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
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

  def isSessionValid(sessionId: String) = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.clusterConfig.mode match {
        case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
        case Worker => {
          env.clusterAgent.isSessionValid(sessionId).map {
            case Some(user) => Ok(user.toJson)
            case None => NotFound(Json.obj("error" -> "Session not found"))
          }
        }
        case Leader => {
          Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] valid session $sessionId")
          env.datastores.privateAppsUserDataStore.findById(sessionId).map {
            case Some(user) => Ok(user.toJson)
            case None => NotFound(Json.obj("error" -> "Session not found"))
          }
        }
      }
    }
  }

  def createSession() = ApiAction.async(parse.json) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.clusterConfig.mode match {
        case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
        case Worker => {
          PrivateAppsUser.fmt.reads(ctx.request.body) match {
            case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad session format")))
            case JsSuccess(user, _) => {
              env.clusterAgent.createSession(user).map {
                case Some(session) => Created(session.toJson)
                case _ => InternalServerError(Json.obj("error" -> "Failed to create session on master"))
              }
            }
          }
        }
        case Leader => {
          Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] creating session")
          PrivateAppsUser.fmt.reads(ctx.request.body) match {
            case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad session format")))
            case JsSuccess(user, _) =>
              user.save(Duration(System.currentTimeMillis() - user.expiredAt.getMillis, TimeUnit.MILLISECONDS)).map {
                session =>
                  Created(session.toJson)
              }
          }
        }
      }
    }
  }

  def updateQuotas() = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.clusterConfig.mode match {
        case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
        case Worker => {
          ctx.request.body
            .via(env.clusterConfig.gunzip())
            .via(Framing.delimiter(ByteString("\n"), 32 * 1024 * 1024))
            .mapAsync(4) { item =>
              val jsItem = Json.parse(item.utf8String)
              (jsItem \ "typ").asOpt[String] match {
                case Some("globstats") => {
                  // TODO: membership + global stats ?
                  FastFuture.successful(())
                }
                case Some("srvincr") => {
                  val id = (jsItem \ "srv").asOpt[String].getOrElse("--")
                  val calls = (jsItem \ "c").asOpt[Long].getOrElse(0L)
                  val dataIn = (jsItem \ "di").asOpt[Long].getOrElse(0L)
                  val dataOut = (jsItem \ "do").asOpt[Long].getOrElse(0L)
                  env.clusterAgent.incrementService(id, dataIn, dataOut)
                  if (calls - 1 > 0) {
                    (0L to (calls - 1L)).foreach { _ =>
                      env.clusterAgent.incrementService(id, 0L, 0L)
                    }
                  }
                  FastFuture.successful(())
                }
                case Some("apkincr") => {
                  val id = (jsItem \ "apk").asOpt[String].getOrElse("--")
                  val increment = (jsItem \ "i").asOpt[Long].getOrElse(0L)
                  env.clusterAgent.incrementApi(id, increment)
                  FastFuture.successful(())
                }
                case _ => FastFuture.successful(())
              }
            }
            .runWith(Sink.ignore)
            .map { _ =>
              Ok(Json.obj("done" -> true))
            }
            .recover {
              case e =>
                Cluster.logger.error("Error while updating quotas", e)
                InternalServerError(Json.obj("error" -> e.getMessage))
            }
        }
        case Leader => {
          // Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] updating quotas")
          val budget: Long = ctx.request.getQueryString("budget").map(_.toLong).getOrElse(2000L)
          val start: Long = System.currentTimeMillis()
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
                (jsItem \ "typ").asOpt[String] match {
                  case Some("globstats") => {
                    ctx.request.headers
                      .get(ClusterAgent.OtoroshiWorkerNameHeader)
                      .map { name =>
                        env.datastores.clusterStateDataStore.registerMember(
                          MemberView(
                            name = name,
                            memberType = ClusterMode.Worker,
                            location = ctx.request.headers.get(ClusterAgent.OtoroshiWorkerLocationHeader).getOrElse("--"),
                            lastSeen = DateTime.now(),
                            timeout =
                              Duration(env.clusterConfig.worker.retries * env.clusterConfig.worker.state.pollEvery,
                                TimeUnit.MILLISECONDS),
                            stats = jsItem.as[JsObject]
                          )
                        )
                      }
                      .getOrElse(FastFuture.successful(()))
                  }
                  case Some("srvincr") => {
                    val id = (jsItem \ "srv").asOpt[String].getOrElse("--")
                    val calls = (jsItem \ "c").asOpt[Long].getOrElse(0L)
                    val dataIn = (jsItem \ "di").asOpt[Long].getOrElse(0L)
                    val dataOut = (jsItem \ "do").asOpt[Long].getOrElse(0L)
                    env.datastores.serviceDescriptorDataStore.findById(id).flatMap {
                      case Some(_) =>
                        env.datastores.serviceDescriptorDataStore
                          .updateIncrementableMetrics(id, calls, dataIn, dataOut, config)
                      case None => FastFuture.successful(())
                    }
                  }
                  case Some("apkincr") => {
                    val id = (jsItem \ "apk").asOpt[String].getOrElse("--")
                    val increment = (jsItem \ "i").asOpt[Long].getOrElse(0L)
                    env.datastores.apiKeyDataStore.findById(id).flatMap {
                      case Some(apikey) => env.datastores.apiKeyDataStore.updateQuotas(apikey, increment)
                      case None => FastFuture.successful(())
                    }
                  }
                  case _ => FastFuture.successful(())
                }

              }
              .runWith(Sink.ignore)
              .andThen {
                case _ =>
                  Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] updated quotas (${bytesCounter.get()} b)")
                  env.datastores.clusterStateDataStore.updateDataIn(bytesCounter.get())
                  if ((System.currentTimeMillis() - start) > budget) {
                    Cluster.logger.warn(
                      s"[${env.clusterConfig.mode.name}] Quotas update from worker ran over time budget, maybe the datastore is slow ?"
                    )
                  }
              }
              .map(_ => Ok(Json.obj("done" -> true)))
              .recover {
                case e =>
                  Cluster.logger.error("Error while updating quotas", e)
                  InternalServerError(Json.obj("error" -> e.getMessage))
              }
          }
        }
      }
    }
  }

  val caching   = new AtomicBoolean(false)
  val cachedAt  = new AtomicLong(0L)
  val cachedRef = new AtomicReference[ByteString]()

  def internalState() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.clusterConfig.mode match {
        case Off => NotFound(Json.obj("error" -> "Cluster API not available")).future
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
          val cachedValue = cachedRef.get()

          ctx.request.headers.get(ClusterAgent.OtoroshiWorkerNameHeader).map { name =>
            env.datastores.clusterStateDataStore.registerMember(
              MemberView(
                name = name,
                memberType = ClusterMode.Worker,
                location = ctx.request.headers.get(ClusterAgent.OtoroshiWorkerLocationHeader).getOrElse("--"),
                lastSeen = DateTime.now(),
                timeout = Duration(env.clusterConfig.worker.retries * env.clusterConfig.worker.state.pollEvery,
                  TimeUnit.MILLISECONDS)
              )
            )
          }

          def sendAndCache(): Future[Result] = {
            // Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Exporting raw state")
            if (caching.compareAndSet(false, true)) {
              val start: Long = System.currentTimeMillis()
              // var stateCache = ByteString.empty
              env.datastores
                .rawExport(env.clusterConfig.leader.groupingBy)
                .map { item =>
                  ByteString(Json.stringify(item) + "\n")
                }
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
                .runWith(Sink.fold(ByteString.empty)(_ ++ _)).fold {
                  case Success(stateCache) => {
                    if ((System.currentTimeMillis() - start) > budget) {
                      Cluster.logger.warn(
                        s"[${env.clusterConfig.mode.name}] Datastore export to worker ran over time budget, maybe the datastore is slow ?"
                      )
                    }
                    cachedRef.set(stateCache)
                    cachedAt.set(System.currentTimeMillis())
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
                      "X-Data-From" -> s"${System.currentTimeMillis()}",
                      "X-Data-Fresh" -> "true"
                    ) //.withHeaders("Content-Encoding" -> "gzip")
                  }
                  case Failure(err) =>
                    Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Stream error while exporting raw state", err)
                    InternalServerError(Json.obj("error" -> "Stream error while exporting raw state", "message" -> err.getMessage))
                }
            } else {
              Cluster.logger.debug(
                s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
              )
              Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson"))).withHeaders(
                "X-Data-From" -> s"${cachedAt.get()}",
                "X-Data-From-Cache" -> "true"
              ).future
            }
          }

          if (env.clusterConfig.autoUpdateState) {
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Sending state from auto cache (${cachedValue.size / 1024} Kb) ..."
            )
            Ok.sendEntity(
              HttpEntity.Streamed(Source.single(env.clusterLeaderAgent.cachedState), None, Some("application/x-ndjson"))
            )
              .withHeaders(
                "X-Data-From" -> s"${env.clusterLeaderAgent.cachedTimestamp}",
                "X-Data-Auto" -> "true"
              ).future
          } else if (cachedValue == null) {
            sendAndCache()
          } else if (caching.get()) {
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
            )
            Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
              .withHeaders(
                "X-Data-From" -> s"${cachedAt.get()}",
                "X-Data-From-Cache" -> "true"
              ).future
          } else if ((cachedAt.get() + env.clusterConfig.leader.cacheStateFor) < System.currentTimeMillis()) {
            sendAndCache()
          } else {
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
            )
            Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
              .withHeaders(
                "X-Data-From" -> s"${cachedAt.get()}",
                "X-Data-From-Cache" -> "true"
              ).future
          }
        }
      }
    }
  }
}
