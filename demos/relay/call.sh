
echo "warmup"

curl -H 'Host: service-a-next-gen.oto.tools' http://127.0.0.1:8081/
curl -H 'Host: service-a-next-gen.oto.tools' http://127.0.0.1:8084/
curl -H 'Host: service-b-next-gen.oto.tools' http://127.0.0.1:8082/
curl -H 'Host: service-b-next-gen.oto.tools' http://127.0.0.1:8085/
curl -H 'Host: service-c-next-gen.oto.tools' http://127.0.0.1:8083/
curl -H 'Host: service-c-next-gen.oto.tools' http://127.0.0.1:8086/

echo "calling on leader-zone-1"

curl -H 'Host: service-a-next-gen.oto.tools' http://127.0.0.1:8081/
curl -H 'Host: service-b-next-gen.oto.tools' http://127.0.0.1:8081/
curl -H 'Host: service-c-next-gen.oto.tools' http://127.0.0.1:8081/

echo "calling on leader-zone-2"

curl -H 'Host: service-a-next-gen.oto.tools' http://127.0.0.1:8082/
curl -H 'Host: service-b-next-gen.oto.tools' http://127.0.0.1:8082/
curl -H 'Host: service-c-next-gen.oto.tools' http://127.0.0.1:8082/

echo "calling on leader-zone-3"

curl -H 'Host: service-a-next-gen.oto.tools' http://127.0.0.1:8083/
curl -H 'Host: service-b-next-gen.oto.tools' http://127.0.0.1:8083/
curl -H 'Host: service-c-next-gen.oto.tools' http://127.0.0.1:8083/

echo "calling on worker-zone-1"

curl -H 'Host: service-a-next-gen.oto.tools' http://127.0.0.1:8084/
curl -H 'Host: service-b-next-gen.oto.tools' http://127.0.0.1:8084/
curl -H 'Host: service-c-next-gen.oto.tools' http://127.0.0.1:8084/

echo "calling on worker-zone-2"

curl -H 'Host: service-a-next-gen.oto.tools' http://127.0.0.1:8085/
curl -H 'Host: service-b-next-gen.oto.tools' http://127.0.0.1:8085/
curl -H 'Host: service-c-next-gen.oto.tools' http://127.0.0.1:8085/

echo "calling on worker-zone-3"

curl -H 'Host: service-a-next-gen.oto.tools' http://127.0.0.1:8086/
curl -H 'Host: service-b-next-gen.oto.tools' http://127.0.0.1:8086/
curl -H 'Host: service-c-next-gen.oto.tools' http://127.0.0.1:8086/