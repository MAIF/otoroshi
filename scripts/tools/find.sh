# ack '(1\.1\.1|1\.1\.2)' \
# ack '1\.1\.1' \
ack $1 \
  --ignore-dir=node_modules \
  --ignore-dir=docs \
  --ignore-dir=target \
  --ignore-dir=bundle \
  --ignore-file=is:yarn.lock \
  --ignore-file=is:Cargo.lock \
  --ignore-dir=.idea \
  --ignore-dir=otoroshi/.idea \
  --ignore-file=is:swagger-ui-bundle.js \
  --ignore-dir=otoroshi/project \
  --ignore-dir=manual/project \
  --ignore-dir=docker/dev