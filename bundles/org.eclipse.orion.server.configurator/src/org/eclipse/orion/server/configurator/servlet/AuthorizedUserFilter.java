/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.configurator.servlet;

import java.io.IOException;
import java.util.Properties;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.orion.internal.server.servlets.ProtocolConstants;
import org.eclipse.orion.internal.server.servlets.workspace.authorization.AuthorizationService;
import org.eclipse.orion.server.configurator.ConfiguratorActivator;
import org.eclipse.orion.server.core.LogHelper;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.orion.server.core.authentication.IAuthenticationService;
import org.json.JSONException;
import org.osgi.framework.Version;
import org.osgi.service.http.HttpContext;

/**
 * The filter checks whether the request is made by an authorized user.
 */
public class AuthorizedUserFilter implements Filter {

	private IAuthenticationService authenticationService;
	private Properties authProperties;

	public void init(FilterConfig filterConfig) throws ServletException {
		authenticationService = ConfiguratorActivator.getDefault().getAuthService();
		// treat lack of authentication as an error. Administrator should use
		// "None" to disable authentication entirely
		if (authenticationService == null) {
			String msg = "Authentication service is missing. The server configuration must specify an authentication scheme, or use \"None\" to indicate no authentication"; //$NON-NLS-1$
			LogHelper.log(new Status(IStatus.ERROR, ConfiguratorActivator.PI_CONFIGURATOR, msg, null));
			throw new ServletException(msg);
		}
	}

	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;

		String userName = authenticationService.getAuthenticatedUser(httpRequest, httpResponse, authProperties);

		if (userName == null)
			userName = IAuthenticationService.ANONYMOUS_LOGIN_VALUE;

		try {
			String requestPath = httpRequest.getServletPath() + (httpRequest.getPathInfo() == null ? "" : httpRequest.getPathInfo());

			if (!AuthorizationService.checkRights(userName, requestPath, httpRequest.getMethod())) {
				if (IAuthenticationService.ANONYMOUS_LOGIN_VALUE.equals(userName)) {
					userName = authenticationService.authenticateUser(httpRequest, httpResponse, authProperties);
					if (userName == null)
						return;

					request.setAttribute(HttpContext.REMOTE_USER, userName);
					request.setAttribute(HttpContext.AUTHENTICATION_TYPE, authenticationService.getAuthType());
				} else {
					setNotAuthorized(httpRequest, httpResponse);
					return;
				}
			} else {
				if (!IAuthenticationService.ANONYMOUS_LOGIN_VALUE.equals(userName)) {
					request.setAttribute(HttpContext.REMOTE_USER, userName);
					request.setAttribute(HttpContext.AUTHENTICATION_TYPE, authenticationService.getAuthType());
				}
			}
		} catch (JSONException e) {
			httpResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			return;
		}

		chain.doFilter(request, response);
	}

	private void setNotAuthorized(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		authenticationService.setUnauthrizedUser(req, resp, authProperties);
	}

	public void destroy() {
		// TODO Auto-generated method stub
	}
}
