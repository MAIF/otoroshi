package io.netty.incubator.codec.http3

import io.netty.buffer.ByteBufAllocator
import io.netty.handler.codec.http.{FullHttpRequest, HttpHeaders, HttpMessage, HttpRequest, HttpVersion}

object Http3ConversionUtil {
  def addHttp3ToHttpHeaders(
      streamId: Long,
      in: Http3Headers,
      out: HttpHeaders,
      version: HttpVersion,
      isTrailer: Boolean,
      isRequest: Boolean
  ): Unit                                                                                                  =
    HttpConversionUtil.addHttp3ToHttpHeaders(streamId, in, out, version, isTrailer, isRequest)
  def toHttp3Headers(inHeaders: HttpHeaders, validateHeaders: Boolean): Http3Headers                       =
    HttpConversionUtil.toHttp3Headers(inHeaders, validateHeaders)
  def toHttp3Headers(in: HttpMessage, validate: Boolean): Http3Headers                                     =
    HttpConversionUtil.toHttp3Headers(in, validate)
  def toHttpRequest(streamId: Long, http3Headers: Http3Headers, validateHttpHeaders: Boolean): HttpRequest =
    HttpConversionUtil.toHttpRequest(streamId, http3Headers, validateHttpHeaders)
  def toFullHttpRequest(
      streamId: Long,
      http3Headers: Http3Headers,
      alloc: ByteBufAllocator,
      validateHttpHeaders: Boolean
  ): FullHttpRequest                                                                                       =
    HttpConversionUtil.toFullHttpRequest(streamId, http3Headers, alloc, validateHttpHeaders)
}
