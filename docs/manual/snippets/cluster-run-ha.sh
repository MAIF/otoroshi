// #no_docker
haproxy -c -f ./haproxy.cfg
// #no_docker

// #docker_linux
docker run -p 8080:8080 -v $(pwd)/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro --add-host=host.docker.internal:host-gateway haproxy
// #docker_linux

// #docker_mac
docker run -p 8080:8080 -v $(pwd)/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro haproxy
// #docker_mac

// #docker_windows
docker run -p 8080:8080 -v $(pwd)/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro haproxy
// #docker_windows