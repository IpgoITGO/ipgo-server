package com.ipgo.server.service;

import com.ipgo.server.dto.response.ScenarioComparison;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.infrastructure.ScenarioEngineClient;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class CustomScenarioService {
    private final CustomScenarioStore store;
    private final ScenarioEngineClient engine;

    public CustomScenarioService(CustomScenarioStore store, ScenarioEngineClient engine) {
        this.store = store;
        this.engine = engine;
    }

    // Separate short transactions: HTTP never holds a DB connection or row lock.
    public ScenarioComparison.Scenario create(long userId, long resultId, long qty) {
        try {
            var input = store.prepare(userId, resultId, qty);
            if (input.cached() != null) return input.cached();
            var response = engine.calculate(resultId, input.qty(), input.request());
            return store.save(input, response);
        } catch (DataAccessException e) {
            // The store transaction has already rolled back, including the previous CUSTOM row.
            throw ProductException.scenarioStorageFailure();
        }
    }
}
