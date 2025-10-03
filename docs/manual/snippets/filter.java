package filters;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import io.vavr.control.Option;
import org.reactivecouchbase.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class OtoroshiFilter implements Filter {

    private static final long JWT_VALIDATION_LEEWAY = 5000L;

    private final String mode;

    private final String sharedKey;

    private final String requestIdHeaderName;
    private final String issuer;
    private final String claimHeaderName;
    private final String stateHeaderName;
    private final String stateRespHeaderName;

    private final Logger Logger = LoggerFactory.getLogger(OtoroshiFilter.class);

    public OtoroshiFilter(String mode, String sharedKey, String issuer, String requestIdHeaderName, String claimHeaderName, String stateHeaderName, String stateRespHeaderName) {
        this.mode = mode;
        this.sharedKey = sharedKey;
        this.requestIdHeaderName = requestIdHeaderName;
        this.issuer = issuer;
        this.claimHeaderName = claimHeaderName;
        this.stateHeaderName = stateHeaderName;
        this.stateRespHeaderName = stateRespHeaderName;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        Logger.info("Filtering request for " + request.getRequestURI());
        if (mode.equalsIgnoreCase("dev")) {
            response.setHeader(stateRespHeaderName, Option.of(request.getHeader(stateHeaderName)).getOrElse("--"));
            chain.doFilter(req, res);
            return;
        } else {
            try {
                Option.of(request.getHeader(requestIdHeaderName)).forEach(id -> Logger.info("Request from Otoroshi with id : " + id + " on " + request.getRequestURI()));
                Option<String> maybeState = Option.of(request.getHeader(stateHeaderName));
                Option<String> maybeClaim = Option.of(request.getHeader(claimHeaderName));
                if (maybeClaim.isEmpty() || maybeState.isEmpty()) {
                    response.setContentType("application/json");
                    response.sendError(400, Json.obj().with("error", "Bad request ...").stringify());
                    return;
                } else {
                    if (maybeClaim.isEmpty()) {
                        response.setContentType("application/json");
                        response.setHeader(stateRespHeaderName, maybeState.get());
                        response.sendError(400, Json.obj().with("error", "Bad Claim ...").stringify());
                        return;
                    } else {
                        try {
                            Algorithm algorithm = Algorithm.HMAC512(sharedKey);
                            JWTVerifier verifier = JWT.require(algorithm)
                                    .withIssuer(issuer)
                                    .acceptLeeway(JWT_VALIDATION_LEEWAY)
                                    .build();
                            verifier.verify(maybeClaim.get());
                        } catch (UnsupportedEncodingException exception) {
                            response.setContentType("application/json");
                            response.setHeader(stateRespHeaderName, maybeState.get());
                            response.sendError(400, Json.obj().with("error", "Bad Encoding ...").stringify());
                            return;
                        } catch (JWTVerificationException exception) {
                            Logger.error("Failed to verify token: " + maybeClaim.get());
                            Logger.error("Got exception: " + exception.getMessage(), exception);
                            response.setContentType("application/json");
                            response.setHeader(stateRespHeaderName, maybeState.get());
                            response.sendError(400, Json.obj().with("error", "Bad Signature ...").stringify());
                            return;
                        }
                        response.setHeader(stateRespHeaderName, maybeState.get());
                        chain.doFilter(req, res);
                        return;
                    }
                }
            } catch (Exception e) {
                Logger.error("Error decoding jwt token", e);
                response.setContentType("application/json");
                response.sendError(400, Json.obj().with("error", e.getMessage()).stringify());
                return;
            }
        }
    }
}