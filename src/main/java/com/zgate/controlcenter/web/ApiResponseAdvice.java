package com.zgate.controlcenter.web;

import com.zgate.controlcenter.exception.GlobalExceptionHandler.ErrorResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every JSON response from a Control Center controller in the uniform {@link ApiResponse}
 * envelope — centrally, so no controller has to build it. The frontend unwraps {@code data} and
 * surfaces {@code message} (see the axios response interceptor), which is how "all messages come
 * from the backend, the UI never hardcodes them" is enforced.
 *
 * <p>Scope + pass-throughs:
 * <ul>
 *   <li>Only JSON responses are wrapped (restricted to {@link MappingJackson2HttpMessageConverter}),
 *       so {@code byte[]}/CSV/PDF downloads and raw {@code String} bodies pass through untouched.</li>
 *   <li>Anything already an {@link ApiResponse} or an {@link ErrorResponse} is returned unchanged,
 *       so error envelopes and any hand-built envelope are never double-wrapped.</li>
 *   <li>A {@code X-Api-Envelope: 1} header is added so the client can detect wrapped responses
 *       unambiguously rather than guessing from body shape.</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.zgate.controlcenter.controller")
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> || body instanceof ErrorResponse) {
            return body;
        }
        // Machine-to-machine endpoints opt out — their body is parsed by non-browser clients that
        // don't unwrap the envelope (see @RawResponse).
        if (returnType.hasMethodAnnotation(RawResponse.class)) {
            return body;
        }

        String code = "OK";
        String message = null;
        ResponseMessage rm = returnType.getMethodAnnotation(ResponseMessage.class);
        if (rm != null) {
            code = rm.code();
            message = rm.value();
        }

        response.getHeaders().add("X-Api-Envelope", "1");
        return new ApiResponse<>(code, message, body);
    }
}
