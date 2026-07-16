package org.instruct.jobenginespring.application.document;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WinAnsiPdfTextTests {

    @Test
    void preservesWesternEuropeanTextAndReplacesUnsupportedCodePoints() {
        assertEquals("Jörg Müller ?", WinAnsiPdfText.sanitize("Jörg Müller 😀"));
    }

    @Test
    void normalizesPdfBoxUnsupportedControlCharactersToWhitespace() {
        assertEquals("Berlin Injected  Office", WinAnsiPdfText.sanitize("Berlin\nInjected\t\0Office"));
    }

    @Test
    void utilityConstructorIsCovered() throws Exception {
        Constructor<WinAnsiPdfText> constructor = WinAnsiPdfText.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
