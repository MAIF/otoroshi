package otoroshi.models

import otoroshi.env.Env
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models._
import otoroshi.utils.{JsonPathValidator, JsonValidator}
import play.api.libs.json._
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait OtoroshiAdminType  {
  def name: String
  def json: JsValue = name.json
}

case object OtoroshiAdminTypeSimpleAdmin extends OtoroshiAdminType {
  def name: String = "SIMPLE"
}

case object OtoroshiAdminTypeWebAuthnAdmin extends OtoroshiAdminType {
  def name: String = "WEBAUTHN"
}
object OtoroshiAdminType {

  val SimpleAdmin = OtoroshiAdminTypeSimpleAdmin
  val WebAuthnAdmin = OtoroshiAdminTypeWebAuthnAdmin

  def fromJson(jsValue: JsValue): Option[OtoroshiAdminType] = {
    jsValue.asOpt[String].flatMap {
      case "simple"   => SimpleAdmin.some
      case "SIMPLE"   => SimpleAdmin.some
      case "webauthn" => WebAuthnAdmin.some
      case "WEBAUTHN" => WebAuthnAdmin.some
      case _          => None
    }
  }
}

trait OtoroshiAdmin extends EntityLocationSupport {
  def username: String
  def password: String
  def label: String
  def createdAt: DateTime
  def typ: OtoroshiAdminType
  def tags: Seq[String]
  def metadata: Map[String, String]
  def json: JsValue
  def rights: UserRights
  def isSimple: Boolean
  def isWebAuthn: Boolean
  def adminEntityValidators: Map[String, Seq[JsonValidator]]
}

case class SimpleOtoroshiAdmin(
    username: String,
    password: String,
    label: String,
    createdAt: DateTime,
    typ: OtoroshiAdminType,
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String],
    rights: UserRights,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
    adminEntityValidators: Map[String, Seq[JsonValidator]]
) extends OtoroshiAdmin {
  val isSimple                         = true
  val isWebAuthn                       = false
  def internalId: String               = username
  def theDescription: String           = label
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = username
  def theTags: Seq[String]             = tags
  def json: JsValue                    =
    location.jsonWithKey ++ Json.obj(
      "username"              -> username,
      "password"              -> password,
      "label"                 -> label,
      "createdAt"             -> createdAt.getMillis,
      "type"                  -> typ.json,
      "metadata"              -> metadata,
      "tags"                  -> JsArray(tags.map(JsString.apply)),
      "rights"                -> rights.json,
      "adminEntityValidators" -> adminEntityValidators.mapValues(v => JsArray(v.map(_.json)))
    )
}

object SimpleOtoroshiAdmin {
  val fmt: Format[SimpleOtoroshiAdmin] = new Format[SimpleOtoroshiAdmin] {
    override def writes(o: SimpleOtoroshiAdmin): JsValue             = o.json
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
        typ =
          (json \ "type").asOpt[JsValue].flatMap(OtoroshiAdminType.fromJson).getOrElse(OtoroshiAdminType.SimpleAdmin),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        rights = UserRights.readFromObject(json),
        adminEntityValidators = json
          .select("adminEntityValidators")
          .asOpt[JsObject]
          .map { obj =>
            obj.value.mapValues { arr =>
              arr.asArray.value
                .map { item =>
                  JsonValidator.format.reads(item)
                }
                .collect { case JsSuccess(v, _) =>
                  v
                }
            }.toMap
          }
          .getOrElse(Map.empty[String, Seq[JsonValidator]])
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
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String],
    rights: UserRights,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
    adminEntityValidators: Map[String, Seq[JsonValidator]]
) extends OtoroshiAdmin {
  val isSimple                         = false
  val isWebAuthn                       = true
  def internalId: String               = username
  def theDescription: String           = label
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = username
  def theTags: Seq[String]             = tags
  def json: JsValue                    =
    location.jsonWithKey ++ Json.obj(
      "username"              -> username,
      "password"              -> password,
      "label"                 -> label,
      "handle"                -> handle,
      "credentials"           -> JsObject(credentials),
      "createdAt"             -> createdAt.getMillis,
      "type"                  -> typ.json,
      "metadata"              -> metadata,
      "tags"                  -> JsArray(tags.map(JsString.apply)),
      "rights"                -> rights.json,
      "adminEntityValidators" -> adminEntityValidators.mapValues(v => JsArray(v.map(_.json)))
    )
}

object WebAuthnOtoroshiAdmin {
  val fmt: Format[WebAuthnOtoroshiAdmin] = new Format[WebAuthnOtoroshiAdmin] {
    override def writes(o: WebAuthnOtoroshiAdmin): JsValue             = o.json
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
        credentials = (json \ "credentials")
          .asOpt[Map[String, JsValue]]
          .orElse((json \ "credential").asOpt[JsValue].map(v => Map((v \ "keyId" \ "id").as[String] -> v)))
          .getOrElse(Map.empty),
        createdAt = (json \ "createdAt").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        typ =
          (json \ "type").asOpt[JsValue].flatMap(OtoroshiAdminType.fromJson).getOrElse(OtoroshiAdminType.WebAuthnAdmin),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        rights = UserRights.readFromObject(json),
        adminEntityValidators = json
          .select("adminEntityValidators")
          .asOpt[JsObject]
          .map { obj =>
            obj.value.mapValues { arr =>
              arr.asArray.value
                .map { item =>
                  JsonValidator.format.reads(item)
                }
                .collect { case JsSuccess(v, _) =>
                  v
                }
            }.toMap
          }
          .getOrElse(Map.empty[String, Seq[JsonValidator]])
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
  def key(id: String): String
  def extractId(value: SimpleOtoroshiAdmin): String
  def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[SimpleOtoroshiAdmin]]
  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[SimpleOtoroshiAdmin]]
  def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def deleteUsers(usernames: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Long]
  def registerUser(user: SimpleOtoroshiAdmin)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def template(env: Env): SimpleOtoroshiAdmin = {
    SimpleOtoroshiAdmin(
      username = "new.admin@foo.bar",
      password = BCrypt.hashpw("password", BCrypt.gensalt()),
      label = "new admin",
      createdAt = DateTime.now(),
      typ = OtoroshiAdminType.SimpleAdmin,
      metadata = Map.empty,
      rights = UserRights.default,
      adminEntityValidators = Map.empty
    )
  }
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

case class AdminPreferences(userId: String, preferences: Map[String, JsValue]) {
  def json: JsValue = AdminPreferences.format.writes(this)
  def set(id: String, value: JsValue): AdminPreferences = {
    copy(preferences = preferences ++ Map(id -> value))
  }
  def delete(id: String): AdminPreferences = {
    copy(preferences = preferences - id)
  }
  def save()(implicit env: Env, ec: ExecutionContext): Future[AdminPreferences] = {
    env.datastores.adminPreferencesDatastore.setPreferences(userId, this)
  }
}

object AdminPreferences {
  val format = new Format[AdminPreferences] {
    override def reads(json: JsValue): JsResult[AdminPreferences] = Try {
      AdminPreferences(
        userId = json.select("user_id").asString,
        preferences = json.select("preferences").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
      )
    } match {
      case Success(prefs) => JsSuccess(prefs)
      case Failure(err)   => JsError(err.getMessage)
    }
    override def writes(o: AdminPreferences): JsValue             = Json.obj(
      "user_id"     -> o.userId,
      "preferences" -> o.preferences
    )
  }
  def defaultValue(id: String): AdminPreferences = {
    AdminPreferences(
      userId = id,
      preferences = Map(
        "backoffice_sidebar_shortcuts" -> Json.arr(
          "routes",
          "backends",
          "apikeys",
          "certificates",
          "auth. modules",
          "jwt verifiers",
          "tcp services",
          "data exporters",
          "wasm plugins",
          "danger zone"
        )
      )
    )
  }
}

class AdminPreferencesDatastore(env: Env) {

  private implicit val ev = env

  def computeKey(id: String): String = s"${env.storageRoot}:admins_prefs:${id}"

  def getPreferencesOrSetDefault(userId: String)(implicit ec: ExecutionContext): Future[AdminPreferences] = {
    getPreferences(userId).flatMap {
      case None        => setPreferences(userId, AdminPreferences.defaultValue(userId))
      case Some(prefs) => prefs.vfuture
    }
  }

  def getPreferences(userId: String)(implicit ec: ExecutionContext): Future[Option[AdminPreferences]] = {
    val key = computeKey(userId)
    env.datastores.rawDataStore
      .get(key)
      .map(_.flatMap(bs => AdminPreferences.format.reads(bs.utf8String.parseJson).asOpt))
  }

  def getPreference(userId: String, prefKey: String)(implicit ec: ExecutionContext): Future[Option[JsValue]] = {
    getPreferencesOrSetDefault(userId) map { prefs =>
      prefs.preferences.get(prefKey)
    }
  }

  def setPreferences(userId: String, preferences: AdminPreferences)(implicit
      ec: ExecutionContext
  ): Future[AdminPreferences] = {
    val key = computeKey(userId)
    env.datastores.rawDataStore.set(key, preferences.json.stringify.byteString, None).map(_ => preferences)
  }

  def setPreference(userId: String, prefKey: String, value: JsValue)(implicit ec: ExecutionContext): Future[JsValue] = {
    getPreferencesOrSetDefault(userId).flatMap { prefs =>
      prefs.set(prefKey, value).save().map { prefs =>
        value
      }
    }
  }

  def deletePreferences(userId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    val key = computeKey(userId)
    env.datastores.rawDataStore.del(Seq(key)).map(_ => ())
  }

  def deletePreference(userId: String, prefKey: String)(implicit ec: ExecutionContext): Future[Unit] = {
    getPreferencesOrSetDefault(userId).flatMap { prefs =>
      prefs.delete(prefKey).save().map { _ =>
        ()
      }
    }
  }
}
