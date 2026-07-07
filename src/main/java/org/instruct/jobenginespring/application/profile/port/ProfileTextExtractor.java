package org.instruct.jobenginespring.application.profile.port;

import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;

/** Converts untrusted extracted document text into a conservative normalized profile write draft. */
public interface ProfileTextExtractor {

    ProfileWriteRequest extractProfile(ProfileTextExtractionInput input);

    record ProfileTextExtractionInput(String text, String sourceFileName) {
    }
}
