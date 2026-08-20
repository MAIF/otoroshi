BASE=$(pwd)

cd $BASE/otoroshi
sbt "Provided / makeBom"

cd $BASE/otoroshi/javascript
npx @cyclonedx/cyclonedx-npm \
  --omit dev \
  --output-format JSON \
  --output-file otoroshi-ui.cdx.json
