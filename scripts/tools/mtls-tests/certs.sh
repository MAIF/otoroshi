#!/usr/bin/env bash

delete ./cert-backend.pem ./cert-backend-key.pem
delete ./cert-frontend.pem ./cert-frontend-key.pem

#openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -out cert-backend.pem -keyout cert-backend-key.pem -subj "/CN=localhost" -addext "subjectAltName = DNS:localhost"
#openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -out cert-frontend.pem -keyout cert-frontend-key.pem -subj "/CN=mtls.oto.tools" -addext "subjectAltName = DNS:mtls.oto.tools"

openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -out cert-backend.pem -keyout cert-backend-key.pem -extensions v3_ca -config ./backend.cfg
openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -out cert-frontend.pem -keyout cert-frontend-key.pem -extensions v3_ca -config ./frontend.cfg