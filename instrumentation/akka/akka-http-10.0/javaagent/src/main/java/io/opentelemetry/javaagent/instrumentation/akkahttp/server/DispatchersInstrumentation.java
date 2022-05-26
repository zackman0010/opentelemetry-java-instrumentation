package io.opentelemetry.javaagent.instrumentation.akkahttp.server;

import akka.dispatch.MessageDispatcher;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

public class DispatchersInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("akka.dispatch.Dispatchers");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    // Instrument the Dispatchers to add a custom OTEL PinnedDispatcher
    // This is used to enforce a single thread per actor
    // This should allow ThreadLocals to function properly
    transformer.applyAdviceToMethod(
        named("lookup").and(takesArgument(0, named("java.lang.String"))),
        this.getClass().getName() + "$LookupAdvice");
    transformer.applyAdviceToMethod(
        named("hasDispatcher").and(takesArgument(0, named("java.lang.String"))),
        this.getClass().getName() + "$HasDispatcherAdvice");
  }

  @SuppressWarnings("unused")
  public static class LookupAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static MessageDispatcher wrapHandler(@Advice.Argument(value = 0) String id) {
      if (AkkaHttpServerSingletons.OTEL_DISPATCHER_NAME.equals(id)) {
        //TODO - Create a Pinned Dispatcher here somehow
      }
      return null;
    }
  }

  @SuppressWarnings("unused")
  public static class HasDispatcherAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static boolean wrapHandler(@Advice.Argument(value = 0) String id) {
      return AkkaHttpServerSingletons.OTEL_DISPATCHER_NAME.equals(id);
    }
  }
}
