# Secure an app and/or your Otoroshi UI with LDAP

### Resume

- Download and run Otoroshi
- Deploy a simple LDAP server with docker and OpenLDAP
- Connect to Otoroshi with LDAP authentication

#### Downloading Otoroshi

Let's start by downloading the latest Otoroshi
```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-dev/otoroshi.jar'
```

By default, Otoroshi starts with domain oto.tools that targets 127.0.0.1
```sh
sudo nano /etc/hosts

# Add this line at the bottom of your file
127.0.0.1	otoroshi.oto.tools privateapps.oto.tools otoroshi-api.oto.tools otoroshi-admin-internal-api.oto.tools localhost
```

Run Otoroshi
```sh
java -jar otoroshi.jar
```

This should display
```sh
$ java -jar otoroshi.jar

[info] otoroshi-env - Otoroshi version 1.5.0-beta.7
[info] otoroshi-env - Admin API exposed on http://otoroshi-api.oto.tools:8080
[info] otoroshi-env - Admin UI  exposed on http://otoroshi.oto.tools:8080
[warn] otoroshi-env - Scripting is enabled on this Otoroshi instance !
[info] otoroshi-in-memory-datastores - Now using InMemory DataStores
[info] otoroshi-env - The main datastore seems to be empty, registering some basic services
[info] otoroshi-env - You can log into the Otoroshi admin console with the following credentials: admin@otoroshi.io / xol1Kwjzqe9OXjqDxxPPbPb9p0BPjhCO
[info] play.api.Play - Application started (Prod)
[info] otoroshi-script-manager - Compiling and starting scripts ...
[info] otoroshi-script-manager - Finding and starting plugins ...
[info] otoroshi-script-manager - Compiling and starting scripts done in 18 ms.
[info] p.c.s.AkkaHttpServer - Listening for HTTP on /0:0:0:0:0:0:0:0:8080
[info] p.c.s.AkkaHttpServer - Listening for HTTPS on /0:0:0:0:0:0:0:0:8443
[info] otoroshi-script-manager - Finding and starting plugins done in 4681 ms.
[info] otoroshi-env - Generating CA certificate for Otoroshi self signed certificates ...
[info] otoroshi-env - Generating a self signed SSL certificate for https://*.oto.tools ...
````

#### Running an simple OpenLDAP server 

Run OpenLDAP docker image : 
```sh
docker run \
-p 389:389 \
-p 636:636  \
--env LDAP_ORGANISATION="Otoroshi company" \
--env LDAP_DOMAIN="otoroshi.tools" \
--env LDAP_ADMIN_PASSWORD="otoroshi" \
--env LDAP_READONLY_USER="false" \
--env LDAP_TLS"false" \
--env LDAP_TLS_ENFORCE"false" \
--name my-openldap-container \
--detach osixia/openldap:1.5.0
```

Let's make the first search in our LDAP container :
```sh
docker exec my-openldap-container ldapsearch -x -H ldap://localhost -b dc=otoroshi,dc=tools -D "cn=admin,dc=otoroshi,dc=tools" -w otoroshi
```

This should output :
```sh
# extended LDIF
 ...
# otoroshi.tools
dn: dc=otoroshi,dc=tools
objectClass: top
objectClass: dcObject
objectClass: organization
o: Otoroshi company
dc: otoroshi

# search result
search: 2
result: 0 Success
...
```

```sh
docker exec -it my-openldap-container "/bin/bash"
```
```sh
echo -e "
dn: ou=People,dc=otoroshi,dc=tools
objectclass: top
objectclass: organizationalUnit
ou: People

dn: ou=Role,dc=otoroshi,dc=tools
objectclass: top
objectclass: organizationalUnit
ou: Role

dn: uid=jhonny,ou=People,dc=otoroshi,dc=tools
objectclass: top
objectclass: person
objectclass: organizationalPerson
objectclass: inetOrgPerson
uid: jhonny
cn: Jhonny
sn: Brown
mail: jhonny@otoroshi.tools
postalCode: 88442
userPassword: password

dn: uid=einstein,ou=People,dc=otoroshi,dc=tools
objectclass: top
objectclass: person
objectclass: organizationalPerson
objectclass: inetOrgPerson
uid: einstein
cn: Einstein
sn: Wilson
mail: einstein@otoroshi.tools
postalCode: 88443
userPassword: password

dn: cn=singers,ou=Role,dc=otoroshi,dc=tools
objectclass: top
objectclass: groupOfNames
cn: singers
member: uid=jhonny,ou=People,dc=otoroshi,dc=tools

dn: cn=scientists,ou=Role,dc=otoroshi,dc=tools
objectclass: top
objectclass: groupOfNames
cn: scientists
member: uid=einstein,ou=People,dc=otoroshi,dc=tools
" > bootstrap.ldif
```

```sh
ldapadd -x -w otoroshi -D "cn=admin,dc=otoroshi,dc=tools" -f bootstrap.ldif -v
```

### Create an Authentication configuration

1. Go ahead, and navigate to http://otoroshi.oto.tools:9999
1. Click on the cog icon on the top right
1. Then `Authentication confis` button
1. And add a new configuration when clicking on the `Add item` button
1. Select the `Ldap auth. provider` in the type selector field
1. Set a basic name and description
1. Then set `ldap://localhost:389` as `LDAP Server URL`and `dc=otoroshi,dc=tools` as `Search Base`
1. Create a group filter (in the next part, we'll change this filter to spread users in different groups with given rights) with 

objectClass=groupOfNames as *Group filter* \
All as *Tenant*\
All as *Team*\
Read/Write as *Rights*

9. Set the search filter as (uid=${username})`
1. Set `cn=admin,dc=otoroshi,dc=tools` as *Admin username*
1. Set `otoroshi` as *Admin password*

> Dont' forget to save on the bottom page your configuration before to quit the page.

12. Test the connection when clicking on `Test admin connection` button

This should display a `It works!` message

13. Finally, test the user connection button and set `jhonny/password` or `einstein/password` as credentials.

This should display a `It works!` message

> Dont' forget to save on the bottom page your configuration before to quit the page.

### Register an Authentication configuration as a BackOffice Auth. configuration

1. Navigate to the *danger zone* (when clicking on the cog on the top right and selecting Danger zone)
1. Scroll to the *BackOffice auth. settings*
1. Select your last Authentication configuration (created in the previous section)
> Dont' forget to save on the bottom page your configuration before to quit the page.

> Don't care about any problems of the connection (in the case your failed the configuration), Otoroshi always activates the default authentication mode for all administrators

#### Testing your configuration

1. Disconnect from your instance
1. Then click on the *Login using third-party* button (or navigate to *http://otoroshi.oto.tools:9999/backoffice/auth0/login*)
1. Set `jhonny/password` or `einstein/password` as credentials

> A fallback solution is always available, by going to *http://otoroshi.oto.tools:9999/bo/simple/login*, for administrators in case your LDAP is not available


#### Managing rights on LDAP users

For each LDAP groups, you can affect a list of rights : 
- on an `Organization` : only ressources of an organization
- on a `Team` : only ressources belonging to this team
- and a level of rights : `Read`, `Write` or `Read/Write`









