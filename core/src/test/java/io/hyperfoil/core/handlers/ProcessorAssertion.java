package io.hyperfoil.core.handlers;

import io.hyperfoil.api.connection.Request;
import io.hyperfoil.api.processor.Processor;
import io.hyperfoil.api.processor.RequestProcessorBuilder;
import io.netty.buffer.ByteBuf;
import io.vertx.ext.unit.TestContext;

public class ProcessorAssertion {
   private final int assertInvocations;
   private final boolean onlyLast;
   private int actualInvocations;

   public ProcessorAssertion(int assertInvocations, boolean onlyLast) {
      this.assertInvocations = assertInvocations;
      this.onlyLast = onlyLast;
   }

   public RequestProcessorBuilder processor(RequestProcessorBuilder delegate) {
      return new Builder(delegate);
   }

   public void runAssertions(TestContext ctx) {
      ctx.assertEquals(assertInvocations, actualInvocations);
      actualInvocations = 0;
   }

   private class Builder implements RequestProcessorBuilder {
      private final RequestProcessorBuilder delegate;

      private Builder(RequestProcessorBuilder delegate) {
         this.delegate = delegate;
      }

      @Override
      public Processor<Request> build(boolean fragmented) {
         return new Instance<>(delegate.build(fragmented));
      }
   }

   private class Instance<T extends Request> extends Processor.BaseDelegating<T> {
      protected Instance(Processor<T> delegate) {
         super(delegate);
      }

      @Override
      public void process(T request, ByteBuf data, int offset, int length, boolean isLastPart) {
         if (isLastPart || !onlyLast) {
            actualInvocations++;
         }
         delegate.process(request, data, offset, length, isLastPart);
      }
   }
}
