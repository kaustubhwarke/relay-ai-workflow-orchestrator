package com.relay.service;

import com.relay.definition.DefinitionValidator;
import com.relay.domain.RelayException;
import java.util.List;
import org.springframework.http.HttpStatus;

/** A workflow definition failed validation (HTTP 422). The message names the first problem; details list all. */
public class InvalidDefinitionException extends RelayException {

    public InvalidDefinitionException(List<DefinitionValidator.Issue> issues) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, headCode(issues), headMessage(issues),
                issues == null ? List.of() : issues.stream().map(i -> i.code() + ": " + i.message()).toList());
    }

    private static String headCode(List<DefinitionValidator.Issue> issues) {
        return issues == null || issues.isEmpty() ? "invalid_definition" : issues.get(0).code();
    }

    private static String headMessage(List<DefinitionValidator.Issue> issues) {
        if (issues == null || issues.isEmpty()) return "The workflow definition is invalid";
        return issues.get(0).message() + (issues.size() > 1 ? " (and " + (issues.size() - 1) + " more problem(s))" : "");
    }
}
