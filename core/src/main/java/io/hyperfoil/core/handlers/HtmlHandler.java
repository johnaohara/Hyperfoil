package io.hyperfoil.core.handlers;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import org.kohsuke.MetaInfServices;

import io.hyperfoil.api.config.BenchmarkDefinitionException;
import io.hyperfoil.api.config.BuilderBase;
import io.hyperfoil.api.config.Locator;
import io.hyperfoil.api.config.Name;
import io.hyperfoil.api.config.SequenceBuilder;
import io.hyperfoil.api.config.Step;
import io.hyperfoil.api.config.StepBuilder;
import io.hyperfoil.api.connection.HttpRequest;
import io.hyperfoil.api.processor.HttpRequestProcessorBuilder;
import io.hyperfoil.api.connection.Request;
import io.hyperfoil.api.http.HttpMethod;
import io.hyperfoil.api.processor.Processor;
import io.hyperfoil.api.session.Access;
import io.hyperfoil.api.session.Action;
import io.hyperfoil.api.session.Session;
import io.hyperfoil.api.session.ResourceUtilizer;
import io.hyperfoil.core.data.DataFormat;
import io.hyperfoil.core.generators.StringGeneratorImplBuilder;
import io.hyperfoil.core.session.SessionFactory;
import io.hyperfoil.core.steps.AddToIntAction;
import io.hyperfoil.core.steps.AwaitIntStep;
import io.hyperfoil.core.steps.HttpRequestStep;
import io.hyperfoil.core.steps.PathMetricSelector;
import io.hyperfoil.core.builders.ServiceLoadedBuilderProvider;
import io.hyperfoil.core.steps.UnsetAction;
import io.hyperfoil.core.util.Trie;
import io.hyperfoil.core.util.Util;
import io.hyperfoil.function.SerializableBiFunction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public class HtmlHandler implements Processor<HttpRequest>, ResourceUtilizer, Session.ResourceKey<HtmlHandler.Context> {
   private static final Logger log = LoggerFactory.getLogger(HtmlHandler.class);
   private static final boolean trace = log.isTraceEnabled();

   private final TagHandler[] handlers;

   private HtmlHandler(TagHandler... handlers) {
      this.handlers = handlers;
   }

   @Override
   public void before(HttpRequest request) {
      for (TagHandler h : handlers) {
         h.processor().before(request);
      }
   }

   @Override
   public void after(HttpRequest request) {
      for (TagHandler h : handlers) {
         h.processor().after(request);
      }
   }

   @Override
   public void process(HttpRequest request, ByteBuf data, int offset, int length, boolean isLastPart) {
      Context ctx = request.session.getResource(this);
      switch (ctx.tagStatus) {
         case PARSING_TAG:
            ctx.tagStart = offset;
            break;
         case PARSING_ATTR:
            ctx.attrStart = offset;
            break;
         case PARSING_VALUE:
            ctx.valueStart = offset;
            break;
      }
      while (length > 0) {
         byte c = data.getByte(offset++);
         --length;
         switch (ctx.tagStatus) {
            case NO_TAG:
               if (c == '<') {
                  ctx.tagStatus = TagStatus.ENTERED;
               }
               break;
            case ENTERED:
               if (c == '!') {
                  ctx.tagStatus = TagStatus.DOCTYPE_START;
               } else if (Character.isWhitespace(c)) {
                  ctx.tagStatus = TagStatus.BEFORE_TAG;
               } else if (c == '/') {
                  ctx.tagClosing = true;
                  ctx.tagStatus = TagStatus.BEFORE_TAG;
               } else {
                  ctx.tagStart = offset - 1;
                  ctx.tagStatus = TagStatus.PARSING_TAG;
               }
               break;
            case DOCTYPE_START:
               if (c == '-') {
                  ctx.comment = 3;
                  ctx.tagStatus = TagStatus.COMMENT;
               } else {
                  ctx.tagStatus = TagStatus.DOCTYPE;
               }
               break;
            case DOCTYPE:
               if (c == '>') {
                  ctx.endTag(request);
               }
               break;
            case COMMENT:
               if (ctx.comment == 1) {
                  if (c == '>') {
                     ctx.comment = 0;
                     ctx.tagStatus = TagStatus.NO_TAG;
                  } else if (c != '-') {
                     ctx.comment = 3;
                  }
               } else if (ctx.comment > 0) {
                  if (c == '-') {
                     ctx.comment--;
                  }
               }
               break;
            case BEFORE_TAG:
               if (!Character.isWhitespace(c)) {
                  ctx.tagStatus = TagStatus.PARSING_TAG;
                  ctx.tagStart = offset - 1;
               }
               break;
            case PARSING_TAG:
               if (Character.isWhitespace(c)) {
                  ctx.onTag(request, ctx.tagClosing, data, offset - 1, true);
                  ctx.tagStatus = TagStatus.BEFORE_ATTR;
               } else if (c == '>') {
                  ctx.endTag(request);
               }
               break;
            case BEFORE_ATTR:
               if (c == '>') {
                  ctx.endTag(request);
               } else if (!Character.isWhitespace(c)) {
                  ctx.attrStart = offset - 1;
                  ctx.tagStatus = TagStatus.PARSING_ATTR;
               }
               break;
            case PARSING_ATTR:
               if (c == '=' || Character.isWhitespace(c)) {
                  ctx.onAttr(request, data, offset - 1, true);
                  ctx.tagStatus = TagStatus.BEFORE_VALUE;
               } else if (c == '>') {
                  ctx.onAttr(request, data, offset - 1, true);
                  ctx.endTag(request);
               }
               break;
            case BEFORE_VALUE:
               if (c == '>') {
                  ctx.endTag(request);
               } else if (c == '=' || Character.isWhitespace(c)) {
                  // ignore, there was a whitespace
                  break;
               } else if (c == '"') {
                  ctx.tagStatus = TagStatus.PARSING_VALUE;
                  ctx.valueStart = offset;
                  ctx.valueQuoted = true;
               } else {
                  // missing quotes
                  ctx.tagStatus = TagStatus.PARSING_VALUE;
                  ctx.valueStart = offset - 1;
               }
               break;
            case PARSING_VALUE:
               if (c == '\\') {
                  ctx.charEscaped = true;
               } else if (c == '"' && !ctx.charEscaped) {
                  ctx.onValue(request, data, offset - 1, true);
                  ctx.tagStatus = TagStatus.BEFORE_ATTR;
                  ctx.valueQuoted = false;
               } else if (!ctx.valueQuoted && Character.isWhitespace(c)) {
                  ctx.onValue(request, data, offset - 1, true);
                  ctx.tagStatus = TagStatus.BEFORE_ATTR;
               } else {
                  ctx.charEscaped = false;
               }
               break;
            default:
               throw new IllegalStateException();
         }
      }
      switch (ctx.tagStatus) {
         case PARSING_TAG:
            ctx.onTag(request, ctx.tagClosing, data, offset - 1, false);
            break;
         case PARSING_ATTR:
            ctx.onAttr(request, data, offset - 1, false);
            break;
         case PARSING_VALUE:
            ctx.onValue(request, data, offset - 1, false);
            break;
      }
   }

   @Override
   public void reserve(Session session) {
      session.declareResource(this, new Context());
      ResourceUtilizer.reserve(session, (Object[]) handlers);
   }

   interface TagHandler {
      Processor<HttpRequest> processor();

      HandlerContext newContext();
   }

   enum TagStatus {
      NO_TAG,
      ENTERED,
      BEFORE_TAG,
      PARSING_TAG,
      BEFORE_ATTR,
      PARSING_ATTR,
      DOCTYPE_START, // doctype, comment
      DOCTYPE,
      BEFORE_VALUE, PARSING_VALUE, COMMENT
   }

   class Context implements Session.Resource {
      TagStatus tagStatus = TagStatus.NO_TAG;
      boolean valueQuoted;
      boolean charEscaped;
      boolean tagClosing;
      int tagStart = -1;
      int attrStart = -1;
      int valueStart = -1;
      int comment;
      HandlerContext[] handlerCtx;

      Context() {
         handlerCtx = Stream.of(handlers).map(TagHandler::newContext).toArray(HandlerContext[]::new);
      }

      void onTag(HttpRequest request, boolean close, ByteBuf data, int tagEnd, boolean isLast) {
         assert tagStart >= 0;
         for (HandlerContext handlerCtx : handlerCtx) {
            handlerCtx.onTag(request, close, data, tagStart, tagEnd - tagStart, isLast);
         }
         tagStart = -1;
      }

      void onAttr(HttpRequest request, ByteBuf data, int attrEnd, boolean isLast) {
         assert attrStart >= 0;
         for (HandlerContext handlerCtx : handlerCtx) {
            handlerCtx.onAttr(request, data, attrStart, attrEnd - attrStart, isLast);
         }
         attrStart = -1;
      }

      void onValue(HttpRequest request, ByteBuf data, int valueEnd, boolean isLast) {
         assert valueStart >= 0;
         for (HandlerContext handlerCtx : handlerCtx) {
            handlerCtx.onValue(request, data, valueStart, valueEnd - valueStart, isLast);
         }
         valueStart = -1;
      }

      // TODO: content handling

      private void endTag(HttpRequest request) {
         tagStatus = TagStatus.NO_TAG;
         tagClosing = false;
         for (int i = 0; i < handlerCtx.length; ++i) {
            handlerCtx[i].endTag(request);
         }
      }
   }

   interface HandlerContext {
      void onTag(HttpRequest request, boolean close, ByteBuf data, int offset, int length, boolean isLast);

      void onAttr(HttpRequest request, ByteBuf data, int offset, int length, boolean isLast);

      void onValue(HttpRequest request, ByteBuf data, int offset, int length, boolean isLast);

      void endTag(HttpRequest request);
   }

   /**
    * Parses HTML tags and invokes handlers based on criteria.
    */
   @MetaInfServices(HttpRequestProcessorBuilder.class)
   @Name("parseHtml")
   public static class Builder implements HttpRequestProcessorBuilder {
      private Locator locator;
      private EmbeddedResourceHandlerBuilder embeddedResourceHandler;

      @Override
      public Builder setLocator(Locator locator) {
         this.locator = locator;
         return this;
      }

      /**
       * Handler firing upon reference to other resource, e.g. image, stylesheet...
       *
       * @return Builder.
       */
      public EmbeddedResourceHandlerBuilder onEmbeddedResource() {
         if (embeddedResourceHandler != null) {
            throw new BenchmarkDefinitionException("Embedded resource handler already set!");
         }
         return embeddedResourceHandler = new EmbeddedResourceHandlerBuilder().setLocator(locator);
      }

      @Override
      public void prepareBuild() {
         embeddedResourceHandler.prepareBuild();
      }

      @Override
      public Builder copy(Locator locator) {
         Builder newBuilder = new Builder().setLocator(locator);
         newBuilder.embeddedResourceHandler = embeddedResourceHandler.copy(locator);
         return newBuilder;
      }

      @Override
      public HtmlHandler build(boolean fragmented) {
         return new HtmlHandler(embeddedResourceHandler.build());
      }
   }

   /**
    * Handles <code>&lt;img src="..."&gt;</code>, <code>&lt;link href="..."&gt;</code>,
    * <code>&lt;embed src="..."&gt;</code>, <code>&lt;frame src="..."&gt;</code>,
    * <code>&lt;iframe src="..."&gt;</code>, <code>&lt;object data="..."&gt;</code> and <code>&lt;script src="..."&gt;</code>.
    * <p>
    * Does not handle <code>&lt;source src="..."&gt;</code> or <code>&lt;track src="..."&gt;</code> because browser
    * would choose only one of the options.
    */
   public static class EmbeddedResourceHandlerBuilder implements BuilderBase<EmbeddedResourceHandlerBuilder> {
      private static final String[] TAGS = { "img", "link", "embed", "frame", "iframe", "object", "script" };
      private static final String[] ATTRS = { "src", "href", "src", "src", "src", "data", "src" };

      private Locator locator;
      private boolean ignoreExternal = true;
      private Processor.Builder<HttpRequest, ?> processor;
      private FetchResourceBuilder fetchResource;

      @Override
      public EmbeddedResourceHandlerBuilder setLocator(Locator locator) {
         this.locator = locator;
         return this;
      }

      /**
       * Ignore resources hosted on servers that are not covered in the <code>http</code> section.
       *
       * @param ignoreExternal Ignore?
       * @return Self.
       */
      public EmbeddedResourceHandlerBuilder ignoreExternal(boolean ignoreExternal) {
         this.ignoreExternal = ignoreExternal;
         return this;
      }

      /**
       * Automatically download referenced resource.
       *
       * @return Builder.
       */
      public FetchResourceBuilder fetchResource() {
         return this.fetchResource = new FetchResourceBuilder(locator);
      }

      public EmbeddedResourceHandlerBuilder processor(Processor.Builder<HttpRequest, ?> processor) {
         if (this.processor == null) {
            this.processor = processor;
         } else if (this.processor instanceof MultiProcessor.Builder) {
            @SuppressWarnings("unchecked")
            MultiProcessor.Builder<HttpRequest> multiprocessor = (MultiProcessor.Builder<HttpRequest>) this.processor;
            multiprocessor.add(processor);
         } else {
            this.processor = new MultiProcessor.Builder<HttpRequest>().add(this.processor).add(processor);
         }
         return this;
      }

      /**
       * Custom processor invoked pointing to attribute data - e.g. in case of <code>&lt;img&gt;</code> tag
       * the processor gets contents of the <code>src</code> attribute.
       *
       * @return Builder.
       */
      public ServiceLoadedBuilderProvider<HttpRequestProcessorBuilder> processor() {
         return new ServiceLoadedBuilderProvider<>(HttpRequestProcessorBuilder.class, locator, this::processor);
      }

      public void prepareBuild() {
         if (processor != null) {
            processor.prepareBuild();
         }
         if (fetchResource != null) {
            fetchResource.prepareBuild();
         }
      }

      @Override
      public EmbeddedResourceHandlerBuilder copy(Locator locator) {
         EmbeddedResourceHandlerBuilder builder = new EmbeddedResourceHandlerBuilder().setLocator(locator);
         builder.ignoreExternal(ignoreExternal);
         if (processor != null) {
            builder.processor(processor.copy(locator));
         }
         if (fetchResource != null) {
            builder.fetchResource = fetchResource.copy(locator);
         }
         return builder;
      }

      public BaseTagAttributeHandler build() {
         if (processor != null && fetchResource != null) {
            throw new BenchmarkDefinitionException("Only one of processor/fetchResource allowed!");
         }
         Processor<HttpRequest> p;
         if (fetchResource != null) {
            p = fetchResource.build();
         } else if (processor != null) {
            p = processor.build(false);
         } else {
            throw new BenchmarkDefinitionException("Embedded resource handler is missing the processor");
         }
         return new BaseTagAttributeHandler(TAGS, ATTRS, new EmbeddedResourceProcessor(ignoreExternal, p));
      }
   }

   /**
    * Automates download of embedded resources.
    */
   public static class FetchResourceBuilder implements BuilderBase<FetchResourceBuilder> {
      private final Locator locator;
      private final String generatedSeqName;

      private int maxResources;
      private SerializableBiFunction<String, String, String> metricSelector;
      private Action.Builder onCompletion;

      FetchResourceBuilder(Locator locator) {
         this.locator = locator;
         this.generatedSeqName = String.format("%s_fetchResources_%08x",
               locator.sequence().name(), ThreadLocalRandom.current().nextInt());
      }

      private String completionLatch() {
         return generatedSeqName + "_latch";
      }

      private String downloadUrlVar() {
         return generatedSeqName + "_url";
      }

      /**
       * Maximum number of resources that can be fetched.
       *
       * @param maxResources Max resources.
       * @return Self.
       */
      public FetchResourceBuilder maxResources(int maxResources) {
         this.maxResources = maxResources;
         return this;
      }

      /**
       * Metrics selector for downloaded resources.
       *
       * @return Builder.
       */
      public PathMetricSelector metric() {
         PathMetricSelector metricSelector = new PathMetricSelector();
         metric(metricSelector);
         return metricSelector;
      }

      public FetchResourceBuilder metric(SerializableBiFunction<String, String, String> metricSelector) {
         if (this.metricSelector != null) {
            throw new BenchmarkDefinitionException("Metric already set!");
         }
         this.metricSelector = metricSelector;
         return this;
      }

      /**
       * Action performed when the download of all resources completes.
       *
       * @return Builder.
       */
      public ServiceLoadedBuilderProvider<Action.Builder> onCompletion() {
         return new ServiceLoadedBuilderProvider<>(Action.Builder.class, locator, this::onCompletion);
      }

      public FetchResourceBuilder onCompletion(Action.Builder a) {
         if (onCompletion != null) {
            throw new BenchmarkDefinitionException("Completion action already set!");
         }
         onCompletion = a;
         return this;
      }

      public void prepareBuild() {
         if (maxResources <= 0) {
            throw new BenchmarkDefinitionException("maxResources is missing or invalid.");
         }

         SequenceBuilder sequence = locator.scenario().sequence(generatedSeqName);

         HttpRequestStep.Builder requestBuilder = new HttpRequestStep.Builder().sync(false).method(HttpMethod.GET);
         requestBuilder.path(
               new StringGeneratorImplBuilder<>(requestBuilder, false).fromVar(downloadUrlVar() + "[.]"));
         if (metricSelector != null) {
            requestBuilder.metric(metricSelector);
         } else {
            // Rather than using auto-generated sequence name we'll use the full path
            requestBuilder.metric((authority, path) -> authority != null ? authority + path : path);
         }
         requestBuilder.handler().onCompletion(new AddToIntAction.Builder().var(completionLatch()).value(-1));
         requestBuilder.setLocator(sequence.createLocator());
         sequence.stepBuilder(requestBuilder);
         requestBuilder.prepareBuild();

         Action onCompletion = this.onCompletion.build();
         // We add unset step for cases where the step is retried and it's not sync
         locator.sequence().insertAfter(locator)
               .step(new AwaitIntStep(completionLatch(), x -> x == 0))
               .step(new StepBuilder.ActionStep(new UnsetAction(completionLatch())))
               .step(new ResourceUtilizingStep(onCompletion));
      }

      @Override
      public FetchResourceBuilder copy(Locator locator) {
         return new FetchResourceBuilder(locator)
               .maxResources(maxResources)
               .metric(metricSelector)
               .onCompletion(onCompletion);
      }

      @SuppressWarnings("unchecked")
      public FetchResourcesAdapter build() {
         return new FetchResourcesAdapter(completionLatch(), new MultiProcessor<>(
               new ArrayRecorder(downloadUrlVar(), DataFormat.STRING, maxResources),
               new NewSequenceProcessor(maxResources, generatedSeqName + "_cnt", generatedSeqName)));
      }
   }

   private static class ResourceUtilizingStep implements Step, ResourceUtilizer {
      private final Action action;

      public ResourceUtilizingStep(Action action) {
         this.action = action;
      }

      @Override
      public boolean invoke(Session session) {
         action.run(session);
         return true;
      }

      @Override
      public void reserve(Session session) {
         ResourceUtilizer.reserve(session, action);
      }
   }

   private static class FetchResourcesAdapter implements Processor<HttpRequest>, ResourceUtilizer {
      private final Access completionCounter;
      private final Processor<Request> delegate;

      private FetchResourcesAdapter(String completionCounter, Processor<Request> delegate) {
         this.completionCounter = SessionFactory.access(completionCounter);
         this.delegate = delegate;
      }

      @Override
      public void before(HttpRequest request) {
         completionCounter.setInt(request.session, 1);
         delegate.before(request);
      }

      @Override
      public void process(HttpRequest request, ByteBuf data, int offset, int length, boolean isLastPart) {
         completionCounter.addToInt(request.session, 1);
         delegate.process(request, data, offset, length, isLastPart);
      }

      @Override
      public void after(HttpRequest request) {
         completionCounter.addToInt(request.session, -1);
         delegate.after(request);
      }

      @Override
      public void reserve(Session session) {
         completionCounter.declareInt(session);
         ResourceUtilizer.reserve(session, delegate);
      }
   }

   private static class BaseTagAttributeHandler implements TagHandler, ResourceUtilizer {
      private final Trie trie;
      private final byte[][] attributes;
      private final Processor<HttpRequest> processor;

      BaseTagAttributeHandler(String[] tags, String[] attributes, Processor<HttpRequest> processor) {
         this.processor = processor;
         if (tags.length != attributes.length) {
            throw new IllegalArgumentException();
         }
         this.trie = new Trie(tags);
         this.attributes = Stream.of(attributes)
               .map(s -> s.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
      }

      @Override
      public Processor<HttpRequest> processor() {
         return processor;
      }

      @Override
      public HandlerContext newContext() {
         return new Ctx();
      }

      @Override
      public void reserve(Session session) {
         ResourceUtilizer.reserve(session, processor);
      }

      protected class Ctx implements HandlerContext {
         private final Trie.State trieState = trie.newState();
         private int tagMatched = -1;
         private int attrMatchedIndex = -1;
         private ByteBuf valueBuffer = ByteBufAllocator.DEFAULT.buffer();

         @Override
         public void onTag(HttpRequest request, boolean close, ByteBuf data, int offset, int length, boolean isLast) {
            for (int i = 0; i < length; ++i) {
               int terminal = trieState.next(data.getByte(offset + i));
               if (isLast && terminal >= 0) {
                  tagMatched = terminal;
                  attrMatchedIndex = 0;
               }
            }
         }

         @Override
         public void onAttr(HttpRequest request, ByteBuf data, int offset, int length, boolean isLast) {
            if (tagMatched < 0) {
               return;
            }
            if (attrMatchedIndex >= 0) {
               for (int i = 0; i < length; ++i) {
                  if (attrMatchedIndex >= attributes[tagMatched].length) {
                     attrMatchedIndex = -1;
                     break;
                  } else if (attributes[tagMatched][attrMatchedIndex] == data.getByte(offset + i)) {
                     attrMatchedIndex++;
                  } else {
                     attrMatchedIndex = -1;
                     break;
                  }
               }
            }
            if (isLast) {
               if (attrMatchedIndex != attributes[tagMatched].length) {
                  attrMatchedIndex = 0;
               } // otherwise keep matched positive for value
            }
         }

         @Override
         public void onValue(HttpRequest request, ByteBuf data, int offset, int length, boolean isLast) {
            if (tagMatched < 0 || attrMatchedIndex <= 0) {
               return;
            }
            valueBuffer.ensureWritable(length);
            valueBuffer.writeBytes(data, offset, length);
            if (isLast) {
               processor().process(request, valueBuffer, valueBuffer.readerIndex(), valueBuffer.readableBytes(), true);
               valueBuffer.clear();
               attrMatchedIndex = 0;
            }
         }

         @Override
         public void endTag(HttpRequest request) {
            trieState.reset();
            tagMatched = -1;
            attrMatchedIndex = -1;
         }
      }
   }

   private static class EmbeddedResourceProcessor extends Processor.BaseDelegating<HttpRequest> {
      private static final byte[] HTTP_PREFIX = "http".getBytes(StandardCharsets.UTF_8);

      private final boolean ignoreExternal;

      EmbeddedResourceProcessor(boolean ignoreExternal, Processor<HttpRequest> delegate) {
         super(delegate);
         this.ignoreExternal = ignoreExternal;
      }

      @Override
      public void process(HttpRequest request, ByteBuf data, int offset, int length, boolean isLastPart) {
         assert isLastPart;
         // TODO: here we should normalize the URL, remove escapes etc...

         boolean isAbsolute = hasPrefix(data, offset, length, HTTP_PREFIX);
         if (isAbsolute) {
            if (ignoreExternal) {
               int authorityStart = indexOf(data, offset, length, ':') + 3;
               boolean external = true;
               for (byte[] authority : request.session.httpDestinations().authorityBytes()) {
                  if (hasPrefix(data, offset + authorityStart, length, authority)) {
                     external = false;
                     break;
                  }
               }
               if (external) {
                  if (trace) {
                     log.trace("#{} Ignoring external URL {}", request.session.uniqueId(), Util.toString(data, offset, length));
                  }
                  return;
               }
            }
            if (trace) {
               log.trace("#{} Matched URL {}", request.session.uniqueId(), Util.toString(data, offset, length));
            }
            delegate.process(request, data, offset, length, true);
         } else if (data.getByte(offset) == '/') {
            // No need to rewrite relative URL
            if (trace) {
               log.trace("#{} Matched URL {}", request.session.uniqueId(), Util.toString(data, offset, length));
            }
            delegate.process(request, data, offset, length, true);
         } else {
            ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer(request.path.length() + length);
            Util.string2byteBuf(request.path, buffer);
            for (int i = buffer.writerIndex() - 1; i >= 0; --i) {
               if (buffer.getByte(i) == '/') {
                  buffer.writerIndex(i + 1);
                  break;
               }
            }
            buffer.ensureWritable(length);
            buffer.writeBytes(data, offset, length);
            if (trace) {
               log.trace("#{} Rewritten relative URL to {}", request.session.uniqueId(), Util.toString(buffer, buffer.readerIndex(), buffer.readableBytes()));
            }
            delegate.process(request, buffer, buffer.readerIndex(), buffer.readableBytes(), true);
            buffer.release();
         }
      }

      private int indexOf(ByteBuf data, int offset, int length, char c) {
         for (int i = 0; i <= length; ++i) {
            if (data.getByte(offset + i) == c) {
               return i;
            }
         }
         return -1;
      }

      private boolean hasPrefix(ByteBuf data, int offset, int length, byte[] authority) {
         int i = 0;
         for (; i < authority.length && i < length; i++) {
            if (data.getByte(offset + i) != authority[i]) {
               return false;
            }
         }
         return i == authority.length;
      }
   }
}
