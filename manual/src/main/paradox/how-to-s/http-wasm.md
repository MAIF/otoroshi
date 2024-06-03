# Http WASM

@@include[experimental.md](../includes/experimental.md) { .experimental-feature }

The HTTP handler ABI allows users to write portable HTTP server middleware in a language that compiles to wasm. For example, a Go HTTP service could embed routing middleware written in Zig.

ABI is available [here](https://http-wasm.io/http-handler-abi/).

HTTP-Wasm is a relatively new proposal aimed at extending the capabilities of WebAssembly (Wasm) to the realm of HTTP-based applications, particularly within the context of web servers and proxies.

## Overview

HTTP-Wasm is an initiative to leverage WebAssembly for enhancing and customizing HTTP processing tasks. The main goal is to provide a standardized environment where WebAssembly modules can be used to handle, modify, and extend HTTP request and response workflows. This can be particularly useful in scenarios such as edge computing, API gateways, web servers, and service meshes.

## Key Features and Benefits

Modularity and Flexibility: HTTP-Wasm allows developers to write modular and reusable code that can be executed in a secure and sandboxed environment. This modular approach can help in creating extensible HTTP processing pipelines.

## Security  

WebAssembly's sandboxed execution model ensures that modules run in a secure environment, reducing the risk of security vulnerabilities. This makes it a suitable choice for handling potentially untrusted code or inputs in HTTP processing.