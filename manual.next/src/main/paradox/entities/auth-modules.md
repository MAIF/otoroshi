# Authentication modules

The authentication modules are the resources to manage the the access to Otoroshi UI and to protect a service.

An authentication module can be as well use on guard to access to the Otoroshi UI as to protect an app.

A `private app` is an Otoroshi service with an authentication module.

The list of supported authentication are :

* `OAuth 2.0/2.1` : an authorization standard that allows a user to grant limited access to their resources on one site to another site, without having to expose their credentials
* `OAuth 1.0a` : the original standard for access delegation
* `In memory` : create users directly in Otoroshi with rights and metadata
* `LDAP : Lightweight Directory Access Protocol` : connect users using a set of LDAP servers
* `SAML V2 - Security Assertion Markup Language` : an open-standard, XML-based data format that allows businesses to communicate user authentication and authorization information to partner companies and enterprise applications their employees may use.

All authentication modules have a unique `id`, a `name` and a `description`.

Each module has also the following fields : 

* `Tags`: list of tags associated to the module
* `Metadata`: list of metadata associated to the module
* `HttpOnly`: if enabled, the cookie cannot be accessed through client side script, prevent cross-site scripting (XSS) by not revealing the cookie to a third party
* `Secure`: if enabled, avoid to include cookie in an HTTP Request without secure channel, typically HTTPs.
* `Session max. age`: duration until the session expired

## OAuth 2.0 / OIDC provider

If you want to secure an app or your Otoroshi UI with this provider, you can check these tutorials : @ref[Secure an app with keycloak](../how-to-s/secure-app-with-keycloak.md) or @ref[Secure an app with auth0](../how-to-s/secure-app-with-auth0.md)

* `Use cookie`: If your OAuth2 provider does not support query param in redirect uri, you can use cookies instead
* `Use json payloads`: the access token, sended to retrieve the user info, will be pass in body as JSON. If disabled, it will sended as Map.
* `Enabled PKCE flow`: This way, a malicious attacker can only intercept the Authorization Code, and they cannot exchange it for a token without the Code Verifier.
* `Disable wildcard on redirect URIs`: As of OAuth 2.1, query parameters on redirect URIs are no longer allowed
* `Refresh tokens`: Automatically refresh access token using the refresh token if available
* `Read profile from token`: if enabled, the user profile will be read from the access token, otherwise the user profile will be retrieved from the user information url
* `Super admins only`: All logged in users will have super admins rights
* `Client ID`: a public identifier of your app
* `Client Secret`: a secret known only to the application and the authorization server
* `Authorize URL`: used to interact with the resource owner and get the authorization to access the protected resource
* `Token URL`: used by the application in order to get an access token or a refresh token
* `Introspection URL`: used to validate access tokens
* `Userinfo URL`: used to retrieve the profile of the user
* `Login URL`:  used to redirect user to the login provider page
* `Logout URL`:  redirect uri used by the identity provider to redirect user after logging out
* `Callback URL`: redirect uri sended to the identity provider to redirect user after successfully connecting
* `Access token field name`: field used to search access token in the response body of the token URL call
* `Scope`: presented scopes to the user in the consent screen. Scopes are space-separated lists of identifiers used to specify what access privileges are being requested
* `Claims`: asked name/values pairs that contains information about a user.
* `Name field name`: Retrieve name from token field
* `Email field name`: Retrieve email from token field
* `Otoroshi metadata field name`: Retrieve metadata from token field
* `Otoroshi rights field name`: Retrieve user rights from user profile
* `Extra metadata`: merged with the user metadata
* `Data override`: merged with extra metadata when a user connects to a `private app`
* `Rights override`: useful when you want erase the rights of an user with only specific rights. This field is the last to be applied on the user rights.
* `Api key metadata field name`: used to extract api key metadata from the OIDC access token 
* `Api key tags field name`: used to extract api key tags from the OIDC access token 
* `Proxy host`: host of proxy behind the identify provider
* `Proxy port`: port of proxy behind the identify provider
* `Proxy principal`: user of proxy 
* `Proxy password`: password of proxy
* `OIDC config url`:  URI of the openid-configuration used to discovery documents. By convention, this URI ends with `.well-known/openid-configuration`
* `Token verification`: What kind of algorithm you want to use to verify/sign your JWT token with
* `SHA Size`: Word size for the SHA-2 hash function used
* `Hmac secret`: The Hmac secret
* `Base64 encoded secret`: Is the secret encoded with base64
* `Custom TLS Settings`: TLS settings for JWKS fetching
* `TLS loose`: if enabled, will block all untrustful ssl configs
* `Trust all`: allows any server certificates even the self-signed ones
* `Client certificates`: list of client certificates used to communicate with JWKS server
* `Trusted certificates`: list of trusted certificates received from JWKS server

## OAuth 1.0a provider

If you want to secure an app or your Otoroshi UI with this provider, you can check this tutorial : @ref[Secure an app with OAuth 1.0a](../how-to-s/secure-with-oauth1-client.md)

* `Http Method`: method used to get request token and the access token 
* `Consumer key`: the identifier portion of the client credentials (equivalent to a username)
* `Consumer secret`: the identifier portion of the client credentials (equivalent to a password)
* `Request Token URL`: url to retrieve the request token
* `Authorize URL`: used to redirect user to the login page
* `Access token URL`: used to retrieve the access token from the server
* `Profile URL`: used to get the user profile
* `Callback URL`: used to redirect user when successfully connecting
* `Rights override`: override the rights of the connected user. With JSON format, each authenticated user, using email, can be associated to a list of rights on tenants and Otoroshi teams.

## LDAP Authentication provider

If you want to secure an app or your Otoroshi UI with this provider, you can check this tutorial : @ref[Secure an app with LDAP](../how-to-s/secure-app-with-ldap.md)

* `Basic auth.`: if enabled, user and password will be extract from the `Authorization` header as a Basic authentication. It will skipped the login Otoroshi page 
* `Allow empty password`: LDAP servers configured by default with the possibility to connect without password can be secured by this module to ensure that user provides a password
* `Super admins only`: All logged in users will have super admins rights
* `Extract profile`: extract LDAP profile in the Otoroshi user
* `LDAP Server URL`: list of LDAP servers to join. Otoroshi use this list in sequence and swap to the next server, each time a server breaks in timeout
* `Search Base`: used to global filter
* `Users search base`: concat with search base to search users in LDAP
* `Mapping group filter`: map LDAP groups with Otoroshi rights
* `Search Filter`: used to filter users. *\${username}* is replace by the email of the user and compare to the given field
* `Admin username (bind DN)`: holds the name of the environment property for specifying the identity of the principal for authenticating the caller to the service
* `Admin password`: holds the name of the environment property for specifying the credentials of the principal for authenticating the caller to the service
* `Extract profile filters attributes in`: keep only attributes which are matching the regex
* `Extract profile filters attributes not in`: keep only attributes which are not matching the regex
* `Name field name`: Retrieve name from LDAP field
* `Email field name`: Retrieve email from LDAP field
* `Otoroshi metadata field name`: Retrieve metadata from LDAP field
* `Extra metadata`: merged with the user metadata
* `Data override`: merged with extra metadata when a user connects to a `private app`
* `Additional rights group`: list of virtual groups. A virtual group is composed of a list of users and a list of rights for each teams/organizations.
* `Rights override`: useful when you want erase the rights of an user with only specific rights. This field is the last to be applied on the user rights.

## In memory provider

* `Basic auth.`: if enabled, user and password will be extract from the `Authorization` header as a Basic authentication. It will skipped the login Otoroshi page 
* `Login with WebAuthn` : enabled logging by WebAuthn
* `Users`: list of users with *name*, *email* and *metadata*. The default password is *password*. The edit button is useful when you want to change the password of the user. The reset button reinitialize the password. 
* `Users raw`: show the registered users with their profile and their rights. You can edit directly each field, especially the rights of the user.

## SAML v2 provider

* `Single sign on URL`: the Identity Provider Single Sign-On URL
* `The protocol binding for the login request`: the protocol binding for the login request
* `Single Logout URL`: a SAML flow that allows the end-user to logout from a single session and be automatically logged out of all related sessions that were established during SSO
* `The protocol binding for the logout request`: the protocol binding for the logout request
* `Sign documents`: Should SAML Request be signed by Otoroshi ?
* `Validate Assertions Signature`: Enable/disable signature validation of SAML assertions
* `Validate assertions with Otoroshi certificate`: validate assertions with Otoroshi certificate. If disabled, the `Encryption Certificate` and `Encryption Private Key` fields can be used to pass a certificate and a private key to validate assertions.
* `Encryption Certificate`: certificate used to verify assertions
* `Encryption Private Key`: privaye key used to verify assertions
* `Signing Certificate`: certicate used to sign documents
* `Signing Private Key`: private key to sign documents
* `Signature al`: the signature algorithm to use to sign documents
* `Canonicalization Method`: canonicalization method for XML signatures 
* `Encryption KeyPair`: the keypair used to sign/verify assertions
* `Name ID Format`: SP and IdP usually communicate each other about a subject. That subject should be identified through a NAME-IDentifier, which should be in some format so that It is easy for the other party to identify it based on the Format
* `Use NameID format as email`: use NameID format as email. If disabled, the email will be search from the attributes
* `URL issuer`: provide the URL to the IdP's who will issue the security token
* `Validate Signature`: enable/disable signature validation of SAML responses
* `Validate Assertions Signature`: should SAML Assertions to be decrypted ?
* `Validating Certificates`: the certificate in PEM format that must be used to check for signatures.

## Related pages
* @ref[Secure an app with auth0](../how-to-s/secure-app-with-auth0.md)
* @ref[Secure an app with keycloak](../how-to-s/secure-app-with-keycloak.md)
* @ref[Secure an app with LDAP](../how-to-s/secure-app-with-ldap.md)
* @ref[Secure an app with OAuth 1.0a](../how-to-s/secure-with-oauth1-client.md)