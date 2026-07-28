package org.instruct.jobenginespring.application.jobscan;

import java.util.List;

/** Fixed-source, read-only Arbeitnow board boundary. */
public interface ArbeitnowJobBoardPort {
    Page fetch(int page, Boolean visaSponsorship);

    record Page(List<UpstreamJob> jobs, boolean anotherPageMayRemain) {
        public Page { jobs = jobs == null ? List.of() : List.copyOf(jobs); }
    }

    record UpstreamJob(String slug, String company, String title, String htmlDescription, Boolean remote, String url,
                       List<String> tags, List<String> jobTypes, String location, Long createdAt) {
        public UpstreamJob {
            tags = tags == null ? List.of() : List.copyOf(tags);
            jobTypes = jobTypes == null ? List.of() : List.copyOf(jobTypes);
        }
    }
}
