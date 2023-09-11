rm ./target/scala-2.12/common-wasm_2.12-*.jar
rm ./target/scala-2.13/common-wasm_2.13-*.jar
# sbt '+package'
sbt '+assembly'
rm ../../otoroshi/lib/common-wasm_2.12-*.jar
rm ../../otoroshi/lib/common-wasm_2.13-*.jar
cp ./target/scala-2.12/common-wasm_2.12-*.jar ../../otoroshi/lib/