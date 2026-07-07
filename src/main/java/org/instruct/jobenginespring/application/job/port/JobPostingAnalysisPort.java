package org.instruct.jobenginespring.application.job.port;

import java.util.List;
import java.util.Map;

public interface JobPostingAnalysisPort {

    JobPostingAnalysisResponse analyze(JobPostingAnalysisRequest request);

    record JobPostingAnalysisRequest(Map<String, Object> inputJson) {
        public JobPostingAnalysisRequest {
            inputJson = inputJson == null ? Map.of() : Map.copyOf(inputJson);
        }
    }

    record JobPostingAnalysisResponse(
            String title,
            String company,
            String location,
            String description,
            List<String> skills,
            String experienceRequirement,
            String employmentType,
            String seniority,
            String postedDate,
            Double confidence,
            List<String> warnings
    ) {
        public JobPostingAnalysisResponse {
            skills = skills == null ? List.of() : List.copyOf(skills);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
