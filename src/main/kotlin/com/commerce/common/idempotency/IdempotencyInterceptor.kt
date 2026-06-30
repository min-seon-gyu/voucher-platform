package com.commerce.common.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

private const val ATTR_OWNED_KEY = "idempotency.ownedKey"
private const val ATTR_COMPLETED = "idempotency.completed"

@Component
class IdempotencyInterceptor(
    private val store: IdempotencyStore,
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true
        if (!handler.hasMethodAnnotation(Idempotent::class.java)) return true

        val key = request.getHeader("Idempotency-Key") ?: return true

        // 1) Redis 빠른 경로 — 이미 완료된 요청이면 원본 응답 재반환
        store.findCachedResponse(key)?.let {
            log.info("Idempotency hit (Redis): {}", key)
            writeResponse(response, it)
            return false
        }

        // 2) 멱등키 선점 시도 — UNIQUE 제약을 뮤텍스로 사용
        //    동시에 같은 키로 들어온 요청 중 단 하나만 선점에 성공한다.
        val reserved = try {
            store.reserve(key)
            true
        } catch (e: DataIntegrityViolationException) {
            false
        }

        if (reserved) {
            request.setAttribute(ATTR_OWNED_KEY, key)
            return true // 이 요청만 실제 비즈니스 처리로 진행
        }

        // 3) 선점 실패 — 다른 요청이 이미 처리(중)
        val completed = store.findCompletedInDb(key)
        if (completed != null) {
            log.info("Idempotency hit (DB): {}", key)
            writeResponse(response, completed) // 완료됨 → 원본 응답 재반환
        } else {
            log.info("Idempotency in-progress, rejecting duplicate: {}", key)
            writeResponse(
                response,
                CachedResponse(
                    HttpStatus.CONFLICT.value(),
                    """{"message":"이미 처리 중인 요청입니다. 잠시 후 다시 시도해 주세요."}"""
                )
            )
        }
        return false
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val key = request.getAttribute(ATTR_OWNED_KEY) as? String ?: return
        val completed = request.getAttribute(ATTR_COMPLETED) as? Boolean ?: false
        if (!completed) {
            // 비즈니스 처리가 실패(예외/4xx·5xx)했다면 선점을 해제해 재시도가 가능하게 한다.
            store.release(key)
        }
    }

    /** 선점한 요청이 성공 응답을 만들었을 때 호출 — DB COMPLETED + Redis 캐시 */
    fun markCompleted(request: HttpServletRequest, body: String, status: Int) {
        val key = request.getAttribute(ATTR_OWNED_KEY) as? String ?: return
        store.complete(key, body, status)
        request.setAttribute(ATTR_COMPLETED, true)
    }

    private fun writeResponse(response: HttpServletResponse, cached: CachedResponse) {
        response.status = cached.status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write(cached.body)
    }
}

/**
 * @Idempotent 메서드의 성공 응답을 캡처하여 멱등 결과를 확정한다.
 * 선점한 요청(ATTR_OWNED_KEY 보유)만 완료 처리한다.
 */
@ControllerAdvice
class IdempotencyResponseAdvice(
    private val idempotencyInterceptor: IdempotencyInterceptor,
    private val objectMapper: ObjectMapper,
) : ResponseBodyAdvice<Any> {

    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>): Boolean {
        return returnType.hasMethodAnnotation(Idempotent::class.java)
    }

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        val servletRequest = (request as? ServletServerHttpRequest)?.servletRequest ?: return body
        // 선점한 요청만 완료 처리 (선점 실패한 요청은 controller까지 오지 않음)
        if (servletRequest.getAttribute(ATTR_OWNED_KEY) == null) return body

        if (body != null) {
            val responseBody = objectMapper.writeValueAsString(body)
            // 원래 응답의 실제 상태코드를 캡처해 캐시한다. @ResponseStatus(예: 201 CREATED)는
            // 메시지 변환(ResponseBodyAdvice) 시점 이전에 응답에 반영되므로 여기서 읽으면 정확하다.
            // 중복 재시도 시 200으로 회귀하지 않고 원래 상태코드(201 등)를 그대로 재반환한다.
            val status = (response as? ServletServerHttpResponse)?.servletResponse?.status
                ?.takeIf { it > 0 } ?: HttpStatus.OK.value()
            idempotencyInterceptor.markCompleted(servletRequest, responseBody, status)
        }
        return body
    }
}

@Component
class IdempotencyWebConfig(
    private val idempotencyInterceptor: IdempotencyInterceptor
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/**")
    }
}
