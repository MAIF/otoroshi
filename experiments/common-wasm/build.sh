rm ./target/scala-2.12/common-wasm_2.12-*.jar
rm ./target/scala-2.13/common-wasm_2.13-*.jar
# sbt '+package'
sbt '+assembly'
rm ../../otoroshi/lib/common-wasm_2.12-*.jar
rm ../../../izanami-v2/lib/common-wasm_2.13-*.jar
cp ./target/scala-2.12/common-wasm_2.12-*.jar ../../otoroshi/lib/
cp ./target/scala-2.13/common-wasm_2.13-*.jar ../../../izanami-v2/lib/