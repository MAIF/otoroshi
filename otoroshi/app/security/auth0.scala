package security

case class Auth0Config(secret: String, clientId: String, callbackURL: String, domain: String)
