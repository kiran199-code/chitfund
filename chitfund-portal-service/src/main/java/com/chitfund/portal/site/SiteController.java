package com.chitfund.portal.site;

import com.chitfund.portal.site.SiteSettingsService.PublicSite;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The help line and office address (public, for the page footer) and the admin side that edits all the organiser's text. */
@RestController
@RequestMapping("/api/v1/portal")
public class SiteController {

    private final SiteSettingsService settings;

    public SiteController(SiteSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/site")
    public PublicSite site() {
        return settings.publicSite();
    }

    @GetMapping("/admin/settings")
    public Map<String, String> adminSettings() {
        return settings.all();
    }

    @PostMapping("/admin/settings")
    public Map<String, String> save(@RequestBody Map<String, String> body, @RequestHeader("X-Actor") String actor) {
        return settings.save(body, actor);
    }
}
