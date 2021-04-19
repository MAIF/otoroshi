package auth.saml

import akka.http.scaladsl.util.FastFuture
import auth.saml.SAMLModule.{decodeAndValidateSamlResponse, encodedCertToX509Certificate, getLogoutRequest, getRequest}
import com.nimbusds.jose.util.X509CertUtils
import net.shibboleth.utilities.java.support.xml.BasicParserPool
import org.apache.pulsar.shade.org.apache.commons.io.IOUtils
import org.apache.pulsar.shade.org.apache.commons.io.input.BOMInputStream
import org.opensaml.core.xml.XMLObject
import org.opensaml.core.config.InitializationService
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.saml.common.SAMLVersion
import org.opensaml.saml.saml2.core.{Assertion, AttributeStatement, AuthnRequest, Issuer, LogoutRequest, NameID, NameIDPolicy, RequestAbstractType, Response}
import otoroshi.auth.{AuthModule, AuthModuleConfig, SessionCookieValues}

import java.io.{ByteArrayInputStream, InputStreamReader, Reader, StringWriter}
import java.nio.charset.StandardCharsets
import javax.xml.namespace.QName
import scala.util.Try
import org.opensaml.core.xml.io.MarshallingException
import org.opensaml.core.xml.schema.impl.XSStringImpl
import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.metadata.resolver.impl.DOMMetadataResolver
import org.opensaml.saml.saml2.encryption.Decrypter
import org.opensaml.saml.saml2.metadata.EntityDescriptor
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.SignatureSigningParameters
import org.opensaml.xmlsec.encryption.support.InlineEncryptedKeyResolver
import org.opensaml.xmlsec.keyinfo.KeyInfoCredentialResolver
import org.opensaml.xmlsec.keyinfo.impl.{ChainingKeyInfoCredentialResolver, StaticKeyInfoCredentialResolver, X509KeyInfoGeneratorFactory}
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.impl.SignatureBuilder
import org.opensaml.xmlsec.signature.support.{SignatureConstants, SignatureException, SignatureSupport}
import otoroshi.env.Env
import otoroshi.models.{BackOfficeUser, FromJson, GlobalConfig, PrivateAppsUser, ServiceDescriptor, TeamAccess, TenantAccess, UserRight, UserRights}
import otoroshi.security.IdGenerator
import otoroshi.utils.future.Implicits.EnhancedObject
import play.api.Logger
import play.api.libs.json.{Format, JsArray, JsError, JsString, JsSuccess, JsValue, Json}
import play.api.mvc.Results.{BadRequest, Redirect}
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}

import java.net.URLEncoder
import java.security.{KeyFactory, PrivateKey}
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.{Instant, ZonedDateTime}
import java.util
import java.util.{Base64, UUID}
import java.util.zip.{Inflater, InflaterInputStream}
import scala.concurrent.{ExecutionContext, Future}

case class SAMLModule(samlConfig: SamlAuthModuleConfig) extends AuthModule {
  override def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)
                          (implicit ec: ExecutionContext, env: Env): Future[Result] = ???

  override def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)
                       (implicit ec: ExecutionContext, env: Env): Future[Option[String]] = ???

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)
                         (implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]] = ???

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)
                          (implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val encodedRequest = getRequest(samlConfig)

    encodedRequest match {
      case Left(value) => FastFuture.successful(BadRequest(value.getMessage))
      case Right(encoded) =>
        val e = URLEncoder.encode(encoded, "UTF-8")
        //val e = "PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiPz4gPHNhbWwycDpBdXRoblJlcXVlc3QgICAgIHhtbG5zOnNhbWwycD0idXJuOm9hc2lzOm5hbWVzOnRjOlNBTUw6Mi4wOnByb3RvY29sIiAgICAgRGVzdGluYXRpb249Imh0dHA6Ly9sb2NhbGhvc3Q6ODA4MC9hdXRoL3JlYWxtcy9tYXN0ZXIvcHJvdG9jb2wvc2FtbCIgICAgIFByb3RvY29sQmluZGluZz0idXJuOm9hc2lzOm5hbWVzOnRjOlNBTUw6Mi4wOmJpbmRpbmdzOkhUVFAtUE9TVCIgICAgIFZlcnNpb249IjIuMCI+ICAgIDxzYW1sMnA6TmFtZUlEUG9saWN5IEZvcm1hdD0idXJuOm9hc2lzOm5hbWVzOnRjOlNBTUw6MS4xOm5hbWVpZC1mb3JtYXQ6dW5zcGVjaWZpZWQiIC8+IDwvc2FtbDJwOkF1dGhuUmVxdWVzdD4g"
        println(s"${samlConfig.idpUrl}?SAMLRequest=$e")
        Redirect(s"${samlConfig.idpUrl}?SAMLRequest=$e")
          .addingToSession("hash" -> env.sign(s"${samlConfig.id}:::backoffice"))(request)
        .asFuture
    }
  }

  override def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)
                       (implicit ec: ExecutionContext, env: Env): Future[Option[String]] = {

    getLogoutRequest(samlConfig, user.metadata.getOrElse("saml-id", "")) match {
      case Left(_) => FastFuture.successful(None)
      case Right(encoded) =>
        env.Ws
          .url(s"${samlConfig.idpUrl}?SAMLRequest=$encoded")
          .get()
          .map(_ => None)
    }
  }

  override def boCallback(request: Request[AnyContent], config: GlobalConfig)
                         (implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {

    import scala.collection.JavaConversions._

    request.body.asFormUrlEncoded match {
      case Some(body) =>
        val samlResponse = body("SAMLResponse").head

        println(samlResponse)

        decodeAndValidateSamlResponse(samlConfig, samlResponse, "") match {
          case Left(value)        =>
            env.logger.error(value)
            FastFuture.successful(Left(value))
          case Right(assertions)  =>
            val attributeStatements: util.List[AttributeStatement] = assertions.get(0).getAttributeStatements

            val attributes: Map[String, List[String]] = attributeStatements.flatMap(_.getAttributes)
              .toList
              .map { attribute =>
                (attribute.getName, attribute.getAttributeValues.map {
                  case value: XSStringImpl => value.getValue
                  case value: XMLObject => value.getDOM.getTextContent
                })
              }
              .groupBy(_._1)
              .map { group =>
                (group._1, group._2.flatMap(_._2))
              }

            // samlConfig ajouter email/name dedans pour le nom du champ de l'email
            // et un booléen pour savoir si on prend le NameId ou si on cherche l'email dans les attributes
            val email            = attributes.get("Email").map(_.head).getOrElse("no.name@oto.tools")
            val name             = attributes.get("Name").map(_.head).getOrElse("No name")

            val id = assertions.get(0).getSubject.getNameID.getValue

            FastFuture.successful(Right(
              BackOfficeUser(
                randomId = IdGenerator.token(64),
                name = name,
                profile = Json.obj(
                  "name" -> name,
                  "email" -> email
                ),
                email = email,
                authConfigId = samlConfig.id,
                simpleLogin = false,
                tags = Seq.empty,
                metadata = Map("saml-id"-> id),
                rights = UserRights(
                  Seq(
                    UserRight(
                      TenantAccess(samlConfig.location.tenant.value),
                      samlConfig.location.teams.map(t => TeamAccess(t.value))
                    )
                  )
                ),
                location = samlConfig.location
              )
            ))
        }
      case None => FastFuture.successful(Left(""))
    }
  }
}

object SamlAuthModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger: Logger = Logger("otoroshi-global-saml-config")

  val _fmt = new Format[SamlAuthModuleConfig] {
    override def reads(json: JsValue) = {
      fromJson(json) match {
        case Left(e)    => JsError(e.getMessage)
        case Right(v)   => JsSuccess(v.asInstanceOf[SamlAuthModuleConfig])
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
          idpUrl = (json \ "idpUrl").as[String],
          credentials =  (json \ "credentials").as[SAMLCredentials](SAMLCredentials.fmt),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          issuer = (json \ "issuer").as[String],
          protocolBinding = (json \ "protocolBinding")
            .asOpt[String]
            .map(n => SAMLProtocolBinding(n).getOrElse(SAMLProtocolBinding.Post))
            .getOrElse(SAMLProtocolBinding.Post),
          validatingCertificates = (json \ "validatingCertificates")
            .asOpt[List[String]]
            .getOrElse(List.empty[String]),
          validateSignature = (json \ "validateSignature").as[Boolean],
          nameIDFormat = (json \ "nameIDFormat")
            .asOpt[String]
            .map(n => NameIDFormat(n).getOrElse(NameIDFormat.Transient))
            .getOrElse(NameIDFormat.Transient),
          signature = (json \ "signature").as[SAMLSignature](SAMLSignature.fmt)
          // assertionConsumerServiceUrl = (json \ "assertionConsumerServiceUrl").as[String],
          // relyingPartyIdentifier = (json \ "relyingPartyIdentifier").as[String],
        )
      )
    } recover {
      case e => Left(e)
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
        else
          Right(
            SamlAuthModuleConfig(
              id = IdGenerator.token,
              name = "SAML Module",
              desc = "SAML Module",
              idpUrl = idpssoDescriptor.getSingleSignOnServices.get(0).getLocation,
              issuer = entityDescriptor.getEntityID,
              protocolBinding = SAMLProtocolBinding.Post,
              validatingCertificates = List.empty,
              tags = Seq.empty
            )
          )
      }
    }
  }
}

sealed trait NameIDFormat {
  def value: String
  def name: String
}


object NameIDFormat {
  case object Persistent extends NameIDFormat {
    val value = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"
    val name = "persistent"
  }
  case object Transient extends NameIDFormat {
    val value = "urn:oasis:names:tc:SAML:2.0:nameid-format:transient"
    val name = "transient"
  }
  case object Kerberos extends NameIDFormat {
    val value = "urn:oasis:names:tc:SAML:2.0:nameid-format:kerberos"
    val name = "kerberos"
  }
  case object Entity extends NameIDFormat {
    val value = "urn:oasis:names:tc:SAML:2.0:nameid-format:entity"
    val name = "entity"
  }
  case object EmailAddress extends NameIDFormat {
    val value = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"
    val name = "emailAddress"
  }
  case object Unspecified extends NameIDFormat {
    val value = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified"
    val name = "unspecified"
  }
  def apply(name: String): Option[NameIDFormat] = {
    name.toLowerCase.trim match {
      case "persistent"   => Some(Persistent)
      case "transient"    => Some(Transient)
      case "kerberos"     => Some(Kerberos)
      case "entity"       => Some(Entity)
      case "emailAddress" => Some(EmailAddress)
      case "unspecified"  => Some(Unspecified)
    }
  }
}

case class Credential (certificate: String, privateKey: String, certId: String)

object Credential {
  def fmt = new Format[Credential] {
    override def writes(o: Credential) = Json.obj(
      "certificate" -> o.certificate,
      "privateKey"  -> o.privateKey,
      "certId" -> o.certId
    )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          Credential(
            certificate = (json \ "certificate").as[String],
            privateKey = (json \ "privateKey").as[String],
            certId = (json \ "certId").as[String]
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

object SAMLProtocolBinding {
  case object Post extends SAMLProtocolBinding {
    val value = SAMLConstants.SAML2_POST_BINDING_URI
    val name = "post"
  }

  case object Redirect extends SAMLProtocolBinding {
    val value = SAMLConstants.SAML2_REDIRECT_BINDING_URI
    val name = "redirect"
  }

  def apply(name: String): Option[SAMLProtocolBinding] = {
    name.toLowerCase.trim match {
      case "post" => Some(Post)
      case "redirect" => Some(Redirect)
    }
  }
}

case class SAMLCredentials (signingKey: Option[Credential], encryptionKey: Option[Credential])

object SAMLCredentials {
  def fmt: Format[SAMLCredentials] =
    new Format[SAMLCredentials] {
      override def writes(o: SAMLCredentials) =
        Json.obj(
          "signingKey" -> o.signingKey.map(Credential.fmt.writes),
          "encryptionKey"   -> o.encryptionKey.map(Credential.fmt.writes)
        )

      override def reads(json: JsValue) =
        Try {
          JsSuccess(
            SAMLCredentials(
              signingKey = (json \ "signingKey").asOpt[Credential](Credential.fmt),
              encryptionKey = (json \ "encryptionKey").asOpt[Credential](Credential.fmt)
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

object SAMLSignatureAlgorithm {
  case object RSA_SHA512 extends SAMLSignatureAlgorithm {
    val value = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA512
    val name = "rsa_sha512"
  }

  case object RSA_SHA256 extends SAMLSignatureAlgorithm {
    val value = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256
    val name = "rsa_sha256"
  }

  case object RSA_SHA1 extends SAMLSignatureAlgorithm {
    val value = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1
    val name = "rsa_sha1"
  }

  case object DSA_SHA1 extends SAMLSignatureAlgorithm {
    val value = SignatureConstants.ALGO_ID_SIGNATURE_DSA_SHA1
    val name = "dsa_sha1"
  }

  def apply(name: String): Option[SAMLSignatureAlgorithm] = {
    name.toLowerCase.trim match {
      case "rsa_sha512" => Some(RSA_SHA512)
      case "rsa_sha256" => Some(RSA_SHA256)
      case "rsa_sha1" => Some(RSA_SHA1)
      case "dsa_sha1" => Some(DSA_SHA1)
    }
  }
}

sealed trait SAMLCanocalizationMethod {
  def name: String
  def value: String
}

object SAMLCanocalizationMethod {
  case object Exclusive extends SAMLCanocalizationMethod {
    val value = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
    val name = "exclusive"
  }

  case object ExclusiveWithComments extends SAMLCanocalizationMethod {
    val value = SignatureConstants.ALGO_ID_C14N_WITH_COMMENTS
    val name = "with_comments"
  }

  def apply(name: String): Option[SAMLCanocalizationMethod] = {
    name.toLowerCase.trim match {
      case "exclusive" => Some(Exclusive)
      case "with_comments" => Some(ExclusiveWithComments)
    }
  }
}

case class SAMLSignature (algorithm: SAMLSignatureAlgorithm, canocalizationMethod: SAMLCanocalizationMethod)

object SAMLSignature {
  def fmt: Format[SAMLSignature] =
    new Format[SAMLSignature] {
      override def writes(o: SAMLSignature) =
        Json.obj(
          "algorithm" -> o.algorithm.name,
          "canocalizationMethod"   -> o.canocalizationMethod.name
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

case class SamlAuthModuleConfig (
                        id: String,
                        name: String,
                        desc: String,
                        sessionMaxAge: Int = 86400,
                        idpUrl: String,
                        protocolBinding: SAMLProtocolBinding = SAMLProtocolBinding.Redirect,
                        credentials: SAMLCredentials = SAMLCredentials(None, None),
                        signature: SAMLSignature = SAMLSignature(
                          canocalizationMethod = SAMLCanocalizationMethod.Exclusive,
                          algorithm = SAMLSignatureAlgorithm.RSA_SHA256
                        ),
                        nameIDFormat: NameIDFormat = NameIDFormat.Unspecified,
                        tags: Seq[String],
                        issuer: String,
                        location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
                        validatingCertificates: List[String] = List.empty,
                        validateSignature: Boolean = false
                        // assertionConsumerServiceUrl: String,
                        // relyingPartyIdentifier: String,
 ) extends AuthModuleConfig {
  def theDescription: String = desc
  def theMetadata: Map[String,String] = metadata
  def theName: String = name
  def theTags: Seq[String] = tags
  def `type`: String                                                   = "saml"
  override def authModule(config: GlobalConfig): AuthModule            = SAMLModule(this)
  override def asJson                                                  = location.jsonWithKey ++ Json.obj(
      "type"                 -> "saml",
      "id"                          -> this.id,
      "name"                        -> this.name,
      "desc"                        -> this.desc,
      "sessionMaxAge"               -> this.sessionMaxAge,
      "idpUrl"                      -> this.idpUrl,
      // "assertionConsumerServiceUrl" -> this.assertionConsumerServiceUrl,
      "credentials"                 -> SAMLCredentials.fmt.writes(this.credentials),
      "tags"                        -> JsArray(tags.map(JsString.apply)),
      "sessionCookieValues"         -> SessionCookieValues.fmt.writes(this.sessionCookieValues),
      "issuer"                      -> this.issuer,
      "validatingCertificates"      -> this.validatingCertificates, //.map(certificateToStr),
      "validateSignature"           -> this.validateSignature,
      "signature"                   -> SAMLSignature.fmt.writes(this.signature),
      "nameIDFormat"                -> this.nameIDFormat.name,
      "protocolBinding"             -> this.protocolBinding.name
    )

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.authConfigsDataStore.set(this)
  }

  override def cookieSuffix(desc: ServiceDescriptor): String = s"global-saml-$id"
  override def metadata: Map[String, String] = metadata

  override def sessionCookieValues: SessionCookieValues = SessionCookieValues()

 /*def certificateToStr(certificate: BasicX509Credential): String = {
    val LINE_SEPARATOR = System.getProperty("line.separator")
    new String(
      Base64
        .getMimeEncoder(64, LINE_SEPARATOR.getBytes())
        .encode(certificate.getEntityCertificate.getEncoded)
    )
  }*/
}

object SAMLModule {
  
  lazy val logger: Logger = Logger("SAMLModule")

  def getRequest(samlConfig: SamlAuthModuleConfig): Either[Throwable, String] = {
    InitializationService.initialize()

    val builderFactory = XMLObjectProviderRegistrySupport.getBuilderFactory

    val authnRequestBuilder = builderFactory
      .getBuilder(AuthnRequest.DEFAULT_ELEMENT_NAME)
      .asInstanceOf[org.opensaml.saml.saml2.core.impl.AuthnRequestBuilder]
    val request = authnRequestBuilder.buildObject()

    println(samlConfig.protocolBinding.value)
    println(samlConfig.idpUrl)

    request.setProtocolBinding(samlConfig.protocolBinding.value)
    request.setDestination(samlConfig.idpUrl)
    request.setAssertionConsumerServiceURL("http://otoroshi.oto.tools:9999/backoffice/auth0/callback")

    val nameIDPolicy = buildObject(NameIDPolicy.DEFAULT_ELEMENT_NAME).asInstanceOf[NameIDPolicy]
    nameIDPolicy.setFormat(samlConfig.nameIDFormat.value)
    request.setNameIDPolicy(nameIDPolicy)

    val issuer = buildObject(Issuer.DEFAULT_ELEMENT_NAME).asInstanceOf[Issuer]
    issuer.setValue(samlConfig.issuer)
    request.setIssuer(issuer)

    request.setIssueInstant(Instant.now())

    signSAMLObject(samlConfig, request.asInstanceOf[RequestAbstractType]) match {
      case Left(e) => Left(e)
      case Right(samlObject) => Right(xmlToBase64Encoded(samlObject))
    }
  }

  def getLogoutRequest(samlConfig: SamlAuthModuleConfig, nameId: String): Either[Throwable, String] = {
    val request = buildObject(LogoutRequest.DEFAULT_ELEMENT_NAME).asInstanceOf[LogoutRequest]
    request.setID("z" + UUID.randomUUID().toString)

    request.setVersion(SAMLVersion.VERSION_20)
    request.setIssueInstant(ZonedDateTime.now().toInstant)
    request.setDestination(samlConfig.idpUrl)

    val issuer = buildObject(Issuer.DEFAULT_ELEMENT_NAME).asInstanceOf[Issuer]
//    issuer.setValue(samlConfig.relyingPartyIdentifier)
    request.setIssuer(issuer)

    val nameIDPolicy = buildObject(NameID.DEFAULT_ELEMENT_NAME).asInstanceOf[NameID]
    nameIDPolicy.setFormat(samlConfig.nameIDFormat.value)
    nameIDPolicy.setValue(nameId)
    request.setNameID(nameIDPolicy)

    signSAMLObject(samlConfig, request.asInstanceOf[RequestAbstractType]) match {
      case Left(e) => Left(e)
      case Right(samlObject) => Right(xmlToBase64Encoded(samlObject))
    }
  }

  def xmlToBase64Encoded(request: RequestAbstractType): String = {
    val stringWriter = new StringWriter()
    val marshaller = XMLObjectProviderRegistrySupport.getMarshallerFactory.getMarshaller(request)
    val dom = marshaller.marshall(request)

    XMLHelper.writeNode(dom, stringWriter)

    org.apache.commons.codec.binary.Base64.encodeBase64String(stringWriter.toString.getBytes(StandardCharsets.UTF_8))
  }

  def decodeAndValidateSamlResponse(
                                     samlConfig: SamlAuthModuleConfig, encodedResponse: String, method: String
                                   ): Either[String, util.List[Assertion]] = {
    val response = parseResponse(encodedResponse, method).asInstanceOf[Response]

    decodeEncryptedAssertion(samlConfig, response)

    ValidatorUtils.validate(
      response, samlConfig.issuer,
      samlConfig.validatingCertificates.map(cert => new BasicX509Credential(encodedCertToX509Certificate(cert))),
      samlConfig.validateSignature
    ) match {
      case Left(value)  => Left(value)
      case Right(_)     => Right(response.getAssertions)
    }
  }

  def getPrivateKey(encodedStr: String): PrivateKey = {
    val bytes = Base64.getDecoder.decode(encodedStr)
    KeyFactory
      .getInstance("RSA")
      .generatePrivate(new PKCS8EncodedKeySpec(bytes))
  }

  def encodedCertToX509Certificate(encodedStr: String): X509Certificate = {
    val encodedCert   = Base64.getDecoder.decode(encodedStr)
    X509CertUtils.parse(encodedCert)
  }

  def credentialToCertificate(credential: Credential): BasicX509Credential = {
    new BasicX509Credential(
      encodedCertToX509Certificate(credential.certificate),
      getPrivateKey(credential.privateKey))
  }

  def signSAMLObject(samlConfig: SamlAuthModuleConfig, samlObject: RequestAbstractType): Either[Throwable, RequestAbstractType] = {
    samlConfig.credentials.signingKey match {
      case Some(credential) =>
        Try {
          val certificate = credentialToCertificate(credential)

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
        } recover {
          case e @ (_: SecurityException | _: MarshallingException | _: SignatureException) => Left(e)
        } get
      case None => Right(samlObject)
    }
  }

  def buildObject(qname: QName): XMLObject = {
    XMLObjectProviderRegistrySupport
      .getBuilderFactory
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

  def decodeEncryptedAssertion(samlConfig: SamlAuthModuleConfig, response: Response): Unit = {
    samlConfig.credentials.encryptionKey match {
      case Some(key) =>
        if (response.getEncryptedAssertions.size () > 0) {
          val encodedCert = Base64.getDecoder.decode(key.certificate)
          val cert = X509CertUtils.parse (encodedCert)

          val pkcs8EncodedBytes = Base64.getDecoder.decode(key.privateKey)
          val keySpec = new PKCS8EncodedKeySpec(pkcs8EncodedBytes)
          val kf = KeyFactory.getInstance("RSA")
          val privateKey = kf.generatePrivate(keySpec)

          val certificate = new BasicX509Credential(cert, privateKey)

          val resolverChain = new util.ArrayList[KeyInfoCredentialResolver]()
          resolverChain.add(new StaticKeyInfoCredentialResolver(certificate))

          response.getEncryptedAssertions.forEach(encryptedAssertion => {
            val decrypter = new Decrypter(
              null,
              new ChainingKeyInfoCredentialResolver(resolverChain),
              new InlineEncryptedKeyResolver())

            decrypter.setRootInNewDocument(true)

            val decryptedAssertion = decrypter.decrypt(encryptedAssertion)
            response.getAssertions.add(decryptedAssertion)
          })
      }
      case None =>
    }
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














