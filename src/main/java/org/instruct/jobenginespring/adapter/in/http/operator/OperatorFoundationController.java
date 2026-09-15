package org.instruct.jobenginespring.adapter.in.http.operator;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serves the local operator console shell and its two static assets.
 *
 * <p>Assets are resolved from a fixed allow-list, never from the request path, so no
 * user-controlled value reaches the classpath lookup and path traversal is impossible
 * by construction. {@code OperatorSecurityFilter} has already enforced the enabled
 * flag plus the loopback host/peer and same-origin checks before any of this runs.
 */
@RestController
final class OperatorFoundationController {

    private static final String UI_ROOT = "operator-ui/";
    private static final MediaType TEXT_HTML = new MediaType("text", "html", StandardCharsets.UTF_8);

    /** The only assets that may ever be served, mapped to their content types. */
    private static final Map<String, MediaType> ASSETS = Map.of(
            "app.css", new MediaType("text", "css", StandardCharsets.UTF_8),
            "app.js", new MediaType("text", "javascript", StandardCharsets.UTF_8)
    );

    @GetMapping("/api/operator/v1/ping")
    Map<String, String> ping() {
        return Map.of("status", "ok");
    }

    @GetMapping("/operator/")
    ResponseEntity<Resource> operatorPage() {
        return asset("index.html", TEXT_HTML);
    }

    @GetMapping("/operator/{asset}")
    ResponseEntity<Resource> operatorAsset(@PathVariable String asset) {
        MediaType contentType = ASSETS.get(asset);
        if (contentType == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return asset(asset, contentType);
    }

    private ResponseEntity<Resource> asset(String name, MediaType contentType) {
        // Both files are packaged in the jar, so the resource always resolves; a missing
        // one is a build defect and surfaces as a 500 rather than a silent empty page.
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource(UI_ROOT + name));
    }
}
