#!/bin/bash

echo "helllooooo"
iptables -t nat -A OUTPUT -p tcp --dport ${FROM} -j DNAT --to-destination 127.0.0.1:${TO}
iptables -t nat --list
