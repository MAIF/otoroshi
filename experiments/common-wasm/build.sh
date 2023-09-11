rm ./target/scala-2.12/common-wasm_2.12-*.jar
sbt '+package'
rm ../../otoroshi/lib/common-wasm_2.12-*.jar
cp ./target/scala-2.12/common-wasm_2.12-*.jar ../../otoroshi/lib/