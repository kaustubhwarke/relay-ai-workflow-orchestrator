package com.relay.service;

import com.relay.definition.DefinitionValidator;
import com.relay.domain.RelayException;
import java.util.List;
import org.springframework.http.HttpStatus;

/** A workflow definition failed validation (HTTP 422). The message names the first problem; details list all. */
public class InvalidDefinitionException extends RelayException {

    public InvalidDefinitionException(List<DefinitionValidator.Issue> issues) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                issues.get(0).code(),
                issues.get(0).message() + (issues.size() > 1 ? " (and " + (issues.size() - 1) + " more problem(s))" : ""),
                issues.stream().map(i -> i.code() + ": " + i.message()).toList());
    }
}
