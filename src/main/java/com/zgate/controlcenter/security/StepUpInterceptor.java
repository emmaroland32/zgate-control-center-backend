package com.zgate.controlcenter.security;

import com.zgate.controlcenter.service.StepUpTicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresStepUp}: the caller must present a recent {@code X-StepUp-Ticket}.
 *
 * <p>Refuses with 403 and {@code code: STEP_UP_REQUIRED} so the console can tell this apart from an
 * authorisation failure and prompt for re-authentication instead of showing "forbidden". This runs
 * as an interceptor rather than a filter so it sees the resolved handler method and therefore the
 * annotation — and because by this point authentication has happened, so the 403 is not rewritten
 * into a 401 the way it would be in a pre-auth filter.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StepUpInterceptor implements HandlerInterceptor {

    private final StepUpTicketService tickets;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RequiresStepUp annotation = hm.getMethodAnnotation(RequiresStepUp.class);
        if (annotation == null) return true;

        String username = currentPrincipalName();
        if (username == null) {
            write(response, 401, "UNAUTHORIZED", "Sign in to continue.");
            return false;
        }

        String ticket = request.getHeader("X-StepUp-Ticket");
        if (ticket == null || !tickets.verify(ticket, username, annotation.maxAgeSeconds())) {
            log.info("Step-up required for {} {} by {}", request.getMethod(),
                     request.getRequestURI(), username);
            write(response, 403, "STEP_UP_REQUIRED",
                  "This action needs you to confirm your password first.");
            return false;
        }
        return true;
    }

    private static String currentPrincipalName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
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
