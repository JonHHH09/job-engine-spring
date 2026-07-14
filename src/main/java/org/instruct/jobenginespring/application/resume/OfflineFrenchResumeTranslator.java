package org.instruct.jobenginespring.application.resume;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic offline EN-to-FR translator for the profile-backed Canadian resume variant.
 * Unknown words remain unchanged. Email/URL spans and caller-supplied proper-name or technology
 * spans are preserved before glossary translation; direct opaque values also bypass translation.
 */
@Component
public class OfflineFrenchResumeTranslator {

    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z][A-Za-z+./-]*");
    private static final String OPAQUE_SPAN_PATTERN =
            "(?:[A-Z][A-Z0-9+.-]*:|www\\.)[^\\s<>()]+|[^\\s@<>()]+@[^\\s@<>()]+";

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("contact", "Coordonnées"),
            Map.entry("links", "Liens"),
            Map.entry("email", "Courriel"),
            Map.entry("phone", "Téléphone"),
            Map.entry("location", "Lieu"),
            Map.entry("address", "Adresse"),
            Map.entry("website", "Site Web"),
            Map.entry("portfolio", "Portfolio"),
            Map.entry("github", "GitHub"),
            Map.entry("linkedin", "LinkedIn"),
            Map.entry("backend", "Développement backend"),
            Map.entry("frontend", "Développement frontend"),
            Map.entry("frontend / apps", "Développement frontend / Applications"),
            Map.entry("cloud / ops", "Infonuagique / Exploitation"),
            Map.entry("security", "Sécurité"),
            Map.entry("systems / tools", "Systèmes / Outils"),
            Map.entry("general", "Général"),
            Map.entry("fluent", "Courant"),
            Map.entry("native", "Langue maternelle"),
            Map.entry("professional", "Professionnel"),
            Map.entry("intermediate", "Intermédiaire"),
            Map.entry("beginner", "Débutant")
    );

    private static final List<Map.Entry<String, String>> PHRASES = List.of(
            Map.entry("Full-stack engineer with strong Java backend depth and practical systems ownership. Experience spans regulated banking systems, freelance end-to-end product delivery, and cross-platform operational environments across web, desktop, mobile, and cloud. Strong fit for roles that value reliable implementation, workflow automation, and hands-on delivery in production settings.",
                    "Ingénieur full-stack possédant une solide expertise en développement backend Java et une expérience concrète de la prise en charge de systèmes. Expérience couvrant les systèmes bancaires réglementés, la livraison autonome de produits de bout en bout et les environnements opérationnels multiplateformes sur le Web, les applications de bureau, le mobile et l'infonuagique. Profil particulièrement adapté aux postes valorisant une mise en oeuvre fiable, l'automatisation des processus et la livraison pratique en production."),
            Map.entry("software engineering, data structures, computer architecture, operating systems, information security, and web technologies",
                    "génie logiciel, structures de données, architecture informatique, systèmes d'exploitation, sécurité de l'information et technologies Web"),
            Map.entry("Migrated the company website from WordPress to a maintainable custom stack and took ownership of delivery through deployment.",
                    "Migré le site Web de l'entreprise de WordPress vers une architecture personnalisée maintenable et pris en charge la livraison jusqu'au déploiement."),
            Map.entry("Developed a desktop business application with Electron and Spring Boot plus SwiftUI-based mobile support.",
                    "Développé une application de bureau pour l'entreprise avec Electron et Spring Boot, ainsi qu'un soutien mobile basé sur SwiftUI."),
            Map.entry("Built internal business workflows for invoices, quotes, statements, and document generation.",
                    "Développé des processus internes pour les factures, les devis, les relevés et la génération de documents."),
            Map.entry("Integrated Supabase for PostgreSQL data, authentication, and real-time operational workflows.",
                    "Intégré Supabase pour les données PostgreSQL, l'authentification et les processus opérationnels en temps réel."),
            Map.entry("Managed cloud and deployment setup across Supabase, Vercel, and AWS.",
                    "Géré l'infrastructure infonuagique et le déploiement avec Supabase, Vercel et AWS."),
            Map.entry("Integrated Visa Direct APIs and collaborated with technical and business stakeholders on delivery.",
                    "Intégré les API Visa Direct et collaboré avec les parties prenantes techniques et commerciales pour la livraison."),
            Map.entry("Automated financial processes using Spring Boot and Hibernate in a regulated banking environment.",
                    "Automatisé des processus financiers avec Spring Boot et Hibernate dans un environnement bancaire réglementé."),
            Map.entry("Worked on systems handling data for more than 100,000 customers.",
                    "Travaillé sur des systèmes traitant les données de plus de 100 000 clients."),
            Map.entry("Implemented JWT-based authentication in production-facing systems.",
                    "Mis en place une authentification basée sur JWT dans des systèmes de production."),
            Map.entry("Supported deployment, configuration, and production issue diagnosis for card-management workflows.",
                    "Soutenu le déploiement, la configuration et le diagnostic d'incidents de production pour les processus de gestion des cartes."),
            Map.entry("Developed reusable frontend components and contributed to responsive website delivery across devices.",
                    "Développé des composants frontend réutilisables et contribué à la livraison de sites Web adaptatifs sur différents appareils."),
            Map.entry("Helped improve consistency and maintainability in user-facing implementation work.",
                    "Contribué à améliorer la cohérence et la maintenabilité des interfaces destinées aux utilisateurs."),
            Map.entry("Diagnosed and resolved software, hardware, and connectivity issues for remote users.",
                    "Diagnostiqué et résolu des problèmes logiciels, matériels et de connectivité pour des utilisateurs à distance."),
            Map.entry("Provided end-user support for AutoCAD and Trimble GPS software used in mapping-related work.",
                    "Fourni du soutien aux utilisateurs pour les logiciels AutoCAD et Trimble GPS utilisés dans des travaux de cartographie."),
            Map.entry("Freelance Full-Stack Developer", "Développeur full-stack autonome"),
            Map.entry("Full-Stack Developer", "Développeur full-stack"),
            Map.entry("Java Application Developer", "Développeur d'applications Java"),
            Map.entry("Front-End Developer", "Développeur frontend"),
            Map.entry("IT Support Technician", "Technicien en soutien informatique"),
            Map.entry("International degree program", "Programme d'études international"),
            Map.entry("Builds MCP-native systems.", "Développe des systèmes natifs MCP."),
            Map.entry("Software Developer", "Développeur logiciel"),
            Map.entry("Software engineering", "Génie logiciel"),
            Map.entry("Computer Science", "Informatique"),
            Map.entry("Built verification-first tools.", "Développé des outils axés sur la vérification."),
            Map.entry("Built MCP-native PDF generation tools.", "Développé des outils de génération PDF natifs MCP."),
            Map.entry("Added deterministic resume rendering tests.", "Ajouté des tests déterministes de rendu de CV."),
            Map.entry("Improved document provenance handling.", "Amélioré la gestion de la provenance des documents."),
            Map.entry("Preserved existing bullet text", "Conservé le texte existant des puces"),
            Map.entry("Normalized star-prefixed bullet text", "Normalisé le texte des puces précédées d'une étoile"),
            Map.entry("Normalized numbered bullet text", "Normalisé le texte des puces numérotées"),
            Map.entry("Current Role", "Poste actuel"),
            Map.entry("Past Role", "Poste précédent"),
            Map.entry("Undated Role", "Poste sans date"),
            Map.entry("English", "Anglais"),
            Map.entry("French", "Français"),
            Map.entry("German", "Allemand"),
            Map.entry("Albanian", "Albanais"),
            Map.entry("Montreal", "Montréal"),
            Map.entry("Albania", "Albanie"),
            Map.entry("Bulgaria", "Bulgarie"),
            Map.entry("Remote", "À distance")
    );

    private static final Map<String, String> WORD_GLOSSARY = new LinkedHashMap<>();

    static {
        WORD_GLOSSARY.put("built", "élaboré");
        WORD_GLOSSARY.put("build", "développer");
        WORD_GLOSSARY.put("developed", "développé");
        WORD_GLOSSARY.put("develop", "développer");
        WORD_GLOSSARY.put("implemented", "mis en place");
        WORD_GLOSSARY.put("integrated", "intégré");
        WORD_GLOSSARY.put("managed", "géré");
        WORD_GLOSSARY.put("supported", "soutenu");
        WORD_GLOSSARY.put("automated", "automatisé");
        WORD_GLOSSARY.put("improved", "amélioré");
        WORD_GLOSSARY.put("added", "ajouté");
        WORD_GLOSSARY.put("created", "créé");
        WORD_GLOSSARY.put("delivered", "livré");
        WORD_GLOSSARY.put("i", "je");
        WORD_GLOSSARY.put("systems", "systèmes");
        WORD_GLOSSARY.put("system", "système");
        WORD_GLOSSARY.put("application", "application");
        WORD_GLOSSARY.put("applications", "applications");
        WORD_GLOSSARY.put("software", "logiciel");
        WORD_GLOSSARY.put("developer", "développeur");
        WORD_GLOSSARY.put("engineering", "génie");
        WORD_GLOSSARY.put("education", "formation");
        WORD_GLOSSARY.put("languages", "langues");
        WORD_GLOSSARY.put("language", "langue");
        WORD_GLOSSARY.put("present", "présent");
        WORD_GLOSSARY.put("role", "poste");
        WORD_GLOSSARY.put("degree", "diplôme");
        WORD_GLOSSARY.put("institution", "établissement");
        WORD_GLOSSARY.put("montreal", "Montréal");
        WORD_GLOSSARY.put("canada", "Canada");
        WORD_GLOSSARY.put("english", "anglais");
        WORD_GLOSSARY.put("french", "français");
        WORD_GLOSSARY.put("german", "allemand");
        WORD_GLOSSARY.put("albanian", "albanais");
        WORD_GLOSSARY.put("remote", "à distance");
        WORD_GLOSSARY.put("fluent", "courant");
        WORD_GLOSSARY.put("professional", "professionnel");
        WORD_GLOSSARY.put("intermediate", "intermédiaire");
        WORD_GLOSSARY.put("beginner", "débutant");
    }

    public String translateLabel(String label) {
        if (label == null || label.isBlank()) {
            return label;
        }
        String key = label.strip().toLowerCase(Locale.ROOT);
        return LABELS.getOrDefault(key, translateText(label));
    }

    public String translateText(String text) {
        return translateText(text, List.of());
    }

    public String translateText(String text, Collection<String> protectedTerms) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String source = text.strip();
        Pattern protectedPattern = protectedSpanPattern(protectedTerms);
        Matcher protectedMatcher = protectedPattern.matcher(source);
        if (protectedMatcher.matches()) {
            return source;
        }
        for (Map.Entry<String, String> phrase : PHRASES) {
            if (source.equalsIgnoreCase(phrase.getKey())) {
                String translatedPhrase = preserveCase(source, phrase.getValue());
                protectedMatcher.reset();
                boolean preservesProtectedSpans = protectedMatcher.results()
                        .map(result -> result.group())
                        .allMatch(span -> containsIgnoreCase(translatedPhrase, span));
                if (preservesProtectedSpans) {
                    return translatedPhrase;
                }
            }
        }
        protectedMatcher.reset();

        StringBuilder translated = new StringBuilder(source.length());
        int cursor = 0;
        while (protectedMatcher.find()) {
            translated.append(translateUnprotected(source.substring(cursor, protectedMatcher.start())));
            translated.append(protectedMatcher.group());
            cursor = protectedMatcher.end();
        }
        translated.append(translateUnprotected(source.substring(cursor)));
        return translated.toString().strip();
    }

    private static String translateUnprotected(String text) {
        String translated = text;
        for (Map.Entry<String, String> phrase : PHRASES) {
            translated = replaceIgnoreCase(translated, phrase.getKey(), phrase.getValue());
        }

        Matcher matcher = WORD_PATTERN.matcher(translated);
        StringBuilder rebuilt = new StringBuilder();
        while (matcher.find()) {
            String word = matcher.group();
            String replacement = WORD_GLOSSARY.get(word.toLowerCase(Locale.ROOT));
            matcher.appendReplacement(
                    rebuilt,
                    Matcher.quoteReplacement(replacement == null ? word : preserveCase(word, replacement))
            );
        }
        matcher.appendTail(rebuilt);
        return rebuilt.toString();
    }

    public String translateOpaqueValue(String value) {
        return value;
    }

    public String translateTechnologyList(String technologies) {
        return technologies;
    }

    private static Pattern protectedSpanPattern(Collection<String> protectedTerms) {
        List<String> terms = new ArrayList<>();
        if (protectedTerms != null) {
            protectedTerms.stream()
                    .filter(term -> term != null && !term.isBlank())
                    .map(String::strip)
                    .distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .forEach(terms::add);
        }
        StringBuilder pattern = new StringBuilder("(?:").append(OPAQUE_SPAN_PATTERN);
        terms.forEach(term -> pattern.append('|').append(Pattern.quote(term)));
        pattern.append(')');
        return Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String replaceIgnoreCase(String source, String target, String replacement) {
        Pattern pattern = Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE);
        return pattern.matcher(source).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static boolean containsIgnoreCase(String source, String target) {
        return source.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT));
    }

    private static String preserveCase(String original, String replacement) {
        if (original.length() > 1 && original.equals(original.toUpperCase(Locale.ROOT))) {
            return replacement.toUpperCase(Locale.CANADA_FRENCH);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }
}
