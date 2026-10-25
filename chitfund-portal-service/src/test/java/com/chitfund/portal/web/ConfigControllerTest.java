package com.chitfund.portal.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigControllerTest {

    @Test
    void theClientIdIsPassedThroughTrimmed() {
        assertEquals("abc.apps.googleusercontent.com", new ConfigController("  abc.apps.googleusercontent.com \n").config().googleClientId());
    }

    @Test
    void anUnsetClientIdIsAnEmptyStringNotNull() {
        assertEquals("", new ConfigController(null).config().googleClientId());
        assertEquals("", new ConfigController("").config().googleClientId());
    }
}
