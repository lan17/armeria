/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.common.logging;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientServiceOption;

/**
 * Utilities for logging decorators.
 */
public final class LoggingDecorators {
    private static final String REQUEST_FORMAT = "{} Request: {}";
    private static final String RESPONSE_FORMAT = "{} Response: {}";
    private static final String RESPONSE_FORMAT2 = "{} Response: {}, cause: {}";

    private LoggingDecorators() {}

    /**
     * Logs request and response using the specified {@code requestLogger} and {@code responseLogger}.
     */
    public static void logWhenComplete(
            Logger logger, RequestContext ctx,
            Consumer<RequestOnlyLog> requestLogger, Consumer<RequestLog> responseLogger) {
        ctx.log().whenRequestComplete().thenAccept(log -> {
            try {
                requestLogger.accept(log);
            } catch (Throwable t) {
                logException(logger, ctx, "request", t);
            }
        });
        ctx.log().whenComplete().thenAccept(log -> {
            try {
                responseLogger.accept(log);
            } catch (Throwable t) {
                logException(logger, ctx, "response", t);
            }
        });
    }

    private static void logException(Logger logger, RequestContext ctx,
                                     String requestOrResponse, Throwable cause) {
        try (SafeCloseable ignored = ctx.push()) {
            logger.warn("{} Unexpected exception while logging {}: ", ctx, requestOrResponse, cause);
        }
    }

    /**
     * Logs a stringified request of {@link RequestLog}.
     */
    public static void logRequest(
            Logger logger, RequestOnlyLog log,
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper,
            BiFunction<? super RequestContext, ? super RequestHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer) {

        final LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
        if (requestLogLevel.isEnabled(logger)) {
            final RequestContext ctx = log.context();
            if (log.requestCause() == null && isTransientService(ctx)) {
                return;
            }
            final String requestStr = log.toStringRequestOnly(requestHeadersSanitizer,
                                                              requestContentSanitizer,
                                                              requestTrailersSanitizer);
            try (SafeCloseable ignored = ctx.push()) {
                // We don't log requestCause when it's not null because responseCause is the same exception when
                // the requestCause is not null. That's way we don't have requestCauseSanitizer.
                requestLogLevel.log(logger, REQUEST_FORMAT, ctx, requestStr);
            }
        }
    }

    /**
     * Logs a stringified response of {@link RequestLog}.
     */
    public static void logResponse(
            Logger logger, RequestLog log,
            Function<? super RequestLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestLog, LogLevel> responseLogLevelMapper,
            BiFunction<? super RequestContext, ? super RequestHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer,
            BiFunction<? super RequestContext, ? super ResponseHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer,
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer) {

        final LogLevel responseLogLevel = responseLogLevelMapper.apply(log);
        final Throwable responseCause = log.responseCause();

        if (responseLogLevel.isEnabled(logger)) {
            final RequestContext ctx = log.context();
            if (responseCause == null &&
                !log.responseHeaders().status().isServerError() &&
                isTransientService(ctx)) {
                return;
            }

            final String responseStr = log.toStringResponseOnly(responseHeadersSanitizer,
                                                                responseContentSanitizer,
                                                                responseTrailersSanitizer);
            try (SafeCloseable ignored = ctx.push()) {
                if (responseCause == null) {
                    responseLogLevel.log(logger, RESPONSE_FORMAT, ctx, responseStr);
                    return;
                }

                final LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
                if (!requestLogLevel.isEnabled(logger)) {
                    // Request wasn't logged, but this is an unsuccessful response,
                    // so we log the request too to help debugging.
                    responseLogLevel.log(logger, REQUEST_FORMAT, ctx,
                                         log.toStringRequestOnly(requestHeadersSanitizer,
                                                                 requestContentSanitizer,
                                                                 requestTrailersSanitizer));
                }

                final Object sanitizedResponseCause = responseCauseSanitizer.apply(ctx, responseCause);
                if (sanitizedResponseCause == null) {
                    responseLogLevel.log(logger, RESPONSE_FORMAT, ctx, responseStr);
                    return;
                }

                if (sanitizedResponseCause instanceof Throwable) {
                    responseLogLevel.log(logger, RESPONSE_FORMAT, ctx,
                                         responseStr, sanitizedResponseCause);
                } else {
                    responseLogLevel.log(logger, RESPONSE_FORMAT2, ctx,
                                         responseStr, sanitizedResponseCause);
                }
            }
        }
    }

    private static boolean isTransientService(RequestContext ctx) {
        return ctx instanceof ServiceRequestContext &&
               !((ServiceRequestContext) ctx).config()
                                             .transientServiceOptions()
                                             .contains(TransientServiceOption.WITH_SERVICE_LOGGING);
    }
}
