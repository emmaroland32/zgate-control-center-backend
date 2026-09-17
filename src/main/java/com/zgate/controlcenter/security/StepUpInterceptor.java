package com.zgate.controlcenter.security;

import com.zgate.controlcenter.service.StepUpTicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresStepUp}: the caller must present a recent, unused {@code X-StepUp-Ticket}
 * that was issued for this very action ({@code METHOD /path}).
 *
 * <p>Refuses with 403 and {@code code: STEP_UP_REQUIRED} so the console can tell this apart from an
 * authorisation failure and prompt for re-authentication instead of showing "forbidden". This runs
 * as an interceptor rather than a filter so it sees the resolved handler method and therefore the
 * annotation — and because by this point authentication has happened, so the 403 is not rewritten
 * into a 401 the way it would be in a pre-auth filter.
 *
 * <p>Interceptors run before method security, so the handler's {@code @PreAuthorize} is evaluated
 * here first: an operator whose role can never perform the action is left to method security's
 * 403 (which is audited) rather than being asked to re-authenticate for nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StepUpInterceptor implements HandlerInterceptor {

    private final StepUpTicketService tickets;
    private final MethodSecurityExpressionHandler expressions = new DefaultMethodSecurityExpressionHandler();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RequiresStepUp annotation = hm.getMethodAnnotation(RequiresStepUp.class);
        if (annotation == null) return true;

        Authentication auth = currentAuthentication();
        String username = principalName(auth);
        if (username == null) {
            write(response, 401, "UNAUTHORIZED", "Sign in to continue.");
            return false;
        }
        if (!roleAllows(hm, auth)) {
            return true; // method security will refuse (and audit) it — no step-up prompt
        }

        String action = actionOf(request);
        String ticket = request.getHeader("X-StepUp-Ticket");
        if (ticket == null || !tickets.verify(ticket, username, action, annotation.maxAgeSeconds())) {
            log.info("Step-up required for {} by {}", action, username);
            write(response, 403, "STEP_UP_REQUIRED",
                  "This action needs you to confirm your password first.");
            return false;
        }
        return true;
    }

    /** The action a ticket is bound to: the HTTP method and the request path, query excluded. */
    static String actionOf(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }

    /**
     * Evaluate the handler's {@code @PreAuthorize} (method, else class) for this principal. Any
     * shape this cannot evaluate is treated as allowed so method security stays the authority.
     */
    private boolean roleAllows(HandlerMethod hm, Authentication auth) {
        PreAuthorize pre = hm.getMethodAnnotation(PreAuthorize.class);
        if (pre == null) pre = hm.getBeanType().getAnnotation(PreAuthorize.class);
        if (pre == null) return true;
        try {
            MethodInvocation invocation = new SimpleMethodInvocation(hm.getBean(), hm.getMethod());
            EvaluationContext ctx = expressions.createEvaluationContext(auth, invocation);
            Expression expr = expressions.getExpressionParser().parseExpression(pre.value());
            return ExpressionUtils.evaluateAsBoolean(expr, ctx);
        } catch (RuntimeException e) {
            log.debug("Could not pre-evaluate @PreAuthorize '{}': {}", pre.value(), e.toString());
            return true;
        }
    }

    private static Authentication currentAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth;
    }

    private static String principalName(Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof UserDetails ud ? ud.getUsername() : auth.getName();
    }

    /** Hand-written so the body shape matches the API's error envelope without a mapper here. */
    private static void write(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
            "{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
