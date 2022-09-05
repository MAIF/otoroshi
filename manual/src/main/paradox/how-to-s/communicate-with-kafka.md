# Communicate with Kafka

Every matching event can be sent to an [Apache Kafka topic](https://kafka.apache.org/).

### SASL mechanism

Create a `docker-compose.yml` with the following content

````yml
version: "2"

services:
  zookeeper:
    image: docker.io/bitnami/zookeeper:3.8
    ports:
      - "2181:2181"
    environment:
      - ALLOW_ANONYMOUS_LOGIN=yes
  kafka:
    image: docker.io/bitnami/kafka:3.2
    ports:
      - "9092:9092"
    environment:
      - KAFKA_CFG_ZOOKEEPER_CONNECT=zookeeper:2181
      - KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,CLIENT:SASL_PLAINTEXT
      - ALLOW_PLAINTEXT_LISTENER=yes
      - KAFKA_CFG_LISTENERS=INTERNAL://:9093,CLIENT://:9092
      - KAFKA_CFG_ADVERTISED_LISTENERS=INTERNAL://kafka:9093,CLIENT://kafka:9092
      - KAFKA_CFG_INTER_BROKER_LISTENER_NAME=INTERNAL
      - KAFKA_CLIENT_USERS=user
      - KAFKA_CLIENT_PASSWORDS=password

    depends_on:
      - zookeeper
````

Launch the command to create the zookeeper and kafka containers

````bash
docker-compose up -d
````

Create a new exporter on your Otoroshi instance with the following values

@@@ div { .centered-img }
<img src="../imgs/kafka-sasl-plaintext.png" />
@@@

### PLAINTEXT mechanism

Create a `docker-compose.yml` with the following content

````yml
version: "2"

services:
  zookeeper:
    image: docker.io/bitnami/zookeeper:3.8
    ports:
      - "2181:2181"
    environment:
      - ALLOW_ANONYMOUS_LOGIN=yes
  kafka:
    image: docker.io/bitnami/kafka:3.2
    ports:
      - "9092:9092"
    environment:
      - KAFKA_CFG_ZOOKEEPER_CONNECT=zookeeper:2181
      - KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,CLIENT:PLAINTEXT
      - ALLOW_PLAINTEXT_LISTENER=yes
      - KAFKA_CFG_LISTENERS=INTERNAL://:9093,CLIENT://:9092
      - KAFKA_CFG_ADVERTISED_LISTENERS=INTERNAL://kafka:9093,CLIENT://kafka:9092
      - KAFKA_CFG_INTER_BROKER_LISTENER_NAME=INTERNAL

    depends_on:
      - zookeeper
````

Launch the command to create the zookeeper and kafka containers

````bash
docker-compose up -d
````

Create a new exporter on your Otoroshi instance with the following values

@@@ div { .centered-img }
<img src="../imgs/kafka-plaintext.png" />
@@@

### SSL mechanism

````bash
wget https://raw.githubusercontent.com/confluentinc/confluent-platform-security-tools/master/kafka-generate-ssl.sh
````

````bash
chmod +x kafka-generate-ssl.sh
````

Create a `docker-compose.yml` with the following content

````yml
version: '3.5'

services:

  zookeeper:
    image: "wurstmeister/zookeeper:latest"
    ports:
      - "2181:2181"

  kafka:
    image: wurstmeister/kafka:2.12-2.2.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_ADVERTISED_LISTENERS: 'SSL://kafka:9092'
      KAFKA_LISTENERS: 'SSL://0.0.0.0:9092'
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true'
      KAFKA_ZOOKEEPER_CONNECT: 'zookeeper:2181'
      KAFKA_SSL_KEYSTORE_LOCATION: '/keystore/kafka.keystore.jks'
      KAFKA_SSL_KEYSTORE_PASSWORD: 'otoroshi'
      KAFKA_SSL_KEY_PASSWORD: 'otoroshi'
      KAFKA_SSL_TRUSTSTORE_LOCATION: '/truststore/kafka.truststore.jks'
      KAFKA_SSL_TRUSTSTORE_PASSWORD: 'otoroshi'
      KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM: ''
      KAFKA_CFG_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM: ''
      KAFKA_SECURITY_INTER_BROKER_PROTOCOL: 'SSL'
    volumes:
      - ./truststore:/truststore
      - ./keystore:/keystore
````

Launch the command to create the zookeeper and kafka containers

````bash
docker-compose up -d
````

Create a new exporter on your Otoroshi instance with the following values

@@@ div { .centered-img }
<img src="../imgs/kafka-ssl.png" />
@@@

### SASL_SSL mechanism

Generate the TLS certificates for the Kafka broker.

Create a file `generate.sh` with the following content and run the command

````bash
chmod +x generate.sh && ./generate.sh
````

````bash
# Content of the generate.sh file

version: '3.5'

services:

  zookeeper:
    image: "bitnami/zookeeper:latest"
    ports:
      - "2181:2181"
    environment:
      - ALLOW_ANONYMOUS_LOGIN=yes

  kafka:
    image: bitnami/kafka:latest
    depends_on:
      - zookeeper
    ports:
      - '9092:9092'
    environment:
      ALLOW_PLAINTEXT_LISTENER: 'yes'
      KAFKA_ZOOKEEPER_PROTOCOL: 'PLAINTEXT'
      KAFKA_CFG_ZOOKEEPER_CONNECT: 'zookeeper:2181'
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: 'INTERNAL:PLAINTEXT,CLIENT:SASL_SSL'
      KAFKA_CFG_LISTENERS: 'INTERNAL://:9093,CLIENT://:9092'
      KAFKA_INTER_BROKER_LISTENER_NAME: 'INTERNAL'
      KAFKA_CFG_ADVERTISED_LISTENERS: 'INTERNAL://kafka:9093,CLIENT://kafka:9092'
      KAFKA_CLIENT_USERS: 'user'
      KAFKA_CLIENT_PASSWORDS: 'password'
      KAFKA_CERTIFICATE_PASSWORD: 'otoroshi'
      KAFKA_TLS_TYPE: 'JKS'
      KAFKA_OPTS: "-Djava.security.auth.login.config=/opt/kafka/kafka_server_jaas.conf"
    volumes:
      - ./secrets/kafka_server_jaas.conf:/opt/kafka/kafka_server_jaas.conf
      - ./truststore/kafka.truststore.jks:/opt/bitnami/kafka/config/certs/kafka.truststore.jks:ro
      - ./keystore/kafka.keystore.jks:/opt/bitnami/kafka/config/certs/kafka.keystore.jks:ro
 79966b@PMP00131  ~/Downloads/kafka_ssl_setup-master 
 79966b@PMP00131  ~/Downloads/kafka_ssl_setup-master  cat generate.sh
#!/usr/bin/env bash

set -e

KEYSTORE_FILENAME="kafka.keystore.jks"
VALIDITY_IN_DAYS=3650
DEFAULT_TRUSTSTORE_FILENAME="kafka.truststore.jks"
TRUSTSTORE_WORKING_DIRECTORY="truststore"
KEYSTORE_WORKING_DIRECTORY="keystore"
CA_CERT_FILE="ca-cert"
KEYSTORE_SIGN_REQUEST="cert-file"
KEYSTORE_SIGN_REQUEST_SRL="ca-cert.srl"
KEYSTORE_SIGNED_CERT="cert-signed"

function file_exists_and_exit() {
  echo "'$1' cannot exist. Move or delete it before"
  echo "re-running this script."
  exit 1
}

if [ -e "$KEYSTORE_WORKING_DIRECTORY" ]; then
  file_exists_and_exit $KEYSTORE_WORKING_DIRECTORY
fi

if [ -e "$CA_CERT_FILE" ]; then
  file_exists_and_exit $CA_CERT_FILE
fi

if [ -e "$KEYSTORE_SIGN_REQUEST" ]; then
  file_exists_and_exit $KEYSTORE_SIGN_REQUEST
fi

if [ -e "$KEYSTORE_SIGN_REQUEST_SRL" ]; then
  file_exists_and_exit $KEYSTORE_SIGN_REQUEST_SRL
fi

if [ -e "$KEYSTORE_SIGNED_CERT" ]; then
  file_exists_and_exit $KEYSTORE_SIGNED_CERT
fi

echo
echo "Welcome to the Kafka SSL keystore and truststore generator script."

echo
echo "First, do you need to generate a trust store and associated private key,"
echo "or do you already have a trust store file and private key?"
echo
echo -n "Do you need to generate a trust store and associated private key? [yn] "
read generate_trust_store

trust_store_file=""
trust_store_private_key_file=""

if [ "$generate_trust_store" == "y" ]; then
  if [ -e "$TRUSTSTORE_WORKING_DIRECTORY" ]; then
    file_exists_and_exit $TRUSTSTORE_WORKING_DIRECTORY
  fi

  mkdir $TRUSTSTORE_WORKING_DIRECTORY
  echo
  echo "OK, we'll generate a trust store and associated private key."
  echo
  echo "First, the private key."
  echo
  echo "You will be prompted for:"
  echo " - A password for the private key. Remember this."
  echo " - Information about you and your company."
  echo " - NOTE that the Common Name (CN) is currently not important."

  openssl req -new -x509 -keyout $TRUSTSTORE_WORKING_DIRECTORY/ca-key \
    -out $TRUSTSTORE_WORKING_DIRECTORY/$CA_CERT_FILE -days $VALIDITY_IN_DAYS

  trust_store_private_key_file="$TRUSTSTORE_WORKING_DIRECTORY/ca-key"

  echo
  echo "Two files were created:"
  echo " - $TRUSTSTORE_WORKING_DIRECTORY/ca-key -- the private key used later to"
  echo "   sign certificates"
  echo " - $TRUSTSTORE_WORKING_DIRECTORY/$CA_CERT_FILE -- the certificate that will be"
  echo "   stored in the trust store in a moment and serve as the certificate"
  echo "   authority (CA). Once this certificate has been stored in the trust"
  echo "   store, it will be deleted. It can be retrieved from the trust store via:"
  echo "   $ keytool -keystore <trust-store-file> -export -alias CARoot -rfc"

  echo
  echo "Now the trust store will be generated from the certificate."
  echo
  echo "You will be prompted for:"
  echo " - the trust store's password (labeled 'keystore'). Remember this"
  echo " - a confirmation that you want to import the certificate"

  keytool -keystore $TRUSTSTORE_WORKING_DIRECTORY/$DEFAULT_TRUSTSTORE_FILENAME \
    -alias CARoot -import -file $TRUSTSTORE_WORKING_DIRECTORY/$CA_CERT_FILE

  trust_store_file="$TRUSTSTORE_WORKING_DIRECTORY/$DEFAULT_TRUSTSTORE_FILENAME"

  echo
  echo "$TRUSTSTORE_WORKING_DIRECTORY/$DEFAULT_TRUSTSTORE_FILENAME was created."

  # don't need the cert because it's in the trust store.
  rm $TRUSTSTORE_WORKING_DIRECTORY/$CA_CERT_FILE
else
  echo
  echo -n "Enter the path of the trust store file. "
  read -e trust_store_file

  if ! [ -f $trust_store_file ]; then
    echo "$trust_store_file isn't a file. Exiting."
    exit 1
  fi

  echo -n "Enter the path of the trust store's private key. "
  read -e trust_store_private_key_file

  if ! [ -f $trust_store_private_key_file ]; then
    echo "$trust_store_private_key_file isn't a file. Exiting."
    exit 1
  fi
fi

echo
echo "Continuing with:"
echo " - trust store file:        $trust_store_file"
echo " - trust store private key: $trust_store_private_key_file"

mkdir $KEYSTORE_WORKING_DIRECTORY

echo
echo "Now, a keystore will be generated. Each broker and logical client needs its own"
echo "keystore. This script will create only one keystore. Run this script multiple"
echo "times for multiple keystores."
echo
echo "You will be prompted for the following:"
echo " - A keystore password. Remember it."
echo " - Personal information, such as your name."
echo "     NOTE: currently in Kafka, the Common Name (CN) does not need to be the FQDN of"
echo "           this host. However, at some point, this may change. As such, make the CN"
echo "           the FQDN. Some operating systems call the CN prompt 'first / last name'"
echo " - A key password, for the key being generated within the keystore. Remember this."

# To learn more about CNs and FQDNs, read:
# https://docs.oracle.com/javase/7/docs/api/javax/net/ssl/X509ExtendedTrustManager.html

keytool -keystore $KEYSTORE_WORKING_DIRECTORY/$KEYSTORE_FILENAME \
  -alias localhost -validity $VALIDITY_IN_DAYS -genkey -keyalg RSA

echo
echo "'$KEYSTORE_WORKING_DIRECTORY/$KEYSTORE_FILENAME' now contains a key pair and a"
echo "self-signed certificate. Again, this keystore can only be used for one broker or"
echo "one logical client. Other brokers or clients need to generate their own keystores."

echo
echo "Fetching the certificate from the trust store and storing in $CA_CERT_FILE."
echo
echo "You will be prompted for the trust store's password (labeled 'keystore')"

keytool -keystore $trust_store_file -export -alias CARoot -rfc -file $CA_CERT_FILE

echo
echo "Now a certificate signing request will be made to the keystore."
echo
echo "You will be prompted for the keystore's password."
keytool -keystore $KEYSTORE_WORKING_DIRECTORY/$KEYSTORE_FILENAME -alias localhost \
  -certreq -file $KEYSTORE_SIGN_REQUEST

echo
echo "Now the trust store's private key (CA) will sign the keystore's certificate."
echo
echo "You will be prompted for the trust store's private key password."
openssl x509 -req -CA $CA_CERT_FILE -CAkey $trust_store_private_key_file \
  -in $KEYSTORE_SIGN_REQUEST -out $KEYSTORE_SIGNED_CERT \
  -days $VALIDITY_IN_DAYS -CAcreateserial
# creates $KEYSTORE_SIGN_REQUEST_SRL which is never used or needed.

echo
echo "Now the CA will be imported into the keystore."
echo
echo "You will be prompted for the keystore's password and a confirmation that you want to"
echo "import the certificate."
keytool -keystore $KEYSTORE_WORKING_DIRECTORY/$KEYSTORE_FILENAME -alias CARoot \
  -import -file $CA_CERT_FILE
rm $CA_CERT_FILE # delete the trust store cert because it's stored in the trust store.

echo
echo "Now the keystore's signed certificate will be imported back into the keystore."
echo
echo "You will be prompted for the keystore's password."
keytool -keystore $KEYSTORE_WORKING_DIRECTORY/$KEYSTORE_FILENAME -alias localhost -import \
  -file $KEYSTORE_SIGNED_CERT

echo
echo "All done!"
echo
echo "Delete intermediate files? They are:"
echo " - '$KEYSTORE_SIGN_REQUEST_SRL': CA serial number"
echo " - '$KEYSTORE_SIGN_REQUEST': the keystore's certificate signing request"
echo "   (that was fulfilled)"
echo " - '$KEYSTORE_SIGNED_CERT': the keystore's certificate, signed by the CA, and stored back"
echo "    into the keystore"
echo -n "Delete? [yn] "
read delete_intermediate_files

if [ "$delete_intermediate_files" == "y" ]; then
  rm $KEYSTORE_SIGN_REQUEST_SRL
  rm $KEYSTORE_SIGN_REQUEST
  rm $KEYSTORE_SIGNED_CERT
fi
````

Create, in the same repository, a repository named `secrets` with the following configuration.

````bash 
# Content of ~/tmp/kafka/secrets/kafka_server_jaas.conf

Client {
    org.apache.kafka.common.security.plain.PlainLoginModule required
    username="user"
    password="password";
};
````

Create a `docker-compose.yml` file with the following content.

````bash
version: '3.5'

services:

  zookeeper:
    image: "bitnami/zookeeper:latest"
    ports:
      - "2181:2181"
    environment:
      - ALLOW_ANONYMOUS_LOGIN=yes

  kafka:
    image: bitnami/kafka:latest
    depends_on:
      - zookeeper
    ports:
      - '9092:9092'
    environment:
      ALLOW_PLAINTEXT_LISTENER: 'yes'
      KAFKA_ZOOKEEPER_PROTOCOL: 'PLAINTEXT'
      KAFKA_CFG_ZOOKEEPER_CONNECT: 'zookeeper:2181'
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: 'INTERNAL:PLAINTEXT,CLIENT:SASL_SSL'
      KAFKA_CFG_LISTENERS: 'INTERNAL://:9093,CLIENT://:9092'
      KAFKA_INTER_BROKER_LISTENER_NAME: 'INTERNAL'
      KAFKA_CFG_ADVERTISED_LISTENERS: 'INTERNAL://kafka:9093,CLIENT://kafka:9092'
      KAFKA_CLIENT_USERS: 'user'
      KAFKA_CLIENT_PASSWORDS: 'password'
      KAFKA_CERTIFICATE_PASSWORD: 'otoroshi'
      KAFKA_TLS_TYPE: 'JKS'
      KAFKA_OPTS: "-Djava.security.auth.login.config=/opt/kafka/kafka_server_jaas.conf"
    volumes:
      - ./secrets/kafka_server_jaas.conf:/opt/kafka/kafka_server_jaas.conf
      - ./truststore/kafka.truststore.jks:/opt/bitnami/kafka/config/certs/kafka.truststore.jks:ro
      - ./keystore/kafka.keystore.jks:/opt/bitnami/kafka/config/certs/kafka.keystore.jks:ro
````

At this point, your repository should be 
````
/tmp/kafka
  | generate.sh
  | docker-compose.yml
  | truststore
    | kafka.truststore.jks
  | keystore 
    | kafka.keystore.jks
  | secrets 
    | kafka_server_jaas.conf
````

Launch the command to create the zookeeper and kafka containers

````bash
docker-compose up -d
````

Create a new exporter on your Otoroshi instance with the following values

@@@ div { .centered-img }
<img src="../imgs/kafka-sasl-ssl.png" />
@@@
