package org.instruct.jobenginespring.application.job.port;

public interface JobLinkContentFetcher {

    JobLinkFetchResult fetch(String url);

    record JobLinkFetchResult(
            String url,
            String title,
            String description,
            Integer httpStatus
    ) {
    }
}
