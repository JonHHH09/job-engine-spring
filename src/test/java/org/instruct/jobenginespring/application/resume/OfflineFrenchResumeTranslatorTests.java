package org.instruct.jobenginespring.application.resume;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OfflineFrenchResumeTranslatorTests {

    private final OfflineFrenchResumeTranslator translator = new OfflineFrenchResumeTranslator();

    @Test
    void translatesCanadianResumeLabelsAndProfessionalContentOffline() {
        assertEquals("Coordonnées", translator.translateLabel("Contact"));
        assertEquals("Lieu", translator.translateLabel("Location"));
        assertEquals("Téléphone", translator.translateLabel("Phone"));
        assertEquals("Courriel", translator.translateLabel("Email"));
        assertEquals("Site Web", translator.translateLabel("Website"));
        assertEquals("Développe des systèmes natifs MCP.", translator.translateText("Builds MCP-native systems."));
        assertEquals("Développeur logiciel", translator.translateText("Software Developer"));
        assertEquals("Génie logiciel", translator.translateText("Software engineering"));
        assertEquals("Anglais", translator.translateText("English"));
        assertEquals("Courant", translator.translateLabel("Fluent"));
    }

    @Test
    void preservesOpaqueValuesTechnologyNamesAndUnknownProperNouns() {
        assertEquals("agentic@example.test", translator.translateOpaqueValue("agentic@example.test"));
        assertEquals("https://linkedin.com/in/agentic-dev", translator.translateOpaqueValue("https://linkedin.com/in/agentic-dev"));
        assertEquals("Java, Spring Boot, MCP", translator.translateTechnologyList("Java, Spring Boot, MCP"));
        assertEquals("Instruct", translator.translateText("Instruct"));
        assertEquals("Montréal, QC", translator.translateText("Montreal, QC"));
    }

    @Test
    void preservesEmailUrlAndSuppliedProperNameTechnologySpansInsideNarrativeText() {
        assertEquals(
                "Développé at developer@example.test using https://example.test/software/developer.",
                translator.translateText("Developed at developer@example.test using https://example.test/software/developer.")
        );
        assertEquals(
                "Développé Spring Boot et Developer Platform pour Instruct. Contact developer@example.test.",
                translator.translateText(
                        "Developed Spring Boot et Developer Platform pour Instruct. Contact developer@example.test.",
                        List.of("Spring Boot", "Developer Platform", "Instruct")
                )
        );
        assertEquals(
                "Software Developer",
                translator.translateText("Software Developer", List.of("Software Developer"))
        );
        assertEquals(
                "Builds MCP-native systems.",
                translator.translateText("Builds MCP-native systems.", List.of("MCP-native systems"))
        );
        assertEquals(
                "Développé ftp://example.test/?q=developer&x=1",
                translator.translateText("Developed ftp://example.test/?q=developer&x=1")
        );
        assertEquals(
                "Développé file:/tmp/developer",
                translator.translateText("Developed file:/tmp/developer")
        );
        assertEquals(
                "Développé developer@例え.テスト.",
                translator.translateText("Developed developer@例え.テスト.")
        );
        assertEquals("Développé", translator.translateText("Developed", null));
        assertEquals(
                "Développé Instruct",
                translator.translateText("Developed Instruct", Arrays.asList(null, " ", "Instruct", "Instruct"))
        );
    }

    @Test
    void translatesCommonExperienceBulletsIntoCompleteProfessionalFrench() {
        assertEquals(
                "Migré le site Web de l'entreprise de WordPress vers une architecture personnalisée maintenable et pris en charge la livraison jusqu'au déploiement.",
                translator.translateText("Migrated the company website from WordPress to a maintainable custom stack and took ownership of delivery through deployment.")
        );
        assertEquals(
                "Automatisé des processus financiers avec Spring Boot et Hibernate dans un environnement bancaire réglementé.",
                translator.translateText("Automated financial processes using Spring Boot and Hibernate in a regulated banking environment.")
        );
        assertEquals(
                "Mis en place une authentification basée sur JWT dans des systèmes de production.",
                translator.translateText("Implemented JWT-based authentication in production-facing systems.")
        );
        assertEquals(
                "Fourni du soutien aux utilisateurs pour les logiciels AutoCAD et Trimble GPS utilisés dans des travaux de cartographie.",
                translator.translateText("Provided end-user support for AutoCAD and Trimble GPS software used in mapping-related work.")
        );
    }

    @Test
    void translatesKnownProfessionalPhrasesBeforeRestoringProtectedTechnologyTerms() {
        assertEquals(
                "Développé une application de bureau pour l'entreprise avec Electron et Spring Boot, ainsi qu'un soutien mobile basé sur SwiftUI.",
                translator.translateText(
                        "Developed a desktop business application with Electron and Spring Boot plus SwiftUI-based mobile support.",
                        List.of("Electron", "Spring Boot", "SwiftUI")
                )
        );
        assertEquals(
                "Automatisé des processus financiers avec Spring Boot et Hibernate dans un environnement bancaire réglementé.",
                translator.translateText(
                        "Automated financial processes using Spring Boot and Hibernate in a regulated banking environment.",
                        List.of("Spring Boot", "Hibernate")
                )
        );
        assertEquals(
                "Développeur d'applications Java",
                translator.translateText("Java Application Developer", List.of("Java"))
        );
    }

    @Test
    void translatesLiveProfileSummaryEducationFocusCategoriesAndLocations() {
        assertEquals(
                "Ingénieur full-stack possédant une solide expertise en développement backend Java et une expérience concrète de la prise en charge de systèmes. Expérience couvrant les systèmes bancaires réglementés, la livraison autonome de produits de bout en bout et les environnements opérationnels multiplateformes sur le Web, les applications de bureau, le mobile et l'infonuagique. Profil particulièrement adapté aux postes valorisant une mise en oeuvre fiable, l'automatisation des processus et la livraison pratique en production.",
                translator.translateText("Full-stack engineer with strong Java backend depth and practical systems ownership. Experience spans regulated banking systems, freelance end-to-end product delivery, and cross-platform operational environments across web, desktop, mobile, and cloud. Strong fit for roles that value reliable implementation, workflow automation, and hands-on delivery in production settings.")
        );
        assertEquals(
                "génie logiciel, structures de données, architecture informatique, systèmes d'exploitation, sécurité de l'information et technologies Web",
                translator.translateText("software engineering, data structures, computer architecture, operating systems, information security, and web technologies")
        );
        assertEquals("Développement frontend / Applications", translator.translateLabel("Frontend / Apps"));
        assertEquals("Systèmes / Outils", translator.translateLabel("Systems / Tools"));
        assertEquals("Tirana, Albanie", translator.translateText("Tirana, Albania"));
        assertEquals("Blagoevgrad, Bulgarie", translator.translateText("Blagoevgrad, Bulgaria"));
    }

    @Test
    void handlesBlankAndUnknownTextWithoutInventingContent() {
        assertNull(translator.translateText(null));
        assertEquals("   ", translator.translateText("   "));
        assertNull(translator.translateLabel(null));
        assertEquals("   ", translator.translateLabel("   "));
        assertEquals("Unknown Product Name", translator.translateText("Unknown Product Name"));
        assertEquals("élaboré", translator.translateText("built"));
        assertEquals("Élaboré", translator.translateText("Built"));
        assertEquals("ÉLABORÉ", translator.translateText("BUILT"));
        assertEquals("mis en place", translator.translateText("implemented"));
        assertEquals("Je", translator.translateText("I"));
    }
}
