package com.chitfund.portal.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the web app needs to know before anyone has signed in. The Google client id is public by design (it is sent to every browser that
 * shows the Google button), so exposing it here is fine. It is read at runtime so a change needs a restart, not a rebuild of the app.
 * Nothing secret ever goes in this response.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class ConfigController {

    public record PublicConfig(String googleClientId) {
    }

    private final String googleClientId;

    public ConfigController(@Value("${chit.portal.google.client-id:}") String googleClientId) {
        this.googleClientId = googleClientId == null ? "" : googleClientId.trim();
    }

    @GetMapping("/config")
    public PublicConfig config() {
        return new PublicConfig(googleClientId);
    }
}
