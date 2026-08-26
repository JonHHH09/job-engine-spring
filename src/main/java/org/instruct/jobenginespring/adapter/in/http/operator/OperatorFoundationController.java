package org.instruct.jobenginespring.adapter.in.http.operator;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
final class OperatorFoundationController {

    @GetMapping("/api/operator/v1/ping")
    Map<String, String> ping() {
        return Map.of("status", "ok");
    }

    @RequestMapping("/operator/")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void operatorPageFoundation() {
    }
}
