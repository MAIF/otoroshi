#!/bin/sh

# node src/sidecar.js "$@" 

echo "Setup DNS"
cp /etc/resolv.conf /etc/resolv.back.conf
echo 'nameserver 127.0.0.1' > /etc/resolv.conf
IP=`cat /etc/resolv.back.conf | grep nameserver | awk '{print $2}'`
touch /etc/dnsmasq.conf
echo 'address=/otoroshi.mesh/127.0.0.1' >> /etc/dnsmasq.conf
echo "resolv-file=/etc/resolv.back.conf"
echo "server=/svc.cluster.local/$IP" >> /etc/dnsmasq.conf
echo "server=/cluster.local/$IP" >> /etc/dnsmasq.conf
echo "server=$IP" >> /etc/dnsmasq.conf
echo "user=root" >> /etc/dnsmasq.conf
dnsmasq --test
dnsmasq

pm2 start src/sidecar.js -i 2 --time --log sidecar.log -- "$@" 
pm2 logs