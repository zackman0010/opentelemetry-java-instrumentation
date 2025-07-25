/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jaxws.jws.v1_1;

import static io.opentelemetry.instrumentation.testing.junit.code.SemconvCodeStabilityUtil.codeFunctionAssertions;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class JwsAnnotationsTest {
  @RegisterExtension
  static InstrumentationExtension testing = AgentInstrumentationExtension.create();

  @Test
  @DisplayName("WebService on a class generates spans only for public methods")
  void classMethods() {
    new WebServiceClass().doSomethingPublic();
    new WebServiceClass().doSomethingPackagePrivate();
    new WebServiceClass().doSomethingProtected();

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("WebServiceClass.doSomethingPublic")
                        .hasNoParent()
                        .hasKind(SpanKind.INTERNAL)
                        .hasAttributesSatisfyingExactly(
                            codeFunctionAssertions(WebServiceClass.class, "doSomethingPublic"))));
  }

  @Test
  @DisplayName("WebService via interface generates spans only for methods of the interface")
  void interfaceMethods() {
    new WebServiceFromInterface().partOfPublicInterface();
    new WebServiceFromInterface().notPartOfPublicInterface();
    new WebServiceFromInterface().notPartOfAnything();

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("WebServiceFromInterface.partOfPublicInterface")
                        .hasNoParent()
                        .hasKind(SpanKind.INTERNAL)
                        .hasAttributesSatisfyingExactly(
                            codeFunctionAssertions(
                                WebServiceFromInterface.class, "partOfPublicInterface"))));
  }

  @Test
  @DisplayName("WebService via proxy must have span attributes from actual implementation")
  void proxy() {
    WebServiceDefinitionInterface proxy =
        (WebServiceDefinitionInterface)
            Proxy.newProxyInstance(
                WebServiceFromInterface.class.getClassLoader(),
                new Class<?>[] {WebServiceDefinitionInterface.class},
                new ProxyInvocationHandler(new WebServiceFromInterface()));
    proxy.partOfPublicInterface();

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("WebServiceFromInterface.partOfPublicInterface")
                        .hasNoParent()
                        .hasKind(SpanKind.INTERNAL)
                        .hasAttributesSatisfyingExactly(
                            codeFunctionAssertions(
                                WebServiceFromInterface.class, "partOfPublicInterface"))));
  }
}
