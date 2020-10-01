package utils

import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import env.Env
import models.{Exporter, GlobalConfig}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSAuthScheme
import utils.http.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NoneMailerSettings() extends MailerSettings with Exporter {
  override def typ: String                                      = "none"
  override def asMailer(config: GlobalConfig, env: Env): Mailer = new NoneMailer()
  override def json: JsValue                                    = NoneMailerSettings.format.writes(this)
  override def toJson: JsValue                                  = NoneMailerSettings.format.writes(this)
  override def to: Seq[EmailLocation] = Seq.empty
}

case class ConsoleMailerSettings() extends MailerSettings with Exporter {
  override def typ: String                                      = "console"
  override def asMailer(config: GlobalConfig, env: Env): Mailer = new LogMailer()
  override def json: JsValue                                    = ConsoleMailerSettings.format.writes(this)
  override def toJson: JsValue                                  = ConsoleMailerSettings.format.writes(this)
  override def to: Seq[EmailLocation] = Seq.empty
}

case class MailjetSettings(apiKeyPublic: String, apiKeyPrivate: String, to: Seq[EmailLocation]) extends MailerSettings with Exporter {
  override def typ: String                                      = "mailjet"
  override def asMailer(config: GlobalConfig, env: Env): Mailer = new MailjetMailer(env, config)
  override def json: JsValue                                    = MailjetSettings.format.writes(this)
  override def toJson: JsValue                                  = MailjetSettings.format.writes(this)
}

case class MailgunSettings(eu: Boolean, apiKey: String, domain: String, to: Seq[EmailLocation]) extends MailerSettings with Exporter {
  override def typ: String                                      = "mailgun"
  override def asMailer(config: GlobalConfig, env: Env): Mailer = new MailgunMailer(env, config)
  override def json: JsValue                                    = MailgunSettings.format.writes(this)
  override def toJson: JsValue                                  = MailgunSettings.format.writes(this)
}

case class SendgridSettings(apiKey: String, to: Seq[EmailLocation]) extends MailerSettings with Exporter {
  override def typ: String                                      = "sendgrid"
  override def asMailer(config: GlobalConfig, env: Env): Mailer = new SendgridMailer(env, config)
  override def json: JsValue                                    = SendgridSettings.format.writes(this)
  override def toJson: JsValue                                    = SendgridSettings.format.writes(this)
}

case class GenericMailerSettings(url: String, headers: Map[String, String], to: Seq[EmailLocation]) extends MailerSettings with Exporter {
  override def typ: String                                      = "generic"
  override def asMailer(config: GlobalConfig, env: Env): Mailer = new GenericMailer(env, config)
  override def json: JsValue                                    = GenericMailerSettings.format.writes(this)
  override def toJson: JsValue                                    = GenericMailerSettings.format.writes(this)
}

trait MailerSettings extends Exporter {
  def typ: String
  def to: Seq[EmailLocation]
  def asMailer(config: GlobalConfig, env: Env): Mailer
  def genericSettings: Option[GenericMailerSettings] = this match {
    case _: GenericMailerSettings => Some(this.asInstanceOf[GenericMailerSettings])
    case _                        => None
  }
  def consoleSettings: Option[ConsoleMailerSettings] = this match {
    case _: ConsoleMailerSettings => Some(this.asInstanceOf[ConsoleMailerSettings])
    case _                        => None
  }
  def mailgunSettings: Option[MailgunSettings] = this match {
    case _: MailgunSettings => Some(this.asInstanceOf[MailgunSettings])
    case _                  => None
  }
  def mailjetSettings: Option[MailjetSettings] = this match {
    case _: MailjetSettings => Some(this.asInstanceOf[MailjetSettings])
    case _                  => None
  }
  def sendgridSettings: Option[SendgridSettings] = this match {
    case _: SendgridSettings => Some(this.asInstanceOf[SendgridSettings])
    case _                  => None
  }
  def json: JsValue
  def toJson: JsValue
}

object MailerSettings {
  val format = new Format[MailerSettings] {
    override def reads(json: JsValue): JsResult[MailerSettings] =
      (json \ "type").asOpt[String].getOrElse("none") match {
        case "none"     => NoneMailerSettings.format.reads(json)
        case "console"  => ConsoleMailerSettings.format.reads(json)
        case "generic"  => GenericMailerSettings.format.reads(json)
        case "mailgun"  => MailgunSettings.format.reads(json)
        case "mailjet"  => MailjetSettings.format.reads(json)
        case "sendgrid" => SendgridSettings.format.reads(json)
        case _          => ConsoleMailerSettings.format.reads(json)
      }
    override def writes(o: MailerSettings): JsValue = o.json
  }
}

object MailgunSettings {
  val format = new Format[MailgunSettings] {
    override def writes(o: MailgunSettings) = Json.obj(
      "type"   -> o.typ,
      "eu"     -> o.eu,
      "apiKey" -> o.apiKey,
      "domain" -> o.domain,
      "to" -> JsArray(o.to.map(_.json))
    )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          MailgunSettings(
            eu = (json \ "eu").asOpt[Boolean].getOrElse(false),
            apiKey = (json \ "apiKey").asOpt[String].map(_.trim).get,
            domain = (json \ "domain").asOpt[String].map(_.trim).get,
            to = (json \ "to").asOpt[Seq[JsValue]].map(_.map(v => EmailLocation.format.reads(v)).collect { case JsSuccess(v, _) => v }).getOrElse(Seq.empty)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

object MailjetSettings {
  val format = new Format[MailjetSettings] {
    override def writes(o: MailjetSettings) = Json.obj(
      "type"          -> o.typ,
      "apiKeyPublic"  -> o.apiKeyPublic,
      "apiKeyPrivate" -> o.apiKeyPrivate,
      "to" -> JsArray(o.to.map(_.json))
    )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          MailjetSettings(
            apiKeyPrivate = (json \ "apiKeyPrivate").asOpt[String].map(_.trim).get,
            apiKeyPublic = (json \ "apiKeyPublic").asOpt[String].map(_.trim).get,
            to = (json \ "to").asOpt[Seq[JsValue]].map(_.map(v => EmailLocation.format.reads(v)).collect { case JsSuccess(v, _) => v }).getOrElse(Seq.empty)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

object SendgridSettings {
  val format = new Format[SendgridSettings] {
    override def writes(o: SendgridSettings) = Json.obj(
      "type"          -> o.typ,
      "apiKey"  -> o.apiKey,
      "to" -> JsArray(o.to.map(_.json))
    )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          SendgridSettings(
            apiKey = (json \ "apiKey").asOpt[String].map(_.trim).get,
            to = (json \ "to").asOpt[Seq[JsValue]].map(_.map(v => EmailLocation.format.reads(v)).collect { case JsSuccess(v, _) => v }).getOrElse(Seq.empty)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

object GenericMailerSettings {
  val format = new Format[GenericMailerSettings] {
    override def writes(o: GenericMailerSettings) = Json.obj(
      "type"    -> o.typ,
      "url"     -> o.url,
      "headers" -> o.headers,
      "to" -> JsArray(o.to.map(_.json))
    )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          GenericMailerSettings(
            url = (json \ "url").asOpt[String].map(_.trim).get,
            headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
            to = (json \ "to").asOpt[Seq[JsValue]].map(_.map(v => EmailLocation.format.reads(v)).collect { case JsSuccess(v, _) => v }).getOrElse(Seq.empty)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

object ConsoleMailerSettings {
  val format = new Format[ConsoleMailerSettings] {
    override def writes(o: ConsoleMailerSettings) = Json.obj()
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          ConsoleMailerSettings()
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

object NoneMailerSettings {
  val format = new Format[NoneMailerSettings] {
    override def writes(o: NoneMailerSettings) = Json.obj()
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          NoneMailerSettings()
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

case class EmailLocation(name: String, email: String) {
  def toEmailString: String = s"$name <$email>"
  def json: JsValue = EmailLocation.format.writes(this)
}

object EmailLocation {
  val format = new Format[EmailLocation] {
    override def writes(o: EmailLocation): JsValue = Json.obj("name" -> o.name, "email" -> o.email)
    override def reads(json: JsValue): JsResult[EmailLocation] = Try {
      EmailLocation(
        name = (json \ "name").as[String],
        email = (json \ "email").as[String],
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(value) => JsError(value.getMessage)
    }
  }
}

trait Mailer {
  def send(from: EmailLocation, to: Seq[EmailLocation], subject: String, html: String)(
      implicit ec: ExecutionContext
  ): Future[Unit]
}

object LogMailer {
  def apply() = new LogMailer()
}

class NoneMailer() extends Mailer {
  def send(from: EmailLocation, to: Seq[EmailLocation], subject: String, html: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    FastFuture.successful(())
  }
}

class LogMailer() extends Mailer {

  lazy val logger = Logger("otoroshi-console-mailer")

  def send(from: EmailLocation, to: Seq[EmailLocation], subject: String, html: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    val email = Json.prettyPrint(
      Json.obj(
        "action"  -> "sent-email",
        "from"    -> from.toEmailString,
        "to"      -> Seq(to.map(_.toEmailString).mkString(", ")),
        "subject" -> subject,
        "html"    -> html
      )
    )
    logger.info(email)
    FastFuture.successful(())
  }
}

class MailgunMailer(env: Env, config: GlobalConfig) extends Mailer {

  lazy val logger = Logger("otoroshi-mailgun-mailer")

  def send(from: EmailLocation, to: Seq[EmailLocation], subject: String, html: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    val fu = config.mailerSettings.flatMap(_.mailgunSettings).map { mailgunSettings =>
      env.Ws // no need for mtls here
        .url(mailgunSettings.eu match {
          case true  => s"https://api.eu.mailgun.net/v3/${mailgunSettings.domain}/messages"
          case false => s"https://api.mailgun.net/v3/${mailgunSettings.domain}/messages"
        })
        .withAuth("api", mailgunSettings.apiKey, WSAuthScheme.BASIC)
        .withMaybeProxyServer(config.proxies.alertEmails)
        .post(
          Map(
            "from"    -> Seq(from.toEmailString),
            "to"      -> Seq(to.map(_.email).mkString(", ")),
            "subject" -> Seq(subject),
            "html"    -> Seq(html)
          )
        )
        .map(_.ignore()(env.otoroshiMaterializer))
    } getOrElse {
      FastFuture.successful(())
    }
    fu.andThen {
        case Success(res) => logger.debug("Alert email sent")
        case Failure(e)   => logger.error("Error while sending alert email", e)
      }
      .fast
      .map(_ => ())
  }
}

class MailjetMailer(env: Env, config: GlobalConfig) extends Mailer {

  lazy val logger = Logger("otoroshi-mailjet-mailer")

  def send(from: EmailLocation, to: Seq[EmailLocation], subject: String, html: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    val fu = config.mailerSettings.flatMap(_.mailjetSettings).map { settings =>
      env.Ws // no need for mtls here
        .url(s"https://api.mailjet.com/v3.1/send")
        .withAuth(settings.apiKeyPublic, settings.apiKeyPrivate, WSAuthScheme.BASIC)
        .withHttpHeaders("Content-Type" -> "application/json")
        .post(
          Json.obj(
            "Messages" -> Json.arr(
              Json.obj(
                "From" -> Json.obj(
                  "Email" -> from.email,
                  "Name"  -> from.name
                ),
                "To" -> JsArray(
                  to.map(
                    t =>
                      Json.obj(
                        "Email" -> t.email,
                        "Name"  -> t.name
                    )
                  )
                ),
                "Subject"  -> subject,
                "HTMLPart" -> html
                // TextPart
              )
            )
          )
        )
        .map(_.ignore()(env.otoroshiMaterializer))
    } getOrElse {
      FastFuture.successful(())
    }
    fu.andThen {
        case Success(res) => logger.info("Alert email sent")
        case Failure(e)   => logger.error("Error while sending alert email", e)
      }
      .fast
      .map(_ => ())
  }
}

class SendgridMailer(env: Env, config: GlobalConfig) extends Mailer {

  lazy val logger = Logger("otoroshi-sendgrid-mailer")

  def send(from: EmailLocation, to: Seq[EmailLocation], subject: String, html: String)(
    implicit ec: ExecutionContext
  ): Future[Unit] = {
    val fu = config.mailerSettings.flatMap(_.sendgridSettings).map { settings =>
      env.Ws // no need for mtls here
        .url(s"https://api.sendgrid.com/v3/mail/send")
        .withHttpHeaders(
          "Authorization" -> s"Bearer ${settings.apiKey}",
          "Content-Type" -> "application/json"
        )
        .post(Json.obj(
          "personalizations" -> Json.arr(
              Json.obj(
                "to" -> to.map(c =>
                  Json.obj(
                    "email" -> c.email,
                    "name" -> c.name
                  )
                ),
                "subject" -> subject
            )
          ),
          "from" -> Json.obj(
            "email" -> from.email,
            "name" -> from.name,
          ),
          "content" -> Json.arr(
            Json.obj(
              "type" -> "text/html",
              "value" -> html
            )
          )
        ))
        .map(_.ignore()(env.otoroshiMaterializer))
    } getOrElse {
      FastFuture.successful(())
    }
    fu.andThen {
      case Success(res) => logger.info("Alert email sent")
      case Failure(e)   => logger.error("Error while sending alert email", e)
    }
      .fast
      .map(_ => ())
  }
}

class GenericMailer(env: Env, config: GlobalConfig) extends Mailer {

  lazy val logger = Logger("otoroshi-generic-mailer")

  def send(from: EmailLocation, to: Seq[EmailLocation], subject: String, html: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    val fu = config.mailerSettings.flatMap(_.genericSettings).map { settings =>
      env.Ws // no need for mtls here
        .url(settings.url)
        .withHttpHeaders("Content-Type" -> "application/json")
        .addHttpHeaders(settings.headers.toSeq: _*)
        .post(
          Json.obj(
            "from" -> Json.obj(
              "email" -> from.email,
              "name"  -> from.name
            ),
            "to" -> JsArray(
              to.map(
                t =>
                  Json.obj(
                    "email" -> t.email,
                    "name"  -> t.name
                )
              )
            ),
            "subject" -> subject,
            "html"    -> html
          )
        )
        .map(_.ignore()(env.otoroshiMaterializer))
    } getOrElse {
      FastFuture.successful(())
    }
    fu.andThen {
        case Success(res) => logger.info("Alert email sent")
        case Failure(e)   => logger.error("Error while sending alert email", e)
      }
      .fast
      .map(_ => ())
  }
}
