sbt '+package'
rm ../../otoroshi/lib/common-wasm_2.12-1.0.0-SNAPSHOT.jar
cp ./target/scala-2.12/common-wasm_2.12-1.0.0-SNAPSHOT.jar ../../otoroshi/lib/