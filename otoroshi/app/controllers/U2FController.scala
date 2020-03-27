package controllers

import java.security.SecureRandom
import java.util
import java.util.Optional
import java.util.concurrent.TimeUnit

import actions.{BackOfficeAction, BackOfficeActionAuth}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.yubico.webauthn._
import com.yubico.webauthn.data._
import env.Env
import events._
import models.BackOfficeUser
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import security.IdGenerator
import utils.future.Implicits._

import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

class U2FController(BackOfficeAction: BackOfficeAction,
                    BackOfficeActionAuth: BackOfficeActionAuth,
                    cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-u2f-controller")

  private val base64Encoder = java.util.Base64.getUrlEncoder
  private val base64Decoder = java.util.Base64.getUrlDecoder
  private val random        = new SecureRandom()
  private val jsonMapper = new ObjectMapper()
    .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
    .setSerializationInclusion(Include.NON_ABSENT)
    .registerModule(new Jdk8Module())

  def loginPage() = BackOfficeAction { ctx =>
    Ok(views.html.backoffice.u2flogin(env))
  }

  /////////// Simple admins ////////////////////////////////////////////////////////////////////////////////////////////

  def simpleLogin = BackOfficeAction.async(parse.json) { ctx =>
    implicit val req = ctx.request
    val usernameOpt  = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt  = (ctx.request.body \ "password").asOpt[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(pass)) => {
        env.datastores.simpleAdminDataStore.findByUsername(username).flatMap {
          case Some(user) => {
            val password        = (user \ "password").as[String]
            val label           = (user \ "label").as[String]
            val authorizedGroup = (user \ "authorizedGroup").asOpt[String]
            if (BCrypt.checkpw(pass, password)) {
              logger.debug(s"Login successful for simple admin '$username'")
              BackOfficeUser(IdGenerator.token(64),
                             username,
                             username,
                             Json.obj(
                               "name"  -> label,
                               "email" -> username
                             ),
                             authorizedGroup,
                             true).save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS)).map { boUser =>
                env.datastores.simpleAdminDataStore.hasAlreadyLoggedIn(username).map {
                  case false => {
                    env.datastores.simpleAdminDataStore.alreadyLoggedIn(username)
                    Alerts.send(AdminFirstLogin(env.snowflakeGenerator.nextIdStr(), env.env, boUser, ctx.from, ctx.ua))
                  }
                  case true => {
                    Alerts
                      .send(
                        AdminLoggedInAlert(env.snowflakeGenerator.nextIdStr(),
                                           env.env,
                                           boUser,
                                           ctx.from,
                                           ctx.ua,
                                           "local")
                      )
                  }
                }
                Ok(Json.obj("username" -> username)).addingToSession("bousr" -> boUser.randomId)
              }
            } else {
              Unauthorized(Json.obj("error" -> "not authorized")).asFuture
            }
          }
          case None => Unauthorized(Json.obj("error" -> "not authorized")).asFuture
        }
      }
      case _ => Unauthorized(Json.obj("error" -> "not authorized")).asFuture
    }
  }

  def registerSimpleAdmin = BackOfficeActionAuth.async(parse.json) { ctx =>
    val usernameOpt        = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt        = (ctx.request.body \ "password").asOpt[String]
    val labelOpt           = (ctx.request.body \ "label").asOpt[String]
    val authorizedGroupOpt = (ctx.request.body \ "authorizedGroup").asOpt[String]
    (usernameOpt, passwordOpt, labelOpt, authorizedGroupOpt) match {
      case (Some(username), Some(password), Some(label), authorizedGroup) => {
        val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
        env.datastores.simpleAdminDataStore.registerUser(username, saltedPassword, label, authorizedGroup).map { _ =>
          Ok(Json.obj("username" -> username))
        }
      }
      case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
    }
  }

  def simpleAdmins = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.simpleAdminDataStore.findAll() map { users =>
      Ok(JsArray(users.drop(paginationPosition).take(paginationPageSize)))
    }
  }

  def deleteAdmin(username: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.simpleAdminDataStore.deleteUser(username).map { d =>
      val event = BackOfficeEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        ctx.user,
        "DELETE_ADMIN",
        s"Admin deleted an Admin",
        ctx.from,
        ctx.ua,
        Json.obj("username" -> username)
      )
      Audit.send(event)
      Alerts.send(U2FAdminDeletedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
      Ok(Json.obj("done" -> true))
    }
  }

  /////////// WebAuthn admins ////////////////////////////////////////////////////////////////////////////////////////////

  def webAuthnAdmins() = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.webAuthnAdminDataStore.findAll() map { users =>
      Ok(JsArray(users.drop(paginationPosition).take(paginationPageSize)))
    }
  }

  def webAuthnDeleteAdmin(username: String, id: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.webAuthnAdminDataStore.deleteUser(username).map { d =>
      val event = BackOfficeEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        ctx.user,
        "DELETE_WEBAUTHN_ADMIN",
        s"Admin deleted a WebAuthn Admin",
        ctx.from,
        ctx.ua,
        Json.obj("username" -> username, "id" -> id)
      )
      Audit.send(event)
      Alerts
        .send(WebAuthnAdminDeletedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
      Ok(Json.obj("done" -> true))
    }
  }

  def webAuthnRegistrationStart() = BackOfficeActionAuth.async(parse.json) { ctx =>
    import collection.JavaConverters._

    val username      = (ctx.request.body \ "username").as[String]
    val label         = (ctx.request.body \ "label").as[String]
    val reqOrigin     = (ctx.request.body \ "origin").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    env.datastores.webAuthnAdminDataStore.findAll().flatMap { users =>
      val rpIdentity: RelyingPartyIdentity = RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
      val rp: RelyingParty = RelyingParty.builder
        .identity(rpIdentity)
        .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
        .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
        .build

      val userHandle = new Array[Byte](64)
      random.nextBytes(userHandle)

      val registrationRequestId = IdGenerator.token(32)
      val request: PublicKeyCredentialCreationOptions = rp.startRegistration(
        StartRegistrationOptions.builder
          .user(
            UserIdentity.builder
              .name(username)
              .displayName(label)
              .id(new ByteArray(userHandle))
              .build
          )
          .build
      )

      val jsonRequest = jsonMapper.writeValueAsString(request)
      val finalRequest = Json.obj(
        "requestId" -> registrationRequestId,
        "request"   -> Json.parse(jsonRequest),
        "username"  -> username,
        "label"     -> label,
        "handle"    -> base64Encoder.encodeToString(userHandle)
      )

      env.datastores.webAuthnRegistrationsDataStore.setRegistrationRequest(registrationRequestId, finalRequest).map {
        _ =>
          Ok(finalRequest)
      }
    }
  }

  def webAuthnRegistrationFinish() = BackOfficeActionAuth.async(parse.json) { ctx =>
    import collection.JavaConverters._

    val json          = ctx.request.body
    val responseJson  = Json.stringify((json \ "webauthn").as[JsValue])
    val otoroshi      = (json \ "otoroshi").as[JsObject]
    val reqOrigin     = (otoroshi \ "origin").as[String]
    val reqId         = (json \ "requestId").as[String]
    val handle        = (otoroshi \ "handle").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    env.datastores.webAuthnAdminDataStore.findAll().flatMap { users =>
      val rpIdentity: RelyingPartyIdentity = RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
      val rp: RelyingParty = RelyingParty.builder
        .identity(rpIdentity)
        .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
        .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
        .build
      val pkc = PublicKeyCredential.parseRegistrationResponseJson(responseJson)

      env.datastores.webAuthnRegistrationsDataStore.getRegistrationRequest(reqId).flatMap {
        case None => FastFuture.successful(BadRequest(Json.obj("error" -> "bad request")))
        case Some(rawRequest) => {
          val request = jsonMapper.readValue(Json.stringify((rawRequest \ "request").as[JsValue]),
                                             classOf[PublicKeyCredentialCreationOptions])

          Try(
            rp.finishRegistration(
              FinishRegistrationOptions
                .builder()
                .request(request)
                .response(pkc)
                .build()
            )
          ) match {
            case Failure(e) =>
              e.printStackTrace()
              FastFuture.successful(BadRequest(Json.obj("error" -> "bad request")))
            case Success(result) => {
              val username           = (otoroshi \ "username").as[String]
              val password           = (otoroshi \ "password").as[String]
              val label              = (otoroshi \ "label").as[String]
              val authorizedGroupOpt = (otoroshi \ "authorizedGroup").asOpt[String]
              val saltedPassword     = BCrypt.hashpw(password, BCrypt.gensalt())
              val credential         = Json.parse(jsonMapper.writeValueAsString(result))
              env.datastores.webAuthnAdminDataStore
                .registerUser(username, saltedPassword, label, authorizedGroupOpt, credential, handle)
                .map { _ =>
                  Ok(Json.obj("username" -> username))
                }
            }
          }
        }
      }
    }
  }

  def webAuthnLoginStart() = BackOfficeAction.async(parse.json) { ctx =>
    import collection.JavaConverters._

    val usernameOpt   = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt   = (ctx.request.body \ "password").asOpt[String]
    val reqOrigin     = (ctx.request.body \ "origin").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(password)) => {
        env.datastores.webAuthnAdminDataStore.findAll().flatMap { users =>
          users.find(u => (u \ "username").as[String] == username) match {
            case Some(user) if BCrypt.checkpw(password, (user \ "password").as[String]) => {

              val rpIdentity: RelyingPartyIdentity =
                RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
              val rp: RelyingParty = RelyingParty.builder
                .identity(rpIdentity)
                .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
                .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
                .build
              val request: AssertionRequest =
                rp.startAssertion(StartAssertionOptions.builder.username(Optional.of(username)).build)

              val registrationRequestId = IdGenerator.token(32)
              val jsonRequest: String   = jsonMapper.writeValueAsString(request)
              val finalRequest = Json.obj(
                "requestId" -> registrationRequestId,
                "request"   -> Json.parse(jsonRequest),
                "username"  -> username,
                "label"     -> "--"
              )

              env.datastores.webAuthnRegistrationsDataStore
                .setRegistrationRequest(registrationRequestId, finalRequest)
                .map { _ =>
                  Ok(finalRequest)
                }
            }
            case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "bad request")))
          }
        }
      }
      case (_, _) => {
        FastFuture.successful(BadRequest(Json.obj("error" -> "bad request")))
      }
    }
  }

  def webAuthnLoginFinish() = BackOfficeAction.async(parse.json) { ctx =>
    import collection.JavaConverters._

    implicit val req = ctx.request

    val json          = ctx.request.body
    val webauthn      = (json \ "webauthn").as[JsObject]
    val otoroshi      = (json \ "otoroshi").as[JsObject]
    val reqOrigin     = (otoroshi \ "origin").as[String]
    val reqId         = (json \ "requestId").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    val usernameOpt = (otoroshi \ "username").asOpt[String]
    val passwordOpt = (otoroshi \ "password").asOpt[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(pass)) => {
        env.datastores.webAuthnAdminDataStore.findAll().flatMap { users =>
          users.find(u => (u \ "username").as[String] == username) match {
            case None => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad user")))
            case Some(user) => {
              env.datastores.webAuthnRegistrationsDataStore.getRegistrationRequest(reqId).flatMap {
                case None => FastFuture.successful(BadRequest(Json.obj("error" -> "bad request")))
                case Some(rawRequest) => {
                  val request = jsonMapper.readValue(Json.stringify((rawRequest \ "request").as[JsValue]),
                                                     classOf[AssertionRequest])
                  val password        = (user \ "password").as[String]
                  val label           = (user \ "label").as[String]
                  val authorizedGroup = (user \ "authorizedGroup").asOpt[String]

                  if (BCrypt.checkpw(pass, password)) {
                    val rpIdentity: RelyingPartyIdentity =
                      RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
                    val rp: RelyingParty = RelyingParty.builder
                      .identity(rpIdentity)
                      .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
                      .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
                      .build
                    val pkc = PublicKeyCredential.parseAssertionResponseJson(Json.stringify(webauthn))
                    Try(
                      rp.finishAssertion(
                        FinishAssertionOptions
                          .builder()
                          .request(request)
                          .response(pkc)
                          .build()
                      )
                    ) match {
                      case Failure(e) =>
                        FastFuture.successful(BadRequest(Json.obj("error" -> "bad request")))
                      case Success(result) if !result.isSuccess =>
                        FastFuture.successful(BadRequest(Json.obj("error" -> "bad request")))
                      case Success(result) if result.isSuccess => {
                        logger.debug(s"Login successful for user '$username'")
                        BackOfficeUser(
                          IdGenerator.token(64),
                          username,
                          username,
                          Json.obj(
                            "name"  -> label,
                            "email" -> username
                          ),
                          authorizedGroup,
                          false
                        ).save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS)).map { boUser =>
                          env.datastores.webAuthnAdminDataStore.hasAlreadyLoggedIn(username).map {
                            case false => {
                              env.datastores.webAuthnAdminDataStore.alreadyLoggedIn(username)
                              Alerts.send(
                                AdminFirstLogin(env.snowflakeGenerator.nextIdStr(), env.env, boUser, ctx.from, ctx.ua)
                              )
                            }
                            case true => {
                              Alerts.send(
                                AdminLoggedInAlert(env.snowflakeGenerator.nextIdStr(),
                                                   env.env,
                                                   boUser,
                                                   ctx.from,
                                                   ctx.ua,
                                                   "local")
                              )
                            }
                          }
                          Ok(
                            Json.obj("username" -> username)
                          ).addingToSession("bousr" -> boUser.randomId)
                        }
                      }
                    }
                  } else {
                    FastFuture.successful(Unauthorized(Json.obj("error" -> "Not Authorized")))
                  }
                }
              }
            }
          }
        }
      }
      case (_, _) => FastFuture.successful(Unauthorized(Json.obj("error" -> "Not Authorized")))
    }
  }
}

class LocalCredentialRepository(users: Seq[JsValue], jsonMapper: ObjectMapper, base64Decoder: java.util.Base64.Decoder)
    extends CredentialRepository {

  import collection.JavaConverters._

  override def getCredentialIdsForUsername(username: String): util.Set[PublicKeyCredentialDescriptor] = {
    users
      .filter { user =>
        val _username = (user \ "username").as[String]
        _username == username
      }
      .map { user =>
        val credential = Json.stringify((user \ "credential").as[JsValue])
        val regResult  = jsonMapper.readValue(credential, classOf[RegistrationResult])
        regResult.getKeyId
      }
      .toSet
      .asJava
  }

  override def getUserHandleForUsername(username: String): Optional[ByteArray] = {
    users
      .find { user =>
        (user \ "username").as[String] == username
      }
      .map { user =>
        new ByteArray(base64Decoder.decode((user \ "handle").as[String]))
      } match {
      case None    => Optional.empty()
      case Some(r) => Optional.of(r)
    }
  }

  override def getUsernameForUserHandle(userHandle: ByteArray): Optional[String] = {
    users
      .find { user =>
        val handle = new ByteArray(base64Decoder.decode((user \ "handle").as[String]))
        handle.equals(userHandle)
      }
      .map { user =>
        (user \ "username").as[String]
      } match {
      case None    => Optional.empty()
      case Some(r) => Optional.of(r)
    }
  }

  override def lookup(credentialId: ByteArray, userHandle: ByteArray): Optional[RegisteredCredential] = {
    users
      .find { user =>
        val credential = Json.stringify((user \ "credential").as[JsValue])
        val regResult  = jsonMapper.readValue(credential, classOf[RegistrationResult])
        val handle     = new ByteArray(base64Decoder.decode((user \ "handle").as[String]))
        regResult.getKeyId.getId.equals(credentialId) && handle.equals(userHandle)
      }
      .map { user =>
        val credential = Json.stringify((user \ "credential").as[JsValue])
        val regResult  = jsonMapper.readValue(credential, classOf[RegistrationResult])
        val handle     = new ByteArray(base64Decoder.decode((user \ "handle").as[String]))
        RegisteredCredential
          .builder()
          .credentialId(regResult.getKeyId.getId)
          .userHandle(handle)
          .publicKeyCose(regResult.getPublicKeyCose)
          .signatureCount(0L)
          .build()
      } match {
      case None    => Optional.empty()
      case Some(r) => Optional.of(r)
    }
  }

  override def lookupAll(credentialId: ByteArray): util.Set[RegisteredCredential] = {
    users
      .filter { user =>
        val credential = Json.stringify((user \ "credential").as[JsValue])
        val regResult  = jsonMapper.readValue(credential, classOf[RegistrationResult])
        regResult.getKeyId.getId.equals(credentialId)
      }
      .map { user =>
        val credential = Json.stringify((user \ "credential").as[JsValue])
        val regResult  = jsonMapper.readValue(credential, classOf[RegistrationResult])
        val handle     = new ByteArray(base64Decoder.decode((user \ "handle").as[String]))
        RegisteredCredential
          .builder()
          .credentialId(regResult.getKeyId.getId)
          .userHandle(handle)
          .publicKeyCose(regResult.getPublicKeyCose)
          .signatureCount(0L)
          .build()
      }
      .toSet
      .asJava
  }
}
