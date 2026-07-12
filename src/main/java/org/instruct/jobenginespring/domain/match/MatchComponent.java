package org.instruct.jobenginespring.domain.match;

public enum MatchComponent {
    TECHNICAL(40), EXPERIENCE_SENIORITY(25), DOMAIN(15), DELIVERY(10), HARD_REQUIREMENTS(10);

    private final int availablePoints;

    MatchComponent(int availablePoints) { this.availablePoints = availablePoints; }

    public int availablePoints() { return availablePoints; }
}
