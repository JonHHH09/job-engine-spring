package org.instruct.jobenginespring.application.resume;

import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline EN→DE translator for structured Lebenslauf content.
 * Uses embedded glossary + phrase replacements; no external network services.
 */
@Component
public class OfflineGermanResumeTranslator {

    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z][A-Za-z+/.-]*");

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("email", "E-Mail"),
            Map.entry("address", "Adresse"),
            Map.entry("phone", "Telefon"),
            Map.entry("location", "Wohnort"),
            Map.entry("github", "GitHub"),
            Map.entry("linkedin", "LinkedIn"),
            Map.entry("portfolio", "Portfolio"),
            Map.entry("date of birth", "Geburtsdatum"),
            Map.entry("nationality", "Staatsangehörigkeit"),
            Map.entry("field of study", "Studienfach"),
            Map.entry("technologies", "Technologien"),
            Map.entry("backend", "Backend"),
            Map.entry("frontend / apps", "Frontend / Apps"),
            Map.entry("cloud / ops", "Cloud / Betrieb"),
            Map.entry("security", "Sicherheit"),
            Map.entry("systems / tools", "Systeme / Tools"),
            Map.entry("technical", "Technisch"),
            Map.entry("general", "Allgemein"),
            Map.entry("fluent", "fließend"),
            Map.entry("beginner", "Anfänger"),
            Map.entry("intermediate", "Fortgeschritten"),
            Map.entry("native", "Muttersprache"),
            Map.entry("professional", "verhandlungssicher")
    );

    private static final List<Map.Entry<String, String>> PHRASES = List.of(
            Map.entry("full-stack developer", "Full-Stack-Entwickler"),
            Map.entry("freelance full-stack developer", "Freiberuflicher Full-Stack-Entwickler"),
            Map.entry("java application developer", "Java-Anwendungsentwickler"),
            Map.entry("front-end developer", "Frontend-Entwickler"),
            Map.entry("it support technician", "IT-Support-Techniker"),
            Map.entry("computer science", "Informatik"),
            Map.entry("b.a. computer science", "B.A. Informatik"),
            Map.entry("regulated banking environment", "reguliertem Bankenumfeld"),
            Map.entry("production-facing systems", "produktiven Systemen"),
            Map.entry("workflow automation", "Workflow-Automatisierung"),
            Map.entry("end-to-end product delivery", "End-to-End-Produktlieferung"),
            Map.entry("took ownership of delivery", "übernahm die Verantwortung für die Umsetzung"),
            Map.entry("took ownership", "übernahm die Verantwortung"),
            Map.entry("migrated the company website", "migrierte die Unternehmenswebsite"),
            Map.entry("built internal business workflows", "baute interne Geschäftsprozesse"),
            Map.entry("developed a desktop business application", "entwickelte eine Desktop-Geschäftsanwendung"),
            Map.entry("integrated supabase", "integrierte Supabase"),
            Map.entry("managed cloud and deployment setup", "verantwortete Cloud- und Deployment-Setup"),
            Map.entry("automated financial processes", "automatisierte Finanzprozesse"),
            Map.entry("implemented jwt-based authentication", "implementierte JWT-basierte Authentifizierung"),
            Map.entry("integrated visa direct apis", "integrierte Visa-Direct-APIs"),
            Map.entry("supported deployment, configuration, and production issue diagnosis",
                    "unterstützte Deployment, Konfiguration und Diagnose von Produktionsproblemen"),
            Map.entry("developed reusable frontend components", "entwickelte wiederverwendbare Frontend-Komponenten"),
            Map.entry("provided end-user support", "leistete Endanwender-Support"),
            Map.entry("diagnosed and resolved", "diagnostizierte und löste"),
            Map.entry("international degree program", "Internationales Studienprogramm"),
            Map.entry("field of study:", "Studienfach:"),
            Map.entry("technologies:", "Technologien:"),
            Map.entry("using spring boot and hibernate", "mit Spring Boot und Hibernate"),
            Map.entry("more than 100,000 customers", "mehr als 100.000 Kunden"),
            Map.entry("collaborated with technical and business stakeholders",
                    "arbeitete mit technischen und fachlichen Stakeholdern zusammen"),
            Map.entry("across web, desktop, mobile, and cloud", "über Web, Desktop, Mobile und Cloud"),
            Map.entry("software engineering, data structures, computer architecture, operating systems, information security, and web technologies",
                    "Software Engineering, Datenstrukturen, Rechnerarchitektur, Betriebssysteme, Informationssicherheit und Webtechnologien")
    );

    private static final Map<String, String> WORD_GLOSSARY = new LinkedHashMap<>();

    static {
        WORD_GLOSSARY.put("built", "erstellte");
        WORD_GLOSSARY.put("build", "erstellen");
        WORD_GLOSSARY.put("developed", "entwickelte");
        WORD_GLOSSARY.put("develop", "entwickeln");
        WORD_GLOSSARY.put("implemented", "implementierte");
        WORD_GLOSSARY.put("implement", "implementieren");
        WORD_GLOSSARY.put("integrated", "integrierte");
        WORD_GLOSSARY.put("managed", "verwaltete");
        WORD_GLOSSARY.put("supported", "unterstützte");
        WORD_GLOSSARY.put("automated", "automatisierte");
        WORD_GLOSSARY.put("migrated", "migrierte");
        WORD_GLOSSARY.put("created", "erstellte");
        WORD_GLOSSARY.put("delivered", "lieferte");
        WORD_GLOSSARY.put("worked", "arbeitete");
        WORD_GLOSSARY.put("using", "mit");
        WORD_GLOSSARY.put("with", "mit");
        WORD_GLOSSARY.put("for", "für");
        WORD_GLOSSARY.put("and", "und");
        WORD_GLOSSARY.put("or", "oder");
        WORD_GLOSSARY.put("in", "in");
        WORD_GLOSSARY.put("of", "von");
        WORD_GLOSSARY.put("the", "");
        WORD_GLOSSARY.put("a", "");
        WORD_GLOSSARY.put("an", "");
        WORD_GLOSSARY.put("to", "zu");
        WORD_GLOSSARY.put("from", "von");
        WORD_GLOSSARY.put("across", "über");
        WORD_GLOSSARY.put("through", "durch");
        WORD_GLOSSARY.put("systems", "Systeme");
        WORD_GLOSSARY.put("system", "System");
        WORD_GLOSSARY.put("application", "Anwendung");
        WORD_GLOSSARY.put("applications", "Anwendungen");
        WORD_GLOSSARY.put("workflow", "Workflow");
        WORD_GLOSSARY.put("workflows", "Workflows");
        WORD_GLOSSARY.put("data", "Daten");
        WORD_GLOSSARY.put("production", "Produktion");
        WORD_GLOSSARY.put("customer", "Kunde");
        WORD_GLOSSARY.put("customers", "Kunden");
        WORD_GLOSSARY.put("authentication", "Authentifizierung");
        WORD_GLOSSARY.put("deployment", "Deployment");
        WORD_GLOSSARY.put("configuration", "Konfiguration");
        WORD_GLOSSARY.put("issue", "Problem");
        WORD_GLOSSARY.put("issues", "Probleme");
        WORD_GLOSSARY.put("diagnosis", "Diagnose");
        WORD_GLOSSARY.put("support", "Support");
        WORD_GLOSSARY.put("software", "Software");
        WORD_GLOSSARY.put("hardware", "Hardware");
        WORD_GLOSSARY.put("remote", "remote");
        WORD_GLOSSARY.put("users", "Benutzer");
        WORD_GLOSSARY.put("user", "Benutzer");
        WORD_GLOSSARY.put("website", "Website");
        WORD_GLOSSARY.put("company", "Unternehmen");
        WORD_GLOSSARY.put("business", "Geschäfts");
        WORD_GLOSSARY.put("invoices", "Rechnungen");
        WORD_GLOSSARY.put("quotes", "Angebote");
        WORD_GLOSSARY.put("statements", "Abrechnungen");
        WORD_GLOSSARY.put("document", "Dokument");
        WORD_GLOSSARY.put("generation", "Erzeugung");
        WORD_GLOSSARY.put("desktop", "Desktop");
        WORD_GLOSSARY.put("mobile", "Mobile");
        WORD_GLOSSARY.put("cloud", "Cloud");
        WORD_GLOSSARY.put("bank", "Bank");
        WORD_GLOSSARY.put("banking", "Bankwesen");
        WORD_GLOSSARY.put("financial", "finanzielle");
        WORD_GLOSSARY.put("processes", "Prozesse");
        WORD_GLOSSARY.put("process", "Prozess");
        WORD_GLOSSARY.put("present", "heute");
        WORD_GLOSSARY.put("unknown", "unbekannt");
        WORD_GLOSSARY.put("role", "Rolle");
        WORD_GLOSSARY.put("project", "Projekt");
        WORD_GLOSSARY.put("degree", "Abschluss");
        WORD_GLOSSARY.put("institution", "Institution");
        WORD_GLOSSARY.put("montreal", "Montreal");
        WORD_GLOSSARY.put("canada", "Kanada");
        WORD_GLOSSARY.put("albania", "Albanien");
        WORD_GLOSSARY.put("tirana", "Tirana");
        WORD_GLOSSARY.put("bulgaria", "Bulgarien");
        WORD_GLOSSARY.put("english", "Englisch");
        WORD_GLOSSARY.put("albanian", "Albanisch");
        WORD_GLOSSARY.put("french", "Französisch");
        WORD_GLOSSARY.put("german", "Deutsch");
    }

    public StructuredResumeContent toGerman(StructuredResumeContent english) {
        StructuredResumeContent source = Objects.requireNonNull(english, "english must not be null");
        return new StructuredResumeContent(
                source.fullName(),
                ResumeVariant.LANGUAGE_DE,
                source.personalFields().stream()
                        .map(field -> new StructuredResumeContent.PersonalField(translateLabel(field.label()), translateText(field.value())))
                        .toList(),
                source.experiences().stream()
                        .map(entry -> new StructuredResumeContent.ExperienceEntry(
                                translateText(entry.title()),
                                translateText(entry.company()),
                                translateText(entry.location()),
                                entry.startDate(),
                                entry.endDate(),
                                entry.bullets().stream().map(this::translateText).toList()
                        ))
                        .toList(),
                source.education().stream()
                        .map(entry -> new StructuredResumeContent.EducationEntry(
                                translateText(entry.degree()),
                                translateText(entry.institution()),
                                translateText(entry.location()),
                                entry.startDate(),
                                entry.endDate(),
                                entry.bullets().stream().map(this::translateText).toList()
                        ))
                        .toList(),
                source.skillGroups().stream()
                        .map(group -> new StructuredResumeContent.SkillGroup(
                                translateLabel(group.category()),
                                group.skills() // keep technology names stable
                        ))
                        .toList(),
                source.languages().stream()
                        .map(language -> new StructuredResumeContent.LanguageEntry(
                                translateText(language.language()),
                                translateLabel(language.proficiency())
                        ))
                        .toList(),
                source.additional().stream()
                        .map(entry -> new StructuredResumeContent.AdditionalEntry(
                                translateText(entry.title()),
                                entry.organization(),
                                entry.bullets().stream().map(this::translateText).toList()
                        ))
                        .toList()
        );
    }

    String translateLabel(String label) {
        if (label == null || label.isBlank()) {
            return label;
        }
        String key = label.strip().toLowerCase(Locale.ROOT);
        if (LABELS.containsKey(key)) {
            return LABELS.get(key);
        }
        return translateText(label);
    }

    String translateText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String translated = text.strip();
        for (Map.Entry<String, String> phrase : PHRASES) {
            translated = replaceIgnoreCase(translated, phrase.getKey(), phrase.getValue());
        }
        Matcher matcher = WORD_PATTERN.matcher(translated);
        StringBuilder rebuilt = new StringBuilder();
        while (matcher.find()) {
            String word = matcher.group();
            String replacement = WORD_GLOSSARY.get(word.toLowerCase(Locale.ROOT));
            if (replacement == null) {
                matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(word));
            } else if (replacement.isEmpty()) {
                matcher.appendReplacement(rebuilt, "");
            } else {
                matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(preserveCase(word, replacement)));
            }
        }
        matcher.appendTail(rebuilt);
        return rebuilt.toString().replaceAll("\\s{2,}", " ").strip();
    }

    private static String replaceIgnoreCase(String source, String target, String replacement) {
        Pattern pattern = Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE);
        return pattern.matcher(source).replaceAll(Matcher.quoteReplacement(replacement));
    }

    static String preserveCase(String original, String replacement) {
        if (original.length() > 1 && original.equals(original.toUpperCase(Locale.ROOT))) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }

    static String preserveCaseForTest(String original, String replacement) {
        return preserveCase(original, replacement);
    }
}
