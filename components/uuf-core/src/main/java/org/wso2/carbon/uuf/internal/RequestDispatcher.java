/*
 *  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.uuf.internal;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.uuf.api.config.Configuration;
import org.wso2.carbon.uuf.core.App;
import org.wso2.carbon.uuf.exception.HttpErrorException;
import org.wso2.carbon.uuf.exception.PageRedirectException;
import org.wso2.carbon.uuf.exception.UUFException;
import org.wso2.carbon.uuf.internal.debug.DebugLogger;
import org.wso2.carbon.uuf.internal.debug.Debugger;
import org.wso2.carbon.uuf.internal.deployment.AppRegistry;
import org.wso2.carbon.uuf.internal.exception.DeploymentException;
import org.wso2.carbon.uuf.internal.filter.CsrfFilter;
import org.wso2.carbon.uuf.internal.filter.Filter;
import org.wso2.carbon.uuf.internal.filter.FilterResult;
import org.wso2.carbon.uuf.internal.io.StaticResolver;
import org.wso2.carbon.uuf.spi.HttpRequest;
import org.wso2.carbon.uuf.spi.HttpResponse;

import java.util.List;

import static org.wso2.carbon.uuf.spi.HttpResponse.CONTENT_TYPE_APPLICATION_JSON;
import static org.wso2.carbon.uuf.spi.HttpResponse.CONTENT_TYPE_TEXT_HTML;
import static org.wso2.carbon.uuf.spi.HttpResponse.HEADER_CACHE_CONTROL;
import static org.wso2.carbon.uuf.spi.HttpResponse.HEADER_EXPIRES;
import static org.wso2.carbon.uuf.spi.HttpResponse.HEADER_LOCATION;
import static org.wso2.carbon.uuf.spi.HttpResponse.HEADER_PRAGMA;
import static org.wso2.carbon.uuf.spi.HttpResponse.HEADER_X_CONTENT_TYPE_OPTIONS;
import static org.wso2.carbon.uuf.spi.HttpResponse.HEADER_X_XSS_PROTECTION;
import static org.wso2.carbon.uuf.spi.HttpResponse.STATUS_BAD_REQUEST;
import static org.wso2.carbon.uuf.spi.HttpResponse.STATUS_FOUND;
import static org.wso2.carbon.uuf.spi.HttpResponse.STATUS_INTERNAL_SERVER_ERROR;
import static org.wso2.carbon.uuf.spi.HttpResponse.STATUS_NOT_FOUND;
import static org.wso2.carbon.uuf.spi.HttpResponse.STATUS_OK;

public class RequestDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestDispatcher.class);

    private final StaticResolver staticResolver;
    private final Debugger debugger;
    private final List<Filter> filters;

    public RequestDispatcher() {
        this(new StaticResolver(), (Debugger.isDebuggingEnabled() ? new Debugger() : null));
    }

    public RequestDispatcher(StaticResolver staticResolver, Debugger debugger) {
        this.staticResolver = staticResolver;
        this.debugger = debugger;
        this.filters = ImmutableList.of(new CsrfFilter());
    }

    public void serve(HttpRequest request, HttpResponse response, AppRegistry appRegistry) {
        if (!request.isValid()) {
            serveDefaultErrorPage(STATUS_BAD_REQUEST, "Invalid URI '" + request.getUri() + "'.", response);
            return;
        }
        if (request.isDefaultFaviconRequest()) {
            serveDefaultFavicon(request, response);
            return;
        }

        App app;
        try {
            app = appRegistry.getApp(request.getContextPath());
        } catch (DeploymentException e) {
            String msg = "Cannot deploy an app for context path '" + request.getContextPath() + "'.";
            LOGGER.error(msg, e);
            serveDefaultErrorPage(STATUS_INTERNAL_SERVER_ERROR, msg, response);
            return;
        }
        if (app == null) {
            serveDefaultErrorPage(STATUS_NOT_FOUND,
                                  "Cannot find an app for context path '" + request.getContextPath() + "'.", response);
            return;
        }

        serve(app, request, response);
    }

    private void serve(App app, HttpRequest request, HttpResponse response) {
        try {
            if (request.isStaticResourceRequest()) {
                staticResolver.serve(app, request, response);
            } else if (Debugger.isDebuggingEnabled() && request.isDebugRequest()) {
                debugger.serve(app, request, response);
            } else {
                servePageOrFragment(app, request, response);
            }
        } catch (PageRedirectException e) {
            response.setStatus(STATUS_FOUND);
            response.setHeader(HEADER_LOCATION, e.getRedirectUrl());
        } catch (HttpErrorException e) {
            serveDefaultErrorPage(e.getHttpStatusCode(), e.getMessage(), response);
        } catch (UUFException e) {
            String msg = "A server error occurred while serving for request '" + request + "'.";
            LOGGER.error(msg, e);
            serveDefaultErrorPage(STATUS_INTERNAL_SERVER_ERROR, msg, response);
        } catch (Exception e) {
            String msg = "An unexpected error occurred while serving for request '" + request + "'.";
            LOGGER.error(msg, e);
            serveDefaultErrorPage(STATUS_INTERNAL_SERVER_ERROR, msg, response);
        }
    }

    private void servePageOrFragment(App app, HttpRequest request, HttpResponse response) {
        DebugLogger.startRequest(request);
        try {
            // set default and configured http response headers for security purpose
            setResponseSecurityHeaders(app, response);
            if (request.isFragmentRequest()) {
                JsonObject renderedFragment = app.renderFragment(request, response);
                response.setContent(STATUS_OK, renderedFragment.toString(), CONTENT_TYPE_APPLICATION_JSON);
            } else {
                // Execute filters
                Configuration configuration = app.getConfiguration();
                for (Filter filter : filters) {
                    FilterResult result = filter.doFilter(request, configuration);
                    if (!result.isContinue()) {
                        serveDefaultErrorPage(result.getHttpStatusCode(), result.getMessage(), response);
                        return;
                    }
                }
                String html = app.renderPage(request, response);
                response.setContent(STATUS_OK, html, CONTENT_TYPE_TEXT_HTML);
            }
        } catch (UUFException e) {
            throw e;
        } catch (Exception e) {
            // May be an UUFException cause this 'e' Exception. Let's unwrap 'e' and find out.
            Throwable th = e;
            while ((th = th.getCause()) != null) {
                if (th instanceof UUFException) {
                    // Cause of 'e' is an UUFException. Throw 'th' so that we can handle it properly.
                    throw (UUFException) th;
                }
            }
            // Cause of 'e' is not an UUFException.
            throw e;
        } finally {
            DebugLogger.endRequest(request);
        }
    }

    private void serveDefaultErrorPage(int httpStatusCode, String content, HttpResponse response) {
        response.setContent(httpStatusCode, content);
    }

    private void serveDefaultFavicon(HttpRequest request, HttpResponse response) {
        staticResolver.serveDefaultFavicon(request, response);
    }

    /**
     * Sets some default mandatory and user configured security related headers to the response path.
     *
     * @param app          the application used with getting the security related configuration.
     * @param httpResponse the http response instance used with setting the headers.
     */
    private void setResponseSecurityHeaders(App app, HttpResponse httpResponse) {
        httpResponse.setHeader(HEADER_X_CONTENT_TYPE_OPTIONS, "nosniff");
        httpResponse.setHeader(HEADER_X_XSS_PROTECTION, "1; mode=block");
        httpResponse.setHeader(HEADER_CACHE_CONTROL, "no-store, no-cache, must-revalidate, private");
        httpResponse.setHeader(HEADER_EXPIRES, "0");
        httpResponse.setHeader(HEADER_PRAGMA, "no-cache");

        // if there are any headers configured by the user for this app, then add them also to the response
        app.getConfiguration().getResponseHeaders().getPages().forEach(httpResponse::setHeader);
    }
}
