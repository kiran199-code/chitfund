package com.chitfund.portal.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The web app is one page that reads its own address. A direct visit to {@code /invite/<token>} (the link in the email) is not a file, so it
 * is answered with the app's index page and the app takes it from there. Everything else (assets, API) is untouched.
 */
@Controller
public class SpaController {

    @GetMapping({"/invite/{token}", "/signin"})
    public String app() {
        return "forward:/index.html";
    }
}
