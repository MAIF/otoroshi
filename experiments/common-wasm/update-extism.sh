# EXTISM_REPO=extism
EXTISM_REPO=MAIF

EXTISM_VERSION=$(curl https://api.github.com/repos/${EXTISM_REPO}/extism/releases/latest | jq -r '.name')

echo "latest extism version is: ${EXTISM_VERSION}"

rm -rfv ./src/main/resources/native
rm -rfv ./src/main/resources/darwin-*
rm -rfv ./src/main/resources/linux-*

mkdir ./src/main/resources/native

curl -L -o "./src/main/resources/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz"
curl -L -o "./src/main/resources/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz"
curl -L -o "./src/main/resources/native/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz"
curl -L -o "./src/main/resources/native/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz"
curl -L -o "./src/main/resources/native/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz"
curl -L -o "./lib/extism-${EXTISM_VERSION}.jar" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/extism-0.4.0.jar"

mkdir ./src/main/resources/darwin-aarch64
mkdir ./src/main/resources/darwin-x86-64
mkdir ./src/main/resources/linux-aarch64
mkdir ./src/main/resources/linux-x86-64

tar -xvf "./src/main/resources/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" --directory ./src/main/resources/native/
mv ./src/main/resources/native/libextism.dylib ./src/main/resources/darwin-aarch64/libextism.dylib
tar -xvf "./src/main/resources/native/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz" --directory ./src/main/resources/native/
mv ./src/main/resources/native/libextism.dylib ./src/main/resources/darwin-x86-64/libextism.dylib

tar -xvf "./src/main/resources/native/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" --directory ./src/main/resources/native/
mv ./src/main/resources/native/libextism.so ./src/main/resources/linux-aarch64/libextism.so
tar -xvf "./src/main/resources/native/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" --directory ./src/main/resources/native/
mv ./src/main/resources/native/libextism.so ./src/main/resources/linux-x86-64/libextism.so
rm -rfv ./src/main/resources/native


# curl -L -o "./src/main/resources/native/libextism-aarch64-unknown-linux-musl-${EXTISM_VERSION}.tar.gz" "https://github.com/extism/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-unknown-linux-musl-${EXTISM_VERSION}.tar.gz"
# tar -xvf "./src/main/resources/native/libextism-aarch64-unknown-linux-musl-${EXTISM_VERSION}.tar.gz" --directory ./src/main/resources/native/
# curl -L -o "./src/main/resources/native/libextism-x86_64-pc-windows-gnu-${EXTISM_VERSION}.tar.gz" "https://github.com/extism/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-pc-windows-gnu-${EXTISM_VERSION}.tar.gz"
# curl -L -o "./src/main/resources/native/libextism-x86_64-pc-windows-msvc-${EXTISM_VERSION}.tar.gz" "https://github.com/extism/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-pc-windows-msvc-${EXTISM_VERSION}.tar.gz"
# mv ./src/main/resources/native/libextism.so ./src/main/resources/native/libextism-aarch64-unknown-linux-musl.so
# tar -xvf "./src/main/resources/native/libextism-x86_64-pc-windows-gnu-${EXTISM_VERSION}.tar.gz" --directory ./src/main/resources/native/