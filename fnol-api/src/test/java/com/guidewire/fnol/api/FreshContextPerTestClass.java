package com.guidewire.fnol.api;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Every test class gets a fresh Spring context, with its own database (test application.yml) and duplicate guard. Classes
 * with the same mock beans would otherwise share one, and a loss date used by one class would be a duplicate in another,
 * so results depended on the order classes ran in. Registered in META-INF/spring.factories.
 */
public class FreshContextPerTestClass extends AbstractTestExecutionListener {

    @Override
    public void afterTestClass(TestContext testContext) {
        testContext.markApplicationContextDirty(null);
    }
}
