/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.java.undertow.runtime;

import com.google.common.collect.ImmutableList;
import com.palantir.conjure.java.undertow.lib.RoutingRegistry;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import java.util.function.BiFunction;

/**
 * Default Conjure implementation of a {@link RoutingRegistry}
 * which can be registered as an Undertow {@link HttpHandler}.
 */
public final class ConjureHandler implements HttpHandler, RoutingRegistry {

    private static final ImmutableList<BiFunction<EndpointDetails, HttpHandler, HttpHandler>> WRAPPERS =
            ImmutableList.<BiFunction<EndpointDetails, HttpHandler, HttpHandler>>of(
            // Allow the server to configure UndertowOptions.DECODE_URL = false to allow slashes in parameters.
            // Servers which do not configure DECODE_URL will still work properly except for encoded slash values.
            // When DECODE_URL has not been disabled, the following handler will no-op
            (endpoint, handler) -> new ConjureUrlDecodingHandler(handler, "UTF-8"),
            // no-cache and web-security handlers add listeners for the response to be committed,
            // they can be executed on the IO thread.
            (endpoint, handler) -> Methods.GET.equals(endpoint.method)
                    // Only applies to GET methods
                    ? new NoCachingResponseHandler(handler) : handler,
            (endpoint, handler) -> new WebSecurityHandler(handler),
            // It is vitally important to never run blocking operations on the initial IO thread otherwise
            // the server will not process new requests. all handlers executed after BlockingHandler
            // use the larger task pool which is allowed to block. Any operation which sets thread
            // state (e.g. SLF4J MDC or Tracer) must execute on the blocking thread otherwise state
            // will not propagate to the wrapped service.
            (endpoint, handler) -> new BlockingHandler(handler),
            // Logging context and trace handler must execute prior to the exception
            // to provide user and trace information on exceptions.
            (endpoint, handler) -> new LoggingContextHandler(handler),
            (endpoint, handler) -> new TraceHandler(endpoint.method + " " + endpoint.template, handler),
            (endpoint, handler) -> new ConjureExceptionHandler(handler)
    ).reverse();

    private final RoutingHandler routingHandler;

    public ConjureHandler(HttpHandler fallback) {
        this.routingHandler = Handlers.routing().setFallbackHandler(fallback);
    }

    public ConjureHandler() {
        this(ResponseCodeHandler.HANDLE_404);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        routingHandler.handleRequest(exchange);
    }

    @Override
    public ConjureHandler get(String template, HttpHandler handler) {
        return register(Methods.GET, template, handler);
    }

    @Override
    public ConjureHandler post(String template, HttpHandler handler) {
        return register(Methods.POST, template, handler);
    }

    @Override
    public ConjureHandler put(String template, HttpHandler handler) {
        return register(Methods.PUT, template, handler);
    }

    @Override
    public ConjureHandler delete(String template, HttpHandler handler) {
        return register(Methods.DELETE, template, handler);
    }

    private ConjureHandler register(HttpString method, String template, HttpHandler handler) {
        HttpHandler current = handler;
        EndpointDetails endpoint = new EndpointDetails(method, template);
        for (BiFunction<EndpointDetails, HttpHandler, HttpHandler> wrapper : WRAPPERS) {
            current = wrapper.apply(endpoint, current);
        }
        routingHandler.add(method, template, current);
        return this;
    }

    private static final class EndpointDetails {

        private final HttpString method;
        private final String template;

        EndpointDetails(HttpString method, String template) {
            this.method = method;
            this.template = template;
        }

        @Override
        public String toString() {
            return "EndpointDetails{method=" + method + ", template='" + template + "'}";
        }
    }
}
