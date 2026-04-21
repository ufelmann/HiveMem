package com.hivemem.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Optional;

@Controller
public class LoginController {

    public static final String SESSION_TOKEN_KEY = "hivemem.token";

    private static final String LOGIN_HTML = """
            <!DOCTYPE html><html><head><meta charset="UTF-8"><style>
            *{margin:0;padding:0;box-sizing:border-box}
            body{background:#000;height:100vh;display:flex;align-items:center;justify-content:center}
            form{display:flex;flex-direction:column;align-items:center;gap:16px}
            input{background:transparent;border:none;border-bottom:1px solid #222;color:#fff;font-size:14px;letter-spacing:2px;outline:none;padding:8px 0;text-align:center;width:220px}
            input:focus{border-bottom-color:#444}
            button{background:transparent;border:none;color:#333;cursor:pointer;font-size:20px;line-height:1}
            button:hover{color:#666}
            @keyframes shake{0%,100%{transform:translateX(0)}20%,60%{transform:translateX(-8px)}40%,80%{transform:translateX(8px)}}
            .err{animation:shake .4s ease}
            </style></head><body>
            <form method="POST" action="/login">
            <input type="password" name="v" id="i" autocomplete="off" autofocus>
            <button type="submit">&#8594;</button>
            </form>
            <script>
            if(location.search.includes('error')){var i=document.getElementById('i');i.className='err';i.addEventListener('animationend',function(){i.className=''})}
            </script>
            </body></html>
            """;

    private final TokenService tokenService;
    private final LoginRateLimiter rateLimiter;

    public LoginController(TokenService tokenService, LoginRateLimiter rateLimiter) {
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/login")
    @ResponseBody
    public ResponseEntity<String> loginPage() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(LOGIN_HTML);
    }

    @PostMapping("/login")
    public Object handleLogin(
            @RequestParam("v") String token,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        String ip = request.getRemoteAddr();
        if (rateLimiter.isBlocked(ip)) {
            response.setStatus(429);
            return null;
        }
        Optional<AuthPrincipal> principal = tokenService.validateToken(token);
        if (principal.isEmpty()) {
            rateLimiter.recordFailure(ip);
            return "redirect:/login?error";
        }
        rateLimiter.clearFailures(ip);
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_TOKEN_KEY, token);
        return "redirect:/";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return "redirect:/login";
    }
}
