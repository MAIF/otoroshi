package auth.saml

import org.opensaml.saml.common.SignableSAMLObject
import org.opensaml.saml.saml2.core.{LogoutRequest, RequestAbstractType, Response, StatusCode, StatusResponseType}
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.support.SignatureValidator
import play.api.Logger

import scala.util.Try

object ValidatorUtils {

  import org.opensaml.security.credential.Credential

  lazy val logger: Logger = Logger("otoroshi-saml-validator-utils")

  def validate(response: Response, responseIssuer: String,
               credentials: List[Credential], validateSign: Boolean, validateAssertions: Boolean)
  : Either[String, Unit] =
    validateResponse(response, responseIssuer) match {
      case Left(value)  => Left(value)
      case Right(_)     => validateAssertion(response, responseIssuer, credentials, validateAssertions) match {
        case Left(value)  => Left(value)
        case Right(_)     => validateSignature(response, credentials, validateSign)
      }
    }
    
    def validateStatus(response: StatusResponseType): Either[String, Unit] = {
      val statusCode = response.getStatus.getStatusCode.getValue

      if (StatusCode.SUCCESS.equals(statusCode))
        Right()
      else
        Left(s"Invalid status code: $statusCode")
    }
    
    def validateIssuer(response: StatusResponseType, responseIssuer: String): Either[String, Unit] = {
      if (response.getIssuer.getValue.equals(responseIssuer)) {
        logger.debug(s"Response Issuer validated : $responseIssuer")
        Right()
      } else
        Left("The response issuer didn't match the expected value")
    }

  def validateIssuer(request: RequestAbstractType, requestIssuer: String): Either[String, Unit] =
      if (!request.getIssuer.getValue.equals(requestIssuer)) {
        logger.debug(s"Request Issuer validated : $requestIssuer")
        Right()
      } else
        Left("The request issuer didn't match the expected value")

    def validateAssertion(response: Response, responseIssuer: String,
                          credentials: List[Credential], validateAssertions: Boolean)
    : Either[String, Unit] = {
      if (response.getAssertions.size() != 1)
        Left("The response doesn't contain exactly 1 assertion")
      else {
        val assertion = response.getAssertions.get(0)
        if (!assertion.getIssuer.getValue.equals(responseIssuer))
          Left("The assertion issuer didn't match the expected value")
        else if (assertion.getSubject.getNameID == null)
          Left("The NameID value is missing from the SAML response this is likely an IDP configuration issue")
        else if (!validate(assertion.getSignature, credentials, validateAssertions))
          Left("The assertion signature is invalid : wrong certificate")
        else
          Right()
        }
    }

    def validateSignature(response: SignableSAMLObject, credentials: List[Credential], validateSign: Boolean)
    : Either[String, Unit] = {
      if (response.getSignature != null && !validate(response.getSignature, credentials, validateSign))
        Left("The response signature is invalid")
      else {
        if (validateSign)
          logger.debug(s"Response Signature validated")
        else
          logger.debug(s"Validation of Response Signature not required")
        Right()
      }
    }

    def validate(signature: Signature, credentials: List[Credential], validateSign: Boolean): Boolean = {
      if (!validateSign)
        true
      else {
        if (credentials.isEmpty)
          false
        else
          credentials
            .exists(credential => {
              Try {
                SignatureValidator.validate(signature, credential)
                true
              } recover {
                case err: Throwable => false
              } get
            })
      }
    }

    def validateResponse(response: Response, responseIssuer: String): Either[String, Unit] = {
      validateIssuer(response, responseIssuer)
      validateStatus(response)
    }

    def validateLogoutRequest(request: LogoutRequest, requestIssuer: String, nameID: String): Either[String, Unit] = {
      validateIssuer(request, requestIssuer) match {
        case Left(value)    => Left(value)
        case Right(_)       => validateNameId(request, nameID)
      }
    }
    
    def validateNameId(request: LogoutRequest , nameID: String): Either[String, Unit] = {
      if (nameID == null || !nameID.equals(request.getNameID.getValue))
        Left("The nameID of the logout request is incorrect")
      else
        Right()
    }
}
