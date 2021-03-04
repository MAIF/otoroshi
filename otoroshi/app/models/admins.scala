package otoroshi.models

import otoroshi.env.Env
import org.joda.time.DateTime
import otoroshi.models._
import play.api.libs.json._
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait OtoroshiAdminType {
  def name: String
  def json: JsValue = name.json
}
object OtoroshiAdminType {
  case object SimpleAdmin extends OtoroshiAdminType {
    def name: String = "SIMPLE"
  }
  case object WebAuthnAdmin extends OtoroshiAdminType {
    def name: String = "WEBAUTHN"
  }
  def fromJson(jsValue: JsValue): Option[OtoroshiAdminType] = {
    jsValue.asOpt[String].flatMap {
      case "simple" =>   SimpleAdmin.some
      case "SIMPLE" =>   SimpleAdmin.some
      case "webauthn" => WebAuthnAdmin.some
      case "WEBAUTHN" => WebAuthnAdmin.some
      case _ => None
    }
  }
}

trait OtoroshiAdmin extends EntityLocationSupport {
  def username: String
  def password: String
  def label: String
  def createdAt: DateTime
  def typ: OtoroshiAdminType
  def metadata: Map[String, String]
  def json: JsValue
  def rights: UserRights
}

case class SimpleOtoroshiAdmin(
                                username: String,
                                password: String,
                                label: String,
                                createdAt: DateTime,
                                typ: OtoroshiAdminType,
                                metadata: Map[String, String],
                                rights: UserRights,
                                location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends OtoroshiAdmin {
  def internalId: String = username
  def json: JsValue = location.jsonWithKey ++ Json.obj(
    "username" -> username,
    "password" -> password,
    "label" -> label,
    "createdAt" -> createdAt.getMillis,
    "type" -> typ.json,
    "metadata" -> metadata,
    "rights" -> rights.json
  )
}

object SimpleOtoroshiAdmin {
  val fmt: Format[SimpleOtoroshiAdmin] = new Format[SimpleOtoroshiAdmin] {
    override def writes(o: SimpleOtoroshiAdmin): JsValue = o.json
    override def reads(json: JsValue): JsResult[SimpleOtoroshiAdmin] = SimpleOtoroshiAdmin.reads(json)
  }
  def reads(json: JsValue): JsResult[SimpleOtoroshiAdmin] = {
    Try {
      SimpleOtoroshiAdmin(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        username = (json \ "username").as[String],
        password = (json \ "password").as[String],
        label = (json \ "label").as[String],
        createdAt = (json \ "createdAt").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        typ = (json \ "typ").asOpt[JsValue].flatMap(OtoroshiAdminType.fromJson).getOrElse(OtoroshiAdminType.SimpleAdmin),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        rights = UserRights.readFromObject(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
}

case class WebAuthnOtoroshiAdmin(
                                  username: String,
                                  password: String,
                                  label: String,
                                  handle: String,
                                  credentials: Map[String, JsValue],
                                  createdAt: DateTime,
                                  typ: OtoroshiAdminType,
                                  metadata: Map[String, String],
                                  rights: UserRights,
                                  location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends OtoroshiAdmin {
  def internalId: String = username
  def json: JsValue = location.jsonWithKey ++ Json.obj(
    "username" -> username,
    "password" -> password,
    "label" -> label,
    "handle" -> handle,
    "credentials" -> JsObject(credentials),
    "createdAt" -> createdAt.getMillis,
    "type" -> typ.json,
    "metadata" -> metadata,
    "rights" -> rights.json
  )
}

object WebAuthnOtoroshiAdmin {
  val fmt: Format[WebAuthnOtoroshiAdmin] = new Format[WebAuthnOtoroshiAdmin] {
    override def writes(o: WebAuthnOtoroshiAdmin): JsValue = o.json
    override def reads(json: JsValue): JsResult[WebAuthnOtoroshiAdmin] = WebAuthnOtoroshiAdmin.reads(json)
  }
  def reads(json: JsValue): JsResult[WebAuthnOtoroshiAdmin] = {
    Try {
      WebAuthnOtoroshiAdmin(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        username = (json \ "username").as[String],
        password = (json \ "password").as[String],
        label = (json \ "label").as[String],
        handle = (json \ "handle").as[String],
        credentials = (json \ "credentials").asOpt[Map[String, JsValue]]
          .orElse((json \ "credential").asOpt[JsValue].map(v => Map((v \ "keyId" \ "id").as[String] -> v)))
          .getOrElse(Map.empty),
        createdAt = (json \ "createdAt").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        typ = (json \ "typ").asOpt[JsValue].flatMap(OtoroshiAdminType.fromJson).getOrElse(OtoroshiAdminType.WebAuthnAdmin),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        rights = UserRights.readFromObject(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
}

object OtoroshiAdmin {
  val format = new Format[OtoroshiAdmin] {
    override def writes(o: OtoroshiAdmin): JsValue = o.json
    override def reads(json: JsValue): JsResult[OtoroshiAdmin] = {
      (json \ "type").asOpt[String] match {
        case Some("SIMPLE")   => SimpleOtoroshiAdmin.reads(json)
        case Some("WEBAUTHN") => WebAuthnOtoroshiAdmin.reads(json)
        case _                => SimpleOtoroshiAdmin.reads(json)
      }
    }
  }
}

trait SimpleAdminDataStore {
  def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[SimpleOtoroshiAdmin]]
  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[SimpleOtoroshiAdmin]]
  def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def deleteUsers(usernames: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Long]
  def registerUser(user: SimpleOtoroshiAdmin)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
}

trait WebAuthnAdminDataStore {
  def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[WebAuthnOtoroshiAdmin]]
  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[WebAuthnOtoroshiAdmin]]
  def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def deleteUsers(usernames: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Long]
  def registerUser(user: WebAuthnOtoroshiAdmin)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
}
