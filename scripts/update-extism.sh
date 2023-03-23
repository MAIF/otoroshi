# EXTISM_REPO=extism
EXTISM_REPO=MAIF

EXTISM_VERSION=$(curl https://api.github.com/repos/${EXTISM_REPO}/extism/releases/latest | jq -r '.name')

echo "latest extism version is: ${EXTISM_VERSION}"

rm -rfv ./otoroshi/conf/native
rm -rfv ./otoroshi/conf/darwin-*
rm -rfv ./otoroshi/conf/linux-*

mkdir ./otoroshi/conf/native

curl -L -o "./otoroshi/conf/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz"
curl -L -o "./otoroshi/conf/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz"
curl -L -o "./otoroshi/conf/native/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz"
curl -L -o "./otoroshi/conf/native/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz"
curl -L -o "./otoroshi/conf/native/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz"

mkdir ./otoroshi/conf/darwin-aarch64
mkdir ./otoroshi/conf/darwin-x86-64
mkdir ./otoroshi/conf/linux-aarch64
mkdir ./otoroshi/conf/linux-x86-64

tar -xvf "./otoroshi/conf/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" --directory ./otoroshi/conf/native/
mv ./otoroshi/conf/native/libextism.dylib ./otoroshi/conf/darwin-aarch64/libextism.dylib
tar -xvf "./otoroshi/conf/native/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz" --directory ./otoroshi/conf/native/
mv ./otoroshi/conf/native/libextism.dylib ./otoroshi/conf/darwin-x86-64/libextism.dylib

tar -xvf "./otoroshi/conf/native/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" --directory ./otoroshi/conf/native/
mv ./otoroshi/conf/native/libextism.so ./otoroshi/conf/linux-aarch64/libextism.so
tar -xvf "./otoroshi/conf/native/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" --directory ./otoroshi/conf/native/
mv ./otoroshi/conf/native/libextism.so ./otoroshi/conf/linux-x86-64/libextism.so
rm -rfv ./otoroshi/conf/native


# curl -L -o "./otoroshi/conf/native/libextism-aarch64-unknown-linux-musl-${EXTISM_VERSION}.tar.gz" "https://github.com/extism/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-unknown-linux-musl-${EXTISM_VERSION}.tar.gz"
# tar -xvf "./otoroshi/conf/native/libextism-aarch64-unknown-linux-musl-${EXTISM_VERSION}.tar.gz" --directory ./otoroshi/conf/native/
# curl -L -o "./otoroshi/conf/native/libextism-x86_64-pc-windows-gnu-${EXTISM_VERSION}.tar.gz" "https://github.com/extism/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-pc-windows-gnu-${EXTISM_VERSION}.tar.gz"
# curl -L -o "./otoroshi/conf/native/libextism-x86_64-pc-windows-msvc-${EXTISM_VERSION}.tar.gz" "https://github.com/extism/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-pc-windows-msvc-${EXTISM_VERSION}.tar.gz"
# mv ./otoroshi/conf/native/libextism.so ./otoroshi/conf/native/libextism-aarch64-unknown-linux-musl.so
# tar -xvf "./otoroshi/conf/native/libextism-x86_64-pc-windows-gnu-${EXTISM_VERSION}.tar.gz" --directory ./otoroshi/conf/native/