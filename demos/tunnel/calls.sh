# call the remote service on remote leader
curl http://whoami-next-gen.oto.tools:8081/api | jq
# call the remote service on exposition leader - should fail
curl http://whoami-next-gen.oto.tools:8080/api | jq
# call the remote service on exposition worker - should fail
curl http://whoami-next-gen.oto.tools:8082/api | jq


# call the exposed service on exposition leader
curl http://whoami-exposed-next-gen.oto.tools:8080/api | jq
# call the exposed service on exposition woker
curl http://whoami-exposed-next-gen.oto.tools:8082/api | jq
# call the exposed service on remote leader - should fail
curl http://whoami-exposed-next-gen.oto.tools:8081/api | jq