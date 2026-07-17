package org.instruct.jobenginespring.application.coverletter.port;

import org.instruct.jobenginespring.domain.coverletter.CoverLetter;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterParagraph;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterVariant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoverLetterRepository {

    Optional<CoverLetter> findById(UUID coverLetterId);

    Optional<CoverLetter> findByProfileJobResume(UUID profileId, UUID jobId, UUID resumeId);

    List<CoverLetterVariant> findVariants(UUID coverLetterId);

    List<CoverLetterVariant> lockAndFindAllByProfileId(UUID profileId);

    List<CoverLetterVariant> lockAndFindAllByJobId(UUID jobId);

    ReplaceResult replace(CoverLetterAggregateWrite write);

    List<CoverLetterVariant> deleteByGermanyResumeIdentity(UUID profileId, UUID jobId);

    record CoverLetterAggregateWrite(CoverLetter coverLetter, VariantWrite variant) {
        public CoverLetterAggregateWrite {
            if (coverLetter == null || variant == null) {
                throw new NullPointerException("coverLetter and variant must not be null");
            }
        }
    }

    record VariantWrite(CoverLetterVariant variant, List<CoverLetterParagraph> paragraphs) {
        public VariantWrite {
            if (variant == null || paragraphs == null) {
                throw new NullPointerException("variant and paragraphs must not be null");
            }
            paragraphs = List.copyOf(paragraphs);
        }
    }

    record ReplaceResult(CoverLetter coverLetter, CoverLetterVariant variant, List<CoverLetterVariant> previousVariants) {
        public ReplaceResult {
            if (coverLetter == null || variant == null || previousVariants == null) {
                throw new NullPointerException("replace result values must not be null");
            }
            previousVariants = List.copyOf(previousVariants);
        }
    }
}
