package otoroshi.auth

import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nimbusds.jose.util.X509CertUtils
import net.shibboleth.utilities.java.support.xml.BasicParserPool
import org.apache.pulsar.shade.org.apache.commons.io.IOUtils
import org.apache.pulsar.shade.org.apache.commons.io.input.BOMInputStream
import org.opensaml.core.config.InitializationService
import org.opensaml.core.xml.XMLObject
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.core.xml.io.MarshallingException
import org.opensaml.core.xml.schema.impl.XSStringImpl
import org.opensaml.saml.common.SAMLVersion
import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.metadata.resolver.impl.DOMMetadataResolver
import org.opensaml.saml.saml2.core._
import org.opensaml.saml.saml2.encryption.Decrypter
import org.opensaml.saml.saml2.metadata.EntityDescriptor
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.SignatureSigningParameters
import org.opensaml.xmlsec.encryption.support.InlineEncryptedKeyResolver
import org.opensaml.xmlsec.keyinfo.KeyInfoCredentialResolver
import org.opensaml.xmlsec.keyinfo.impl.{
  ChainingKeyInfoCredentialResolver,
  StaticKeyInfoCredentialResolver,
  X509KeyInfoGeneratorFactory
}
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.impl.SignatureBuilder
import org.opensaml.xmlsec.signature.support.{SignatureConstants, SignatureException, SignatureSupport}
import org.w3c.dom.ls.DOMImplementationLS
import org.w3c.dom.{Document, Node}
import otoroshi.controllers.routes
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.security.IdGenerator
import otoroshi.ssl.DynamicSSLEngineProvider.PRIVATE_KEY_PATTERN
import otoroshi.ssl.{DynamicSSLEngineProvider, PemHeaders}
import otoroshi.utils.{JsonPathValidator, JsonValidator}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{Format, JsArray, JsError, JsNull, JsObject, JsString, JsSuccess, JsValue, Json}
import play.api.mvc.Results.{BadRequest, Ok, Redirect}
import play.api.mvc.{AnyContent, Request, RequestHeader, Result, Results}
import play.twirl.api.TwirlHelperImports.twirlJavaCollectionToScala

import java.io.{ByteArrayInputStream, InputStreamReader, Reader, StringWriter, Writer}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{PrivateKey, Security}
import java.time.{Instant, ZonedDateTime}
import java.util
import java.util.regex.Matcher
import java.util.zip.{Inflater, InflaterInputStream}
import java.util.{Base64, UUID}
import javax.xml.namespace.QName
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.{asScalaBufferConverter, asScalaSetConverter}
import scala.util.Try
import java.util.zip.Deflater

case class SAMLModule(authConfig: SamlAuthModuleConfig) extends AuthModule {

  import SAMLModule._

  def this() = this(SAMLModule.defaultConfig)

  override def paLoginPage(
      request: RequestHeader,
      config: GlobalConfig,
      descriptor: ServiceDescriptor,
      isRoute: Boolean
  )(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    implicit val req: RequestHeader = request

    val redirect   = request
      .getQueryString("redirect")
      .filter(redirect =>
        request.getQueryString("hash").contains(env.sign(s"desc=${descriptor.id}&redirect=${redirect}"))
      )
      .map(redirectBase64Encoded =>
        new String(Base64.getUrlDecoder.decode(redirectBase64Encoded), StandardCharsets.UTF_8)
      )
    val redirectTo = redirect.getOrElse(
      routes.PrivateAppsController.home.absoluteURL(env.exposedRootSchemeIsHttps)
    )
    val hash       = env.sign(s"${authConfig.id}:::${descriptor.id}")
    val relayState = JWT
      .create()
      .withClaim("hash", hash)
      .withClaim("desc", descriptor.id)
      .withClaim("route", isRoute)
      .withClaim("ref", authConfig.id)
      .withClaim("redirect_uri", redirectTo)
      .sign(Algorithm.HMAC512(env.otoroshiSecret))
    //URLEncoder.encode(
    //  s"hash=$hash&desc=${descriptor.id}&redirect_uri=${redirectTo}&route=$isRoute&ref=${authConfig.id}",
    //  "UTF-8"
    //)
    getRequest(env, authConfig).map {
      case Left(value)    => BadRequest(value)
      case Right(encoded) =>
        if (authConfig.ssoProtocolBinding == SAMLProtocolBinding.Post)
          Ok(otoroshi.views.html.oto.saml(encoded, authConfig.singleSignOnUrl, env, Some(relayState)))
        else {
          val redirectUrl = if (authConfig.singleSignOnUrl.contains("?")) {
            s"${authConfig.singleSignOnUrl}&SAMLRequest=${URLEncoder.encode(encoded, "UTF-8")}&RelayState=$relayState"
          } else {
            s"${authConfig.singleSignOnUrl}?SAMLRequest=${URLEncoder.encode(encoded, "UTF-8")}&RelayState=$relayState"
          }
          Redirect(redirectUrl)
            .addingToSession(
              s"pa-redirect-after-login-${authConfig.cookieSuffix(descriptor)}" -> redirectTo,
              "hash"                                                            -> env.sign(s"${authConfig.id}:::${descriptor.id}"),
              "desc"                                                            -> descriptor.id,
              "ref"                                                             -> authConfig.id,
              "route"                                                           -> s"$isRoute"
            )
        }
    }
  }

  override def paLogout(
      request: RequestHeader,
      user: Option[PrivateAppsUser],
      config: GlobalConfig,
      descriptor: ServiceDescriptor
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Option[String]]] = {
    getLogoutRequest(env, authConfig, user.map(_.metadata.getOrElse("saml-id", ""))).map {
      case Left(_)        => Right(None)
      case Right(encoded) =>
        authConfig.singleLogoutUrl match {
          case None                  => Left(Results.InternalServerError(Json.obj("error" -> "no logout url configured !")))
          case Some(singleLogoutUrl) => {
            if (authConfig.singleLogoutProtocolBinding == SAMLProtocolBinding.Post)
              Left(Ok(otoroshi.views.html.oto.saml(encoded, singleLogoutUrl, env)))
            else {
              val redirectUrl = if (authConfig.singleLogoutUrl.contains("?")) {
                s"${singleLogoutUrl}&SAMLRequest=${URLEncoder.encode(encoded, "UTF-8")}"
              } else {
                s"${singleLogoutUrl}?SAMLRequest=${URLEncoder.encode(encoded, "UTF-8")}"
              }
              env.Ws.url(redirectUrl).get()
              Right(None)
            }
          }
        }
    }
  }

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[ErrorReason, PrivateAppsUser]] = {

    request.body.asFormUrlEncoded match {
      case Some(body) =>
        val samlResponse = body("SAMLResponse").head

        decodeAndValidateSamlResponse(env, authConfig, samlResponse, "") match {
          case Left(value)       =>
            env.logger.error(value)
            FastFuture.successful(Left(ErrorReason(value)))
          case Right(assertions) =>
            val assertion = assertions.get(0)

            val attributeStatements = assertion.getAttributeStatements.asScala

            val attributes: Map[String, List[String]] = attributeStatements
              .flatMap(_.getAttributes.asScala)
              .toList
              .map { attribute =>
                (
                  attribute.getName,
                  attribute.getAttributeValues.map {
                    case value: XSStringImpl => value.getValue
                    case value: XMLObject    => value.getDOM.getTextContent
                  }
                )
              }
              .groupBy(_._1)
              .map { group =>
                (group._1, group._2.flatMap(_._2))
              }

            val email =
              if (authConfig.usedNameIDAsEmail)
                assertion.getSubject.getNameID.getValue
              else
                attributes.get("Email").map(_.head).getOrElse("no.name@oto.tools")

            val name = attributes.get("Name").map(_.head).getOrElse("No name")

            PrivateAppsUser(
              randomId = IdGenerator.token(64),
              name = name,
              email = email,
              profile = Json
                .obj(
                  "name"  -> name,
                  "email" -> email
                )
                .deepMerge(authConfig.extraMetadata)
                .deepMerge(attributes.foldLeft(Json.obj()) { case (acc, item) =>
                  acc ++ Json.obj(item._1 -> item._2.mkString(" - "))
                }),
              token = Json.obj(),
              authConfigId = authConfig.id,
              realm = authConfig.cookieSuffix(descriptor),
              tags = Seq.empty,
              metadata = Map("saml-id" -> assertion.getSubject.getNameID.getValue),
              otoroshiData = Some(authConfig.extraMetadata),
              location = authConfig.location
            ).validate(descriptor, isRoute = true, authConfig)
        }
      case None       => FastFuture.successful(Left(ErrorReason("error")))
    }
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    val encodedRequest = getRequest(env, authConfig)

    encodedRequest.map {
      case Left(value)    => BadRequest(value)
      case Right(encoded) =>
        if (authConfig.ssoProtocolBinding == SAMLProtocolBinding.Post)
          Ok(otoroshi.views.html.oto.saml(encoded, authConfig.singleSignOnUrl, env))
        else {
          val redirectUrl = if (authConfig.singleSignOnUrl.contains("?")) {
            s"${authConfig.singleSignOnUrl}&SAMLRequest=${URLEncoder.encode(encoded, "UTF-8")}"
          } else {
            s"${authConfig.singleSignOnUrl}?SAMLRequest=${URLEncoder.encode(encoded, "UTF-8")}"
          }
          Redirect(redirectUrl).addingToSession("hash" -> env.sign(s"${authConfig.id}:::backoffice"))(request)
        }
    }
  }

  override def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[Result, Option[String]]] = {
    getLogoutRequest(env, authConfig, Some(user.metadata.getOrElse("saml-id", ""))).map {
      case Left(_)        => Right(None)
      case Right(encoded) =>
        authConfig.singleLogoutUrl match {
          case None                  => Left(Results.InternalServerError(Json.obj("error" -> "no logout url configured !")))
          case Some(singleLogoutUrl) => {
            if (authConfig.singleLogoutProtocolBinding == SAMLProtocolBinding.Post)
              Left(Ok(otoroshi.views.html.oto.saml(encoded, singleLogoutUrl, env)))
            else {
              val redirectUrl = if (authConfig.singleLogoutUrl.contains("?")) {
                s"${singleLogoutUrl}&SAMLRequest=${URLEncoder.encode(encoded, "UTF-8")}"
              } else {
                s"${singleLogoutUrl}?SAMLRequest=${URLEncoder.encode(encoded, "UTF-8")}"
              }
              env.Ws.url(redirectUrl).get()
              Right(None)
            }
          }
        }
    }
  }

  override def boCallback(request: Request[AnyContent], config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[ErrorReason, BackOfficeUser]] = {

    request.body.asFormUrlEncoded match {
      case Some(body) =>
        val samlResponse = body("SAMLResponse").head

        decodeAndValidateSamlResponse(env, authConfig, samlResponse, "") match {
          case Left(value)       =>
            env.logger.error(value)
            FastFuture.successful(Left(ErrorReason(value)))
          case Right(assertions) =>
            val assertion = assertions.get(0)

            val attributeStatements = assertion.getAttributeStatements.asScala

            val attributes: Map[String, List[String]] = attributeStatements
              .flatMap(_.getAttributes.asScala)
              .toList
              .map { attribute =>
                (
                  attribute.getName,
                  attribute.getAttributeValues.map {
                    case value: XSStringImpl => value.getValue
                    case value: XMLObject    => value.getDOM.getTextContent
                  }
                )
              }
              .groupBy(_._1)
              .map { group =>
                (group._1, group._2.flatMap(_._2))
              }

            val email =
              if (authConfig.usedNameIDAsEmail)
                assertion.getSubject.getNameID.getValue
              else
                attributes.get("Email").map(_.head).getOrElse("no.name@oto.tools")

            val name = attributes.get("Name").map(_.head).getOrElse("No name")

            BackOfficeUser(
              randomId = IdGenerator.token(64),
              name = name,
              profile = Json
                .obj(
                  "name"  -> name,
                  "email" -> email
                )
                .deepMerge(authConfig.extraMetadata)
                .deepMerge(attributes.foldLeft(Json.obj()) { case (acc, item) =>
                  acc ++ Json.obj(item._1 -> item._2.mkString(" - "))
                }),
              email = email,
              authConfigId = authConfig.id,
              simpleLogin = false,
              tags = Seq.empty,
              metadata = Map("saml-id" -> assertion.getSubject.getNameID.getValue),
              adminEntityValidators = authConfig.adminEntityValidatorsOverride.getOrElse(email, Map.empty),
              rights = UserRights(
                Seq(
                  UserRight(
                    TenantAccess(authConfig.location.tenant.value),
                    authConfig.location.teams.map(t => TeamAccess(t.value))
                  )
                )
              ),
              location = authConfig.location
            ).validate(
              env.backOfficeServiceDescriptor,
              isRoute = true,
              authConfig
            )
        }
      case None       => FastFuture.successful(Left(ErrorReason("error")))
    }
  }
}

object SamlAuthModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger: Logger = Logger("otoroshi-global-saml-config")

  val _fmt = new Format[SamlAuthModuleConfig] {
    override def reads(json: JsValue) = {
      fromJson(json) match {
        case Left(e)  => JsError(e.getMessage)
        case Right(v) => JsSuccess(v.asInstanceOf[SamlAuthModuleConfig])
      }
    }

    override def writes(o: SamlAuthModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = {
    Try {
      Right(
        SamlAuthModuleConfig(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          singleSignOnUrl = (json \ "singleSignOnUrl").as[String],
          singleLogoutUrl = (json \ "singleLogoutUrl").asOpt[String].filter(_.nonEmpty),
          credentials = (json \ "credentials").as[SAMLCredentials](SAMLCredentials.fmt),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          extraMetadata = (json \ "extraMetadata").asOpt[JsObject].getOrElse(Json.obj()),
          allowedUsers = json.select("allowedUsers").asOpt[Seq[String]].getOrElse(Seq.empty),
          deniedUsers = json.select("deniedUsers").asOpt[Seq[String]].getOrElse(Seq.empty),
          issuer = (json \ "issuer").as[String],
          ssoProtocolBinding = (json \ "ssoProtocolBinding")
            .asOpt[String]
            .map(n => SAMLProtocolBinding(n))
            .getOrElse(SAMLProtocolBinding.Redirect),
          singleLogoutProtocolBinding = (json \ "singleLogoutProtocolBinding")
            .asOpt[String]
            .map(n => SAMLProtocolBinding(n))
            .getOrElse(SAMLProtocolBinding.Redirect),
          validatingCertificates = (json \ "validatingCertificates")
            .asOpt[List[String]]
            .getOrElse(List.empty[String]),
          validateSignature = (json \ "validateSignature").as[Boolean],
          nameIDFormat = (json \ "nameIDFormat")
            .asOpt[String]
            .map(n => NameIDFormat(n).getOrElse(NameIDFormat.Transient))
            .getOrElse(NameIDFormat.Transient),
          validateAssertions = (json \ "validateAssertions").as[Boolean],
          signature = (json \ "signature").as[SAMLSignature](SAMLSignature.fmt),
          usedNameIDAsEmail = (json \ "usedNameIDAsEmail").asOpt[Boolean].getOrElse(true),
          emailAttributeName = (json \ "emailAttributeName").asOpt[String],
          clientSideSessionEnabled = (json \ "clientSideSessionEnabled").asOpt[Boolean].getOrElse(true),
          sessionCookieValues =
            (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues()),
          userValidators = (json \ "userValidators")
            .asOpt[Seq[JsValue]]
            .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
            .getOrElse(Seq.empty),
          remoteValidators = (json \ "remoteValidators")
            .asOpt[Seq[JsValue]]
            .map(_.flatMap(v => RemoteUserValidatorSettings.format.reads(v).asOpt))
            .getOrElse(Seq.empty),
          adminEntityValidatorsOverride = json
            .select("adminEntityValidatorsOverride")
            .asOpt[JsObject]
            .map { o =>
              o.value.mapValues { obj =>
                obj.asObject.value.mapValues { arr =>
                  arr.asArray.value
                    .map { item =>
                      JsonValidator.format.reads(item)
                    }
                    .collect { case JsSuccess(v, _) =>
                      v
                    }
                }.toMap
              }.toMap
            }
            .getOrElse(Map.empty[String, Map[String, Seq[JsonValidator]]])
        )
      )
    } recover { case e =>
      Left(e)
    } get
  }

  def fromDescriptor(metadata: String): Either[String, SamlAuthModuleConfig] = {
    InitializationService.initialize()

    val parser = new BasicParserPool()
    parser.initialize()

    val metadataDocument = parser.parse(new BOMInputStream(IOUtils.toInputStream(metadata, StandardCharsets.UTF_8)))

    val resolver = new DOMMetadataResolver(metadataDocument.getDocumentElement)
    resolver.setId("componentId")
    resolver.initialize()

    val entitiesDescriptor: util.List[EntityDescriptor] = new util.ArrayList[EntityDescriptor]()
    resolver.forEach(e => entitiesDescriptor.add(e))

    if (entitiesDescriptor.isEmpty)
      Left("Wrong entities descriptors - Missing entity descriptor")
    else {
      val entityDescriptor = entitiesDescriptor.get(0)
      val idpssoDescriptor = entityDescriptor.getIDPSSODescriptor("urn:oasis:names:tc:SAML:2.0:protocol")

      if (idpssoDescriptor == null)
        Left("Cannot retrieve IDP SSO descriptor")
      else {
        if (idpssoDescriptor.getSingleSignOnServices.isEmpty)
          Left("Cannot find SSO binding in metadata")
        // else if (idpssoDescriptor.getSingleLogoutServices.isEmpty)
        //   Left("Cannot find Single Logout Service in metadata")
        else {
          Right(
            SamlAuthModuleConfig(
              id = IdGenerator.token,
              name = "SAML Module",
              desc = "SAML Module",
              singleSignOnUrl = idpssoDescriptor.getSingleSignOnServices.get(0).getLocation,
              singleLogoutUrl =
                Try(idpssoDescriptor.getSingleLogoutServices.get(0).getLocation).filter(_ != null).toOption,
              issuer = entityDescriptor.getEntityID,
              ssoProtocolBinding = SAMLProtocolBinding(idpssoDescriptor.getSingleSignOnServices.get(0).getBinding),
              singleLogoutProtocolBinding =
                SAMLProtocolBinding(idpssoDescriptor.getSingleLogoutServices.get(0).getBinding),
              validatingCertificates = idpssoDescriptor.getKeyDescriptors.toSeq
                .flatMap(_.getKeyInfo.getX509Datas.filter(_.getX509Certificates.nonEmpty))
                .flatMap(_.getX509Certificates.toSeq.headOption)
                .map(_.getValue)
                .toList,
              tags = Seq.empty,
              metadata = Map.empty,
              sessionCookieValues = SessionCookieValues(),
              clientSideSessionEnabled = true
            )
          )
        }
      }
    }
  }
}

sealed trait NameIDFormat {
  def value: String
  def name: String
}

case object NameIDFormatPersistent extends NameIDFormat {
  val value = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"
  val name  = "persistent"
}

case object NameIDFormatTransient extends NameIDFormat {
  val value = "urn:oasis:names:tc:SAML:2.0:nameid-format:transient"
  val name  = "transient"
}

case object NameIDFormatKerberos extends NameIDFormat {
  val value = "urn:oasis:names:tc:SAML:2.0:nameid-format:kerberos"
  val name  = "kerberos"
}

case object NameIDFormatEntity extends NameIDFormat {
  val value = "urn:oasis:names:tc:SAML:2.0:nameid-format:entity"
  val name  = "entity"
}

case object NameIDFormatEmailAddress extends NameIDFormat {
  val value = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"
  val name  = "emailAddress"
}

case object NameIDFormatUnspecified extends NameIDFormat {
  val value = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified"
  val name  = "unspecified"
}

object NameIDFormat {

  val Persistent   = NameIDFormatPersistent
  val Transient    = NameIDFormatTransient
  val Kerberos     = NameIDFormatKerberos
  val Entity       = NameIDFormatEntity
  val EmailAddress = NameIDFormatEmailAddress
  val Unspecified  = NameIDFormatUnspecified

  def apply(name: String): Option[NameIDFormat] = {
    name.toLowerCase.trim match {
      case "persistent"   => Some(Persistent)
      case "transient"    => Some(Transient)
      case "kerberos"     => Some(Kerberos)
      case "entity"       => Some(Entity)
      case "emailaddress" => Some(EmailAddress)
      case "unspecified"  => Some(Unspecified)
      case _              => Some(Unspecified)
    }
  }
}

case class Credential(
    certificate: Option[String] = None,
    privateKey: Option[String] = None,
    certId: Option[String] = None,
    useOtoroshiCertificate: Boolean = false
)

object Credential {
  def fmt = new Format[Credential] {
    override def writes(o: Credential) = Json.obj(
      "certificate"            -> o.certificate,
      "privateKey"             -> o.privateKey,
      "certId"                 -> o.certId,
      "useOtoroshiCertificate" -> o.useOtoroshiCertificate
    )
    override def reads(json: JsValue)  =
      Try {
        JsSuccess(
          Credential(
            certificate = (json \ "certificate").asOpt[String],
            privateKey = (json \ "privateKey").asOpt[String],
            certId = (json \ "certId").asOpt[String],
            useOtoroshiCertificate = (json \ "useOtoroshiCertificate").asOpt[Boolean].getOrElse(false)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

sealed trait SAMLProtocolBinding {
  def name: String
  def value: String
}

case object SAMLProtocolBindingPost extends SAMLProtocolBinding {
  val value = SAMLConstants.SAML2_POST_BINDING_URI
  val name  = "post"
}

case object SAMLProtocolBindingRedirect extends SAMLProtocolBinding {
  val value = SAMLConstants.SAML2_REDIRECT_BINDING_URI
  val name  = "redirect"
}
object SAMLProtocolBinding {

  val Post     = SAMLProtocolBindingPost
  val Redirect = SAMLProtocolBindingRedirect

  def apply(name: String): SAMLProtocolBinding = {
    name.toLowerCase.trim match {
      case "post"     => Post
      case "redirect" => Redirect
      case _          =>
        name match {
          case SAMLConstants.SAML2_POST_BINDING_URI     => Post
          case SAMLConstants.SAML2_REDIRECT_BINDING_URI => Redirect
          case _                                        => Redirect
        }
    }
  }
}

case class SAMLCredentials(
    signingKey: Credential,
    encryptionKey: Credential,
    signedDocuments: Boolean = false,
    encryptedAssertions: Boolean = false
)

object SAMLCredentials {
  def fmt: Format[SAMLCredentials] =
    new Format[SAMLCredentials] {
      override def writes(o: SAMLCredentials) =
        Json.obj(
          "signingKey"          -> Credential.fmt.writes(o.signingKey),
          "encryptionKey"       -> Credential.fmt.writes(o.encryptionKey),
          "signedDocuments"     -> o.signedDocuments,
          "encryptedAssertions" -> o.encryptedAssertions
        )

      override def reads(json: JsValue) =
        Try {
          JsSuccess(
            SAMLCredentials(
              signingKey = (json \ "signingKey").as[Credential](Credential.fmt),
              encryptionKey = (json \ "encryptionKey").as[Credential](Credential.fmt),
              signedDocuments = (json \ "signedDocuments").asOpt[Boolean].getOrElse(false),
              encryptedAssertions = (json \ "encryptedAssertions").asOpt[Boolean].getOrElse(false)
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }
}

sealed trait SAMLSignatureAlgorithm {
  def name: String
  def value: String
}

case object SAMLSignatureAlgorithmRSA_SHA512 extends SAMLSignatureAlgorithm {
  val value = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA512
  val name  = "rsa_sha512"
}

case object SAMLSignatureAlgorithmRSA_SHA256 extends SAMLSignatureAlgorithm {
  val value = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256
  val name  = "rsa_sha256"
}

case object SAMLSignatureAlgorithmRSA_SHA1 extends SAMLSignatureAlgorithm {
  val value = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1
  val name  = "rsa_sha1"
}

case object SAMLSignatureAlgorithmDSA_SHA1 extends SAMLSignatureAlgorithm {
  val value = SignatureConstants.ALGO_ID_SIGNATURE_DSA_SHA1
  val name  = "dsa_sha1"
}

object SAMLSignatureAlgorithm {

  val RSA_SHA512 = SAMLSignatureAlgorithmRSA_SHA512
  val RSA_SHA256 = SAMLSignatureAlgorithmRSA_SHA256
  val RSA_SHA1   = SAMLSignatureAlgorithmRSA_SHA1
  val DSA_SHA1   = SAMLSignatureAlgorithmDSA_SHA1

  def apply(name: String): Option[SAMLSignatureAlgorithm] = {
    name.toLowerCase.trim match {
      case "rsa_sha512" => Some(RSA_SHA512)
      case "rsa_sha256" => Some(RSA_SHA256)
      case "rsa_sha1"   => Some(RSA_SHA1)
      case "dsa_sha1"   => Some(DSA_SHA1)
    }
  }
}

sealed trait SAMLCanocalizationMethod {
  def name: String
  def value: String
}

case object SAMLCanocalizationMethodExclusive extends SAMLCanocalizationMethod {
  val value = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
  val name  = "exclusive"
}

case object SAMLCanocalizationMethodExclusiveWithComments extends SAMLCanocalizationMethod {
  val value = SignatureConstants.ALGO_ID_C14N_WITH_COMMENTS
  val name  = "with_comments"
}

object SAMLCanocalizationMethod {

  val Exclusive             = SAMLCanocalizationMethodExclusive
  val ExclusiveWithComments = SAMLCanocalizationMethodExclusiveWithComments

  def apply(name: String): Option[SAMLCanocalizationMethod] = {
    name.toLowerCase.trim match {
      case "exclusive"     => Some(Exclusive)
      case "with_comments" => Some(ExclusiveWithComments)
    }
  }
}

case class SAMLSignature(algorithm: SAMLSignatureAlgorithm, canocalizationMethod: SAMLCanocalizationMethod)

object SAMLSignature {
  def fmt: Format[SAMLSignature] =
    new Format[SAMLSignature] {
      override def writes(o: SAMLSignature) =
        Json.obj(
          "algorithm"            -> o.algorithm.name,
          "canocalizationMethod" -> o.canocalizationMethod.name
        )

      override def reads(json: JsValue) =
        Try {
          JsSuccess(
            SAMLSignature(
              algorithm = (json \ "algorithm")
                .asOpt[String]
                .map(n => SAMLSignatureAlgorithm(n).getOrElse(SAMLSignatureAlgorithm.RSA_SHA256))
                .getOrElse(SAMLSignatureAlgorithm.RSA_SHA256),
              canocalizationMethod = (json \ "canocalizationMethod")
                .asOpt[String]
                .map(n => SAMLCanocalizationMethod(n).getOrElse(SAMLCanocalizationMethod.Exclusive))
                .getOrElse(SAMLCanocalizationMethod.Exclusive)
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }
}

case class SamlAuthModuleConfig(
    id: String,
    name: String,
    desc: String,
    clientSideSessionEnabled: Boolean,
    sessionMaxAge: Int = 86400,
    userValidators: Seq[JsonPathValidator] = Seq.empty,
    remoteValidators: Seq[RemoteUserValidatorSettings] = Seq.empty,
    singleSignOnUrl: String,
    singleLogoutUrl: Option[String],
    ssoProtocolBinding: SAMLProtocolBinding = SAMLProtocolBinding.Redirect,
    singleLogoutProtocolBinding: SAMLProtocolBinding = SAMLProtocolBinding.Redirect,
    credentials: SAMLCredentials = SAMLCredentials(Credential(), Credential()),
    signature: SAMLSignature = SAMLSignature(
      canocalizationMethod = SAMLCanocalizationMethod.Exclusive,
      algorithm = SAMLSignatureAlgorithm.RSA_SHA256
    ),
    nameIDFormat: NameIDFormat = NameIDFormat.Unspecified,
    tags: Seq[String],
    metadata: Map[String, String],
    extraMetadata: JsObject = Json.obj(),
    issuer: String,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
    validatingCertificates: List[String] = List.empty,
    validateSignature: Boolean = false,
    validateAssertions: Boolean = false,
    usedNameIDAsEmail: Boolean = true,
    emailAttributeName: Option[String] = Some("Email"),
    sessionCookieValues: SessionCookieValues,
    adminEntityValidatorsOverride: Map[String, Map[String, Seq[JsonValidator]]] = Map.empty,
    allowedUsers: Seq[String] = Seq.empty,
    deniedUsers: Seq[String] = Seq.empty
) extends AuthModuleConfig {
  def theDescription: String           = desc
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags
  def `type`: String                   = "saml"
  def humanName: String                = "SAML v2 provider"

  override def form: Option[Form]                                       = None
  override def authModule(config: GlobalConfig): AuthModule             = new SAMLModule(this)
  override def withLocation(location: EntityLocation): AuthModuleConfig = copy(location = location)
  override def _fmt()(implicit env: Env): Format[AuthModuleConfig]      = AuthModuleConfig._fmt(env)
  override def cookieSuffix(desc: ServiceDescriptor)                    = s"saml-auth-$id"
  override def asJson                                                   = location.jsonWithKey ++ Json.obj(
    "type"                          -> "saml",
    "id"                            -> this.id,
    "name"                          -> this.name,
    "desc"                          -> this.desc,
    "sessionMaxAge"                 -> this.sessionMaxAge,
    "clientSideSessionEnabled"      -> this.clientSideSessionEnabled,
    "userValidators"                -> JsArray(userValidators.map(_.json)),
    "remoteValidators"              -> JsArray(remoteValidators.map(_.json)),
    "allowedUsers"                  -> this.allowedUsers,
    "deniedUsers"                   -> this.deniedUsers,
    "singleSignOnUrl"               -> this.singleSignOnUrl,
    "singleLogoutUrl"               -> this.singleLogoutUrl.map(JsString.apply).getOrElse(JsNull).asValue,
    "credentials"                   -> SAMLCredentials.fmt.writes(this.credentials),
    "tags"                          -> JsArray(tags.map(JsString.apply)),
    "metadata"                      -> this.metadata,
    "extraMetadata"                 -> this.extraMetadata,
    "sessionCookieValues"           -> SessionCookieValues.fmt.writes(this.sessionCookieValues),
    "issuer"                        -> this.issuer,
    "validatingCertificates"        -> this.validatingCertificates,
    "validateSignature"             -> this.validateSignature,
    "validateAssertions"            -> this.validateAssertions,
    "signature"                     -> SAMLSignature.fmt.writes(this.signature),
    "nameIDFormat"                  -> this.nameIDFormat.name,
    "ssoProtocolBinding"            -> this.ssoProtocolBinding.name,
    "singleLogoutProtocolBinding"   -> this.singleLogoutProtocolBinding.name,
    "usedNameIDAsEmail"             -> this.usedNameIDAsEmail,
    "emailAttributeName"            -> this.emailAttributeName,
    "sessionCookieValues"           -> SessionCookieValues.fmt.writes(this.sessionCookieValues),
    "adminEntityValidatorsOverride" -> JsObject(adminEntityValidatorsOverride.mapValues { o =>
      JsObject(o.mapValues(v => JsArray(v.map(_.json))))
    })
  )

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.authConfigsDataStore.set(this)
  }
}

object SAMLModule {

  lazy val logger: Logger = Logger("SAMLModule")

  def defaultConfig = SamlAuthModuleConfig(
    id = IdGenerator.namedId("auth_mod", IdGenerator.uuid),
    name = "New auth. module",
    desc = "New auth. module",
    tags = Seq.empty,
    metadata = Map.empty,
    singleSignOnUrl = "",
    singleLogoutUrl = None,
    issuer = "",
    sessionCookieValues = SessionCookieValues(),
    clientSideSessionEnabled = true
  )

  def getRequest(env: Env, samlConfig: SamlAuthModuleConfig): Future[Either[String, String]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    InitializationService.initialize()

    val builderFactory = XMLObjectProviderRegistrySupport.getBuilderFactory

    val authnRequestBuilder = builderFactory
      .getBuilder(AuthnRequest.DEFAULT_ELEMENT_NAME)
      .asInstanceOf[org.opensaml.saml.saml2.core.impl.AuthnRequestBuilder]
    val request             = authnRequestBuilder.buildObject()

    request.setProtocolBinding(samlConfig.ssoProtocolBinding.value)
    request.setDestination(samlConfig.singleSignOnUrl)

    val nameIDPolicy = buildObject(NameIDPolicy.DEFAULT_ELEMENT_NAME).asInstanceOf[NameIDPolicy]
    nameIDPolicy.setFormat(samlConfig.nameIDFormat.value)
    request.setNameIDPolicy(nameIDPolicy)

    request.setID("z" + UUID.randomUUID().toString)

    val issuer = buildObject(Issuer.DEFAULT_ELEMENT_NAME).asInstanceOf[Issuer]
    issuer.setValue(samlConfig.issuer)
    request.setIssuer(issuer)

    val subject = buildObject(Subject.DEFAULT_ELEMENT_NAME).asInstanceOf[Subject]
    val nameID  = buildObject(NameID.DEFAULT_ELEMENT_NAME).asInstanceOf[NameID]
    nameID.setValue("z" + UUID.randomUUID().toString)
    subject.setNameID(nameID)
    request.setSubject(subject)

    request.setIssueInstant(Instant.now())

    signSAMLObject(env, samlConfig, request.asInstanceOf[RequestAbstractType]).map {
      case Left(e)           => Left(e)
      case Right(samlObject) => Right(xmlToBase64Encoded(samlObject))
    }
  }

  def getLogoutRequest(
      env: Env,
      samlConfig: SamlAuthModuleConfig,
      nameId: Option[String]
  ): Future[Either[String, String]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    val request = buildObject(LogoutRequest.DEFAULT_ELEMENT_NAME).asInstanceOf[LogoutRequest]
    request.setID("z" + UUID.randomUUID().toString)

    request.setVersion(SAMLVersion.VERSION_20)
    request.setIssueInstant(ZonedDateTime.now().toInstant)

    val issuer = buildObject(Issuer.DEFAULT_ELEMENT_NAME).asInstanceOf[Issuer]
    request.setIssuer(issuer)

    val nameIDPolicy = buildObject(NameID.DEFAULT_ELEMENT_NAME).asInstanceOf[NameID]
    nameIDPolicy.setFormat(samlConfig.nameIDFormat.value)
    nameId.foreach(nameIDPolicy.setValue)
    request.setNameID(nameIDPolicy)

    signSAMLObject(env, samlConfig, request.asInstanceOf[RequestAbstractType]).map {
      case Left(e)           => Left(e)
      case Right(samlObject) => Right(xmlToBase64Encoded(samlObject))
    }
  }

  def xmlToBase64Encoded(request: RequestAbstractType): String = {
    val stringWriter = new StringWriter()
    val marshaller   = XMLObjectProviderRegistrySupport.getMarshallerFactory.getMarshaller(request)
    val dom          = marshaller.marshall(request)

    XMLHelper.writeNode(dom, stringWriter)
    val body         = stringWriter.toString
    val deflatedBody = doDeflate(body.getBytes(StandardCharsets.UTF_8))

    org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(deflatedBody)
  }

  def decodeAndValidateSamlResponse(
      env: Env,
      samlConfig: SamlAuthModuleConfig,
      encodedResponse: String,
      method: String
  ): Either[String, util.List[Assertion]] = {
    val response = parseResponse(encodedResponse, method).asInstanceOf[Response]

    decodeEncryptedAssertion(env, samlConfig, response)

    ValidatorUtils.validate(
      response,
      samlConfig.issuer,
      samlConfig.validatingCertificates.map(cert => new BasicX509Credential(encodedCertToX509Certificate(cert))),
      samlConfig.validateSignature,
      samlConfig.validateAssertions
    ) match {
      case Left(value) => Left(value)
      case Right(_)    => Right(response.getAssertions)
    }
  }

  def getPrivateKey(encodedStr: String): Either[String, PrivateKey] = {
    val matcher: Matcher = PRIVATE_KEY_PATTERN.matcher(encodedStr)
    if (!matcher.find)
      DynamicSSLEngineProvider.readPrivateKeyUniversal(
        "id",
        s"${PemHeaders.BeginPrivateKey}\n$encodedStr\n${PemHeaders.EndPrivateKey}",
        None
      )
    else
      DynamicSSLEngineProvider.readPrivateKeyUniversal("id", encodedStr, None)
  }

  def supportedKeyPairAlgorithms(): Seq[String] =
    Security
      .getProviders()
      .flatMap(_.getServices.asScala)
      .filter(_.getType.equals("KeyPairGenerator"))
      .map(_.getAlgorithm)
      .toSeq

  def encodedCertToX509Certificate(encodedStr: String): X509Certificate = {
    val isBase64Encoded = org.apache.commons.codec.binary.Base64.isBase64(encodedStr)

    val encodedCert =
      if (isBase64Encoded)
        Base64.getDecoder.decode(
          encodedStr
            .replace(PemHeaders.BeginCertificate, "")
            .replace(PemHeaders.EndCertificate, "")
            .replaceAll("\n", "")
        )
      else encodedStr.getBytes

    X509CertUtils.parse(encodedCert)
  }

  def credentialToCertificate(env: Env, credential: Credential): Future[Either[String, Option[BasicX509Credential]]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    credential match {
      case Credential(_, _, Some(certId), true)                      =>
        env.datastores.certificatesDataStore
          .findById(certId)(ec, env)
          .map { optCert =>
            optCert
              .map { cert =>
                if (logger.isDebugEnabled) logger.debug("Using certificate from store")

                getPrivateKey(cert.privateKey) match {
                  case Left(err)         => Left(err)
                  case Right(privateKey) =>
                    Right(
                      Some(
                        new BasicX509Credential(
                          cert.certificate.get,
                          privateKey
                        )
                      )
                    )
                }
              }
              .getOrElse(Left("Certificate not found"))
          }
      case Credential(Some(certificate), Some(privateKey), _, false) =>
        FastFuture.successful(getPrivateKey(privateKey) match {
          case Left(err)    => Left(err)
          case Right(value) =>
            Right(
              Some(
                new BasicX509Credential(
                  encodedCertToX509Certificate(certificate),
                  value
                )
              )
            )
        })
      case _                                                         => FastFuture.successful(Right(None))
    }
  }

  def signSAMLObject(
      env: Env,
      samlConfig: SamlAuthModuleConfig,
      samlObject: RequestAbstractType
  ): Future[Either[String, RequestAbstractType]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    if (samlConfig.credentials.signedDocuments)
      credentialToCertificate(env, samlConfig.credentials.signingKey)
        .map {
          case Left(error) => Left(error)
          case Right(cert) =>
            cert match {
              case Some(certificate) =>
                Try {
                  val signature = new SignatureBuilder().buildObject(Signature.DEFAULT_ELEMENT_NAME)
                  signature.setKeyInfo(new X509KeyInfoGeneratorFactory().newInstance().generate(certificate))
                  signature.setCanonicalizationAlgorithm(samlConfig.signature.canocalizationMethod.value)
                  signature.setSignatureAlgorithm(samlConfig.signature.algorithm.value)
                  signature.setSigningCredential(certificate)
                  samlObject.setSignature(signature)

                  val signingParams = new SignatureSigningParameters()
                  signingParams.setSigningCredential(certificate)
                  signingParams.setSignatureCanonicalizationAlgorithm(samlConfig.signature.canocalizationMethod.value)
                  signingParams.setKeyInfoGenerator(new X509KeyInfoGeneratorFactory().newInstance())
                  signingParams.setSignatureAlgorithm(samlConfig.signature.algorithm.value)
                  SignatureSupport.signObject(samlObject, signingParams)
                  Right(samlObject)
                } recover { case e @ (_: SecurityException | _: MarshallingException | _: SignatureException) =>
                  Left(e.getMessage)
                } get
              case None              => Right(samlObject)
            }
        }
    else
      FastFuture.successful(Right(samlObject))
  }

  def buildObject(qname: QName): XMLObject = {
    XMLObjectProviderRegistrySupport.getBuilderFactory
      .getBuilder(qname)
      .buildObject(qname)
      .asInstanceOf[XMLObject]
  }

  def parseResponse(encodedResponse: String, method: String): XMLObject = {
    val responseDocument = createDOMParser().parse(decodeAndInflate(encodedResponse, method))

    XMLObjectProviderRegistrySupport.getUnmarshallerFactory
      .getUnmarshaller(responseDocument.getDocumentElement)
      .unmarshall(responseDocument.getDocumentElement)
  }

  def decodeEncryptedAssertion(env: Env, samlConfig: SamlAuthModuleConfig, response: Response) = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    if (samlConfig.credentials.encryptedAssertions && response.getEncryptedAssertions.size() > 0)
      samlConfig.credentials.encryptionKey match {
        case Credential(Some(strCertificate), Some(privateKey), _, false) =>
          val certificateFactory = CertificateFactory.getInstance("X.509")
          val cert               = certificateFactory
            .generateCertificate(new ByteArrayInputStream(DynamicSSLEngineProvider.base64Decode(strCertificate)))
            .asInstanceOf[X509Certificate]

          getPrivateKey(privateKey) match {
            case Right(value) => decodeAssertionWithCertificate(response, new BasicX509Credential(cert, value))
            case Left(err)    => FastFuture.successful(())
          }

        case Credential(_, _, Some(certId), true) =>
          env.datastores.certificatesDataStore
            .findById(certId)(ec, env)
            .map { optCert =>
              optCert.map { cert =>
                DynamicSSLEngineProvider.readPrivateKeyUniversal("test", cert.privateKey, cert.password) match {
                  case Left(value)  => logger.error(value)
                  case Right(value) =>
                    decodeAssertionWithCertificate(response, new BasicX509Credential(cert.certificate.get, value))
                }
              }
            }
        case _                                    => FastFuture.successful(())
      }
  }

  def doDeflate(dataBytes: Array[Byte]): Array[Byte] = {
    var compBufSize          = 655316
    if (compBufSize < dataBytes.length + 5) {
      compBufSize = dataBytes.length + 5
    }
    val compBuf              = new Array[Byte](compBufSize)
    val compresser           = new Deflater(9, true)
    compresser.setInput(dataBytes)
    compresser.finish
    val compressedDataLength = compresser.deflate(compBuf)
    val compressedData       = new Array[Byte](compressedDataLength)
    System.arraycopy(compBuf, 0, compressedData, 0, compressedDataLength)
    compressedData
  }

  def decodeAssertionWithCertificate(response: Response, certificate: BasicX509Credential): Future[Unit] = {
    val resolverChain = new util.ArrayList[KeyInfoCredentialResolver]()
    resolverChain.add(new StaticKeyInfoCredentialResolver(certificate))

    response.getEncryptedAssertions.forEach(encryptedAssertion => {
      val decrypter =
        new Decrypter(null, new ChainingKeyInfoCredentialResolver(resolverChain), new InlineEncryptedKeyResolver())

      decrypter.setRootInNewDocument(true)

      val decryptedAssertion = decrypter.decrypt(encryptedAssertion)
      response.getAssertions.add(decryptedAssertion)
    })

    FastFuture.successful(())
  }

  def createDOMParser(): BasicParserPool = {
    val basicParserPool = new BasicParserPool()
    basicParserPool.initialize()
    basicParserPool
  }

  def decodeAndInflate(encodedResponse: String, method: String): Reader = {
    val afterB64Decode = new ByteArrayInputStream(org.apache.commons.codec.binary.Base64.decodeBase64(encodedResponse))

    if ("GET".equals(method)) {
      val afterInflate = new InflaterInputStream(afterB64Decode, new Inflater(true))
      new InputStreamReader(afterInflate, StandardCharsets.UTF_8)
    } else
      new InputStreamReader(afterB64Decode, StandardCharsets.UTF_8)
  }
}

object XMLHelper {
  def writeNode(node: Node, output: Writer): Unit = {
    val domImplLS = (node match {
      case n: Document => n.getImplementation
      case n           => n.getOwnerDocument.getImplementation
    })
      .getFeature("LS", "3.0")
      .asInstanceOf[DOMImplementationLS]

    val serializer = domImplLS.createLSSerializer()

    val serializerOut = domImplLS.createLSOutput()
    serializerOut.setCharacterStream(output)

    serializer.write(node, serializerOut)
  }
}
