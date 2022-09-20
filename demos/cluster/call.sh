echo "\ncall api on leader\n"
curl http://api-next-gen.oto.tools:8080/api
echo "\ncall api on worker-1\n"
curl http://api-next-gen.oto.tools:8081/api
echo "\ncall api on worker-2\n"
curl http://api-next-gen.oto.tools:8082/api