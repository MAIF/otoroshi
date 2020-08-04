package events

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Terminated}
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.{OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Keep, Sink, Source}
import env.Env
import events.impl.{ElasticWritesAnalytics, WebHookAnalytics}
import models.{DataExporter, ElasticAnalyticsConfig, Webhook}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json
import utils.{ConsoleMailerSettings, EmailLocation, MailerSettings}

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

object OtoroshiEventsActorSupervizer {
  def props(implicit env: Env) = Props(new OtoroshiEventsActorSupervizer(env))
}

class OtoroshiEventsActorSupervizer(env: Env) extends Actor {

  lazy val logger    = Logger("otoroshi-events-actor-supervizer")

  implicit val e = env
  implicit val ec  = env.analyticsExecutionContext

  val namesAndRefs: Map[ActorRef, Tuple2[String, DataExporter]] = Map.empty

  override def receive: Receive = {
    case Terminated(ref) =>
      logger.debug(s"Restarting ototoshi events actor child ${namesAndRefs(ref)._1}")
      context.watch(context.actorOf(AnalyticsActor.props(namesAndRefs(ref)._2)(env), namesAndRefs(ref)._1))
    case evt => context.children.map(_ ! evt)
  }

  override def preStart(): Unit = {
    env.datastores.globalConfigDataStore.singleton().fast.map { config =>
      config.dataExporters.foreach(exporter => {
        val childName = s"analytics-actor-${exporter.id}"
        if (context.child(childName).isEmpty) {
          logger.debug(s"Starting new child $childName")
          val ref = context.actorOf(AnalyticsActor.props(exporter)(env), childName)
          namesAndRefs + (ref -> (childName -> exporter))
          context.watch(ref)
        }
      })

    }
  }

  override def postStop(): Unit =
    context.children.foreach(_ ! PoisonPill)
}


object OtoroshiEventsActor {
  def props(exporter: DataExporter)(implicit env: Env) = Props(new OtoroshiEventsActor(exporter))
}

class OtoroshiEventsActor(exporter: DataExporter)(implicit env: Env) extends Actor {

  implicit lazy val ec = env.analyticsExecutionContext
  lazy val kafkaWrapper = new KafkaWrapper(env.analyticsActorSystem, env, _.topic)

  lazy val logger = Logger("otoroshi-events-actor")

  lazy val stream = Source
    .queue[AnalyticEvent](50000, OverflowStrategy.dropHead)
    .filter(event => exporter.eventsFilters
      .map(utils.RegexPool(_))
      .exists(r => r.matches(event.`@type`))
    )
    .mapAsync(5)(evt => evt.toEnrichedJson)
    .groupedWithin(env.maxWebhookSize, FiniteDuration(env.analyticsWindow, TimeUnit.SECONDS)) //todo: maybe change value
    .mapAsync(5) { evts =>
      logger.debug(s"SEND_OTOROSHI_EVENTS_HOOK: will send ${evts.size} evts")
      env.datastores.globalConfigDataStore.singleton().fast.map { config =>

        exporter.config match {
          case c: ElasticAnalyticsConfig => new ElasticWritesAnalytics(c, env).publish(evts)
          case c: Webhook => new WebHookAnalytics(c, config).publish(evts)
          case c: KafkaConfig => evts.foreach (evt => kafkaWrapper.publish(evt)(env, c))
          case c: MailerSettings =>
            //todo: rewrites email body
            val titles = evts
              .map { jsonEvt =>
                val date = new DateTime((jsonEvt \ "@timestamp").as[Long])
                val id   = (jsonEvt \ "@id").as[String]
                s"""<li><a href="#$id">""" + (jsonEvt \ "alert")
                  .asOpt[String]
                  .getOrElse("Unkown alert") + s" - ${date.toString()}</a></li>"
              }
              .mkString("<ul>", "\n", "</ul>")

            val email = evts
              .map { jsonEvt =>
                val alert   = (jsonEvt \ "alert").asOpt[String].getOrElse("Unkown alert")
                val date    = new DateTime((jsonEvt \ "@timestamp").as[Long])
                val id      = (jsonEvt \ "@id").as[String]
                s"""<h3 id="$id">$alert - ${date.toString()}</h3><pre>${Json.prettyPrint(jsonEvt)}</pre><br/>"""
              }
              .mkString("\n")

            val emailBody =
              s"""<p>${evts.size} new events occured on Otoroshi, you can visualize it on the <a href="${env.rootScheme}${env.backOfficeHost}/">Otoroshi Dashboard</a></p>
                 |$titles
                 |$email
                 """
            c.asMailer(config, env).send(
              from = EmailLocation("Otoroshi Alerts", s"otoroshi-alerts@${env.domain}"),
              to = config.alertsEmails.map(e => EmailLocation(e, e)),
              subject = s"Otoroshi Alert - ${evts.size} new alerts",
              html = emailBody
            )
        }
      }
    }

  lazy val (queue, done) = stream.toMat(Sink.ignore)(Keep.both).run()(env.analyticsMaterializer)

  override def receive: Receive = {
    case ge: AnalyticEvent => {
      logger.debug("OTOROSHI_EVENTS: Event sent to stream")
      val myself = self
      queue.offer(ge).andThen {
        case Success(QueueOfferResult.Enqueued) => logger.debug("OTOROSHI_EVENT: Event enqueued")
        case Success(QueueOfferResult.Dropped) =>
          logger.error("OTOROSHI_EVENTS_ERROR: Enqueue Dropped otoroshiEvents :(")
        case Success(QueueOfferResult.QueueClosed) =>
          logger.error("OTOROSHI_EVENTS_ERROR: Queue closed :(")
          context.stop(myself)
        case Success(QueueOfferResult.Failure(t)) =>
          logger.error("OTOROSHI_EVENTS_ERROR: Enqueue Failure otoroshiEvents :(", t)
          context.stop(myself)
        case e =>
          logger.error(s"OTOROSHI_EVENTS_ERROR: otoroshiEvents actor error : ${e}")
          context.stop(myself)
      }
      //todo: on peu logger les analytics ==> y penser
      env.datastores.globalConfigDataStore.latestSafe.filter(_.logAnalyticsOnServer).foreach(_ => ge.log())
    }
    case _ =>
  }
}

