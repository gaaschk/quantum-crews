package com.quantumhorizon.quantumcrews

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream

@Component
class RequestBoundary:OncePerRequestFilter() {
 override fun doFilterInternal(request:HttpServletRequest,response:HttpServletResponse,chain:FilterChain) {
  response.setHeader("X-Content-Type-Options","nosniff")
  response.setHeader("X-Frame-Options","DENY")
  response.setHeader("Referrer-Policy","no-referrer")
  response.setHeader("Cache-Control","no-store")
  if(request.method !in setOf("POST","PUT","PATCH")) {chain.doFilter(request,response);return}
  val bytes=request.inputStream.readNBytes(131073)
  if(bytes.size>131072) {
   response.status=413;response.contentType="application/json";response.writer.write("{\"message\":\"Request body exceeds 128 KiB\"}");return
  }
  chain.doFilter(object:HttpServletRequestWrapper(request){
   override fun getInputStream():ServletInputStream {
    val input=ByteArrayInputStream(bytes)
    return object:ServletInputStream(){
     override fun read()=input.read()
     override fun isFinished()=input.available()==0
     override fun isReady()=true
     override fun setReadListener(listener:ReadListener){throw UnsupportedOperationException("Blocking requests only")}
    }
   }
  },response)
 }
}
