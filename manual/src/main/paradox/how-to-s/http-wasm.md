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

## Tutorial

Otoroshi has its own implementation of the Http Wasm ABI that can be used by adding the Http Wasm Plugin on your route.

### Before your start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Create your first HTTP Wasm Plugin

````go
package main

import (
	"bytes"
	"fmt"
	"strings"

	httpwasm "github.com/http-wasm/http-wasm-guest-tinygo/handler"
	"github.com/http-wasm/http-wasm-guest-tinygo/handler/api"
)

func main() {
	enabledFeatures := httpwasm.Host.EnableFeatures(api.FeatureBufferRequest | api.FeatureBufferResponse | api.FeatureTrailers)
	h := handler{enabledFeatures: enabledFeatures}

	httpwasm.HandleRequestFn = h.handleRequest
}

type handler struct {
	enabledFeatures api.Features
}

func (h *handler) handleRequest(req api.Request, resp api.Response) (next bool, reqCtx uint32) {
	testID, _ := req.Headers().Get("x-httpwasm-tck-testid")
	if len(testID) == 0 {
		resp.SetStatusCode(500)
		resp.Body().WriteString("missing x-httpwasm-tck-testid header")
		return false, 0
	}

	resp.Headers().Set("x-httpwasm-tck-handled", "1")

	return
}

// func (h *handler) testGetMethod(req api.Request, resp api.Response, expectedMethod string) (next bool, reqCtx uint32) {
// 	if req.GetMethod() != expectedMethod {
// 		fail(resp, fmt.Sprintf("get_method: want %s, have %s", expectedMethod, req.GetMethod()))
// 	}
// 	return true, 0
// }

// func (h *handler) testSetMethod(req api.Request, _ api.Response) (next bool, reqCtx uint32) {
// 	req.SetMethod("POST")
// 	return true, 0
// }

// func (h *handler) testGetURI(req api.Request, resp api.Response, expectedURI string) (next bool, reqCtx uint32) {
// 	if req.GetURI() != expectedURI {
// 		fail(resp, fmt.Sprintf("get_uri: want %s, have %s", expectedURI, req.GetURI()))
// 	}
// 	return true, 0
// }

// func (h *handler) testSetURI(req api.Request, _ api.Response, uri string) (next bool, reqCtx uint32) {
// 	req.SetURI(uri)
// 	return true, 0
// }

// func (h *handler) testGetRequestHeader(req api.Request, resp api.Response, header string, expectedValue []string) (next bool, reqCtx uint32) {
// 	have := req.Headers().GetAll(header)
// 	if len(have) != len(expectedValue) {
// 		fail(resp, fmt.Sprintf("get_request_header: want %d values, have %d", len(expectedValue), len(have)))
// 		return
// 	}
// 	for i, v := range have {
// 		if v != expectedValue[i] {
// 			fail(resp, fmt.Sprintf("get_request_header: want %s, have %s", expectedValue[i], v))
// 			return
// 		}
// 	}

// 	return true, 0
// }

// func (h *handler) testGetRequestHeaderNames(req api.Request, resp api.Response, expectedNames []string) (next bool, reqCtx uint32) {
// 	have := req.Headers().Names()

// 	// Don't check an exact match since it can be tricky to control automatic headers like user-agent, we're probably
// 	// fine as long as we have all the want headers.
// 	// TODO: Confirm this suspicion

// 	for _, name := range expectedNames {
// 		found := false
// 		for _, haveName := range have {
// 			if name == haveName {
// 				found = true
// 				break
// 			}
// 		}
// 		if !found {
// 			fail(resp, fmt.Sprintf("get_header_names/request: want %s, not found. have: %v", name, have))
// 			return
// 		}
// 	}

// 	return true, 0
// }

// func (h *handler) testSetRequestHeader(req api.Request, _ api.Response, header string, value string) (next bool, reqCtx uint32) {
// 	req.Headers().Set(header, value)
// 	return true, 0
// }

// func (h *handler) testAddRequestHeader(req api.Request, _ api.Response, header string, value string) (next bool, reqCtx uint32) {
// 	req.Headers().Add(header, value)
// 	return true, 0
// }

// func (h *handler) testRemoveRequestHeader(req api.Request, _ api.Response, header string) (next bool, reqCtx uint32) {
// 	req.Headers().Remove(header)
// 	return true, 0
// }

// func (h *handler) testReadBody(req api.Request, resp api.Response, expectedBody string) (next bool, reqCtx uint32) {
// 	body := req.Body()
// 	buf := &bytes.Buffer{}
// 	sz, err := body.WriteTo(buf)
// 	if err != nil {
// 		fail(resp, fmt.Sprintf("read_body/request: error %v", err))
// 		return
// 	}

// 	if int(sz) != len(expectedBody) {
// 		fail(resp, fmt.Sprintf("read_body/request: want %d bytes, have %d", len(expectedBody), sz))
// 		return
// 	}

// 	if buf.String() != expectedBody {
// 		fail(resp, fmt.Sprintf("read_body/request: want %s, have %s", expectedBody, buf.String()))
// 		return
// 	}

// 	return true, 0
// }

// func (h *handler) testGetSourceAddr(req api.Request, resp api.Response, expectedAddr string) (next bool, reqCtx uint32) {
// 	addr := req.GetSourceAddr()
// 	raw := strings.Split(addr, ":")
// 	if len(raw) != 2 {
// 		fail(resp, fmt.Sprintf("get_source_addr: unknown colon count %s", req.GetSourceAddr()))
// 		return
// 	}
// 	if raw[0] != expectedAddr {
// 		fail(resp, fmt.Sprintf("get_source_addr: want %s, have %s", expectedAddr, req.GetSourceAddr()))
// 		return
// 	}
// 	if len(raw[1]) <= 0 || len(raw[1]) > 5 {
// 		fail(resp, fmt.Sprintf("get_source_addr: could not find port number '%s' from %s", raw[1], req.GetSourceAddr()))
// 		return
// 	}
// 	return true, 0
// }

// func fail(resp api.Response, msg string) {
// 	resp.SetStatusCode(500)
// 	resp.Headers().Set("x-httpwasm-tck-failed", msg)
// }
````