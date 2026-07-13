package org.instruct.jobenginespring.application.resume;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineGermanResumeTranslatorTests {

    private final OfflineGermanResumeTranslator translator = new OfflineGermanResumeTranslator();

    @Test
    void translatesKnownLabelsAndPhrasesOffline() {
        assertEquals("E-Mail", translator.translateLabel("Email"));
        assertEquals("Geburtsdatum", translator.translateLabel("Date of birth"));
        assertTrue(translator.translateText("Java Application Developer").toLowerCase().contains("java"));
        assertTrue(translator.translateText("Automated financial processes using Spring Boot and Hibernate in a regulated banking environment")
                .contains("automatisierte") || translator.translateText("Automated financial processes using Spring Boot and Hibernate in a regulated banking environment")
                .toLowerCase().contains("spring"));
    }

    @Test
    void preservesBlankValues() {
        assertEquals(null, translator.translateText(null));
        assertEquals("   ", translator.translateText("   "));
        assertEquals(null, translator.translateLabel(null));
    }
}
