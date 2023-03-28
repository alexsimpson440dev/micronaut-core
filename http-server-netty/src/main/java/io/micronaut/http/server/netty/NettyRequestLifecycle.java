/*
 * Copyright 2017-2022 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server.netty;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.execution.DelayedExecutionFlow;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.netty.stream.StreamedHttpRequest;
import io.micronaut.http.server.RequestLifecycle;
import io.micronaut.http.server.multipart.MultipartBody;
import io.micronaut.http.server.netty.multipart.NettyStreamingFileUpload;
import io.micronaut.http.server.netty.types.files.NettyStreamedFileCustomizableResponseType;
import io.micronaut.http.server.netty.types.files.NettySystemFileCustomizableResponseType;
import io.micronaut.http.server.types.files.FileCustomizableResponseType;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteMatch;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.FullHttpRequest;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Internal
final class NettyRequestLifecycle extends RequestLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(NettyRequestLifecycle.class);

    private final RoutingInBoundHandler rib;
    private final ChannelHandlerContext ctx;

    /**
     * Should only be used where netty-specific stuff is needed, such as reading the body or
     * writing the response. Otherwise, use {@link #request()} which can be updated by filters
     */
    private final NettyHttpRequest<?> nettyRequest;

    NettyRequestLifecycle(RoutingInBoundHandler rib, ChannelHandlerContext ctx, NettyHttpRequest<?> request) {
        super(rib.routeExecutor, request);
        this.rib = rib;
        this.ctx = ctx;
        this.nettyRequest = request;

        multipartEnabled(rib.multipartEnabled);
    }

    void handleNormal() {
        ctx.channel().config().setAutoRead(false);

        if (LOG.isDebugEnabled()) {
            HttpMethod httpMethod = request().getMethod();
            ServerRequestContext.set(request());
            LOG.debug("Request {} {}", httpMethod, request().getUri());
        }

        ExecutionFlow<MutableHttpResponse<?>> result;

        // handle decoding failure
        DecoderResult decoderResult = nettyRequest.getNativeRequest().decoderResult();
        if (decoderResult.isFailure()) {
            Throwable cause = decoderResult.cause();
            HttpStatus status = cause instanceof TooLongFrameException ? HttpStatus.REQUEST_ENTITY_TOO_LARGE : HttpStatus.BAD_REQUEST;
            result = onStatusError(
                HttpResponse.status(status),
                status.getReason()
            );
        } else {
            result = normalFlow();
        }

        result.onComplete((response, throwable) -> {
            if (nettyRequest.getNativeRequest() instanceof FullHttpRequest full) {
                full.release();
            }
            rib.writeResponse(ctx, nettyRequest, response, throwable);
        });
    }

    @Nullable
    @Override
    protected FileCustomizableResponseType findFile() {
        Optional<URL> optionalUrl = rib.staticResourceResolver.resolve(request().getUri().getPath());
        if (optionalUrl.isPresent()) {
            try {
                URL url = optionalUrl.get();
                if (url.getProtocol().equals("file")) {
                    File file = Paths.get(url.toURI()).toFile();
                    if (file.exists() && !file.isDirectory() && file.canRead()) {
                        return new NettySystemFileCustomizableResponseType(file);
                    }
                }
                return new NettyStreamedFileCustomizableResponseType(url);
            } catch (URISyntaxException e) {
                //no-op
            }
        }
        return null;
    }

    @Override
    protected ExecutionFlow<RouteMatch<?>> fulfillArguments(RouteMatch<?> routeMatch) {
        // handle decoding failure
        DecoderResult decoderResult = nettyRequest.getNativeRequest().decoderResult();
        if (decoderResult.isFailure()) {
            return ExecutionFlow.error(decoderResult.cause());
        }
        return super.fulfillArguments(routeMatch).flatMap(this::waitForBody);
    }

    /**
     * If necessary (e.g. when there's a {@link Body} parameter), wait for the body to come in.
     * This method also sometimes fulfills more controller parameters with form data.
     */
    private ExecutionFlow<RouteMatch<?>> waitForBody(RouteMatch<?> routeMatch) {
        // note: shouldReadBody only works when fulfill has been called at least once
        if (!shouldReadBody(routeMatch)) {
            ctx.read();
            return ExecutionFlow.just(routeMatch);
        }
        BaseRouteCompleter completer = nettyRequest.isFormOrMultipartData() ?
            new FormRouteCompleter(new NettyStreamingFileUpload.Factory(rib.serverConfiguration.getMultipart(), rib.getIoExecutor()), rib.conversionService, nettyRequest, routeMatch) :
            new BaseRouteCompleter(nettyRequest, routeMatch);
        HttpContentProcessor processor = rib.httpContentProcessorResolver.resolve(nettyRequest, routeMatch);
        io.netty.handler.codec.http.HttpRequest nativeRequest = nettyRequest.getNativeRequest();
        if (nativeRequest instanceof FullHttpRequest full) {
            // we will read the body, retain the request
            full.retain();
            List<Object> bufferList = new ArrayList<>(1);
            try {
                if (full.content().isReadable()) {
                    processor.add(full, bufferList);
                } else {
                    full.release();
                }
                processor.complete(bufferList);
                for (Object o : bufferList) {
                    completer.add(o);
                }
                completer.completeSuccess();
            } catch (Throwable e) {
                try {
                    processor.cancel();
                } catch (Throwable f) {
                    e.addSuppressed(f);
                }
                completer.completeFailure(e);
            }
            return ExecutionFlow.just(routeMatch);
        } else if (nativeRequest instanceof StreamedHttpRequest streamed) {
            StreamingDataSubscriber pr = new StreamingDataSubscriber(completer, processor);
            streamed.subscribe(pr);
            return pr.completion;
        } else {
            throw new AssertionError();
        }
    }

    void handleException(Throwable cause) {
        onError(cause).onComplete((response, throwable) -> rib.writeResponse(ctx, nettyRequest, response, throwable));
    }

    private boolean shouldReadBody(RouteMatch<?> routeMatch) {
        if (!HttpMethod.permitsRequestBody(request().getMethod())) {
            return false;
        }
        if (routeMatch instanceof MethodBasedRouteMatch<?, ?> methodBasedRouteMatch) {
            if (hasArg(methodBasedRouteMatch, MultipartBody.class)) {
                // MultipartBody will subscribe to the request body in MultipartBodyArgumentBinder
                return false;
            }
            if (hasArg(methodBasedRouteMatch, HttpRequest.class)) {
                // HttpRequest argument in the method
                return true;
            }
        }
        Optional<Argument<?>> bodyArgument = routeMatch.getBodyArgument()
            .filter(argument -> argument.getAnnotationMetadata().hasAnnotation(Body.class));
        if (bodyArgument.isPresent() && !routeMatch.isSatisfied(bodyArgument.get().getName())) {
            // Body argument in the method
            return true;
        }
        // Might be some body parts
        return !routeMatch.isExecutable();
    }

    private static boolean hasArg(MethodBasedRouteMatch<?, ?> methodBasedRouteMatch, Class<?> type) {
        for (Argument<?> argument : methodBasedRouteMatch.getArguments()) {
            if (argument.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private static class StreamingDataSubscriber implements Subscriber<ByteBufHolder> {
        final DelayedExecutionFlow<RouteMatch<?>> completion = DelayedExecutionFlow.create();
        private boolean completed = false;

        private final List<Object> bufferList = new ArrayList<>(1);
        private final HttpContentProcessor contentProcessor;
        private final BaseRouteCompleter completer;
        private Subscription upstream;

        private volatile boolean upstreamRequested = false;
        private boolean downstreamDone = false;

        StreamingDataSubscriber(BaseRouteCompleter completer, HttpContentProcessor contentProcessor) {
            this.completer = completer;
            this.contentProcessor = contentProcessor;
        }

        private void checkDemand() {
            if (completer.needsInput && !upstreamRequested) {
                upstreamRequested = true;
                upstream.request(1);
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (upstream != null) {
                throw new IllegalStateException("Only one upstream subscription allowed");
            }
            upstream = s;
            completer.checkDemand = this::checkDemand;
            checkDemand();
        }

        private void sendToCompleter(Collection<Object> out) throws Throwable {
            for (Object processed : out) {
                boolean wasExecuted = completer.execute;
                completer.add(processed);
                if (!wasExecuted && completer.execute) {
                    executeRoute();
                }
            }
        }

        @Override
        public void onNext(ByteBufHolder holder) {
            upstreamRequested = false;
            if (downstreamDone) {
                // previous error
                holder.release();
                return;
            }
            try {
                bufferList.clear();
                contentProcessor.add(holder, bufferList);
                sendToCompleter(bufferList);
                checkDemand();
            } catch (Throwable t) {
                handleError(t);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (downstreamDone) {
                // previous error
                LOG.warn("Downstream already complete, dropping error", t);
                return;
            }
            handleError(t);
        }

        private void handleError(Throwable t) {
            try {
                upstream.cancel();
            } catch (Throwable o) {
                t.addSuppressed(o);
            }
            try {
                contentProcessor.cancel();
            } catch (Throwable o) {
                t.addSuppressed(o);
            }
            completer.completeFailure(t);
            // this may drop the exception if the route has already been executed. However, that is
            // only the case if there are publisher parameters, and those will still receive the
            // failure. Hopefully.
            if (!completed) {
                completion.completeExceptionally(t);
                completed = true;
            }
            downstreamDone = true;
        }

        @Override
        public void onComplete() {
            if (downstreamDone) {
                // previous error
                return;
            }
            try {
                bufferList.clear();
                contentProcessor.complete(bufferList);
                sendToCompleter(bufferList);
                boolean wasExecuted = completer.execute;
                completer.completeSuccess();
                if (!wasExecuted && completer.execute) {
                    executeRoute();
                }
            } catch (Throwable t) {
                handleError(t);
            }
        }

        private void executeRoute() {
            completion.complete(completer.routeMatch);
            completed = true;
        }
    }
}
