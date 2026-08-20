package com.hivemem.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Forwards the SPA root and every deep link to {@code /index.html}. The header is set
 * here — not left to the internal forward alone — because a servlet-container forward
 * (real Tomcat in production, but notably NOT MockMvc, which records but does not
 * execute {@code forward:} views) is the only thing that guarantees this controller's
 * response is what a browser actually sees; setting it directly makes the "no-cache"
 * contract independent of how the forward is followed downstream. The same value is
 * also set by {@link StaticResourceCacheConfig}'s resource handler for {@code
 * /index.html}, which real Tomcat still exercises via the forward this controller
 * triggers — both settings agree, so there is no conflict.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {"/", "/{path:[^.]*}", "/**/{path:[^.]*}"})
    public String forward(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        return "forward:/index.html";
    }
}
