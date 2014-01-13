package com.j256.simplewebframework.handler;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import javax.jws.WebMethod;
import javax.jws.WebService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import com.j256.simplewebframework.displayer.ResultDisplayer;

/**
 * Handler that wraps one or many {@link WebService} classes and takes the requests and makes method calls to the
 * configured web-services.
 * 
 * @author graywatson
 */
public class ServiceHandler extends AbstractHandler {

	private String handlerPathPrefix = "";

	private final Map<String, MethodWrapper> methodWrappers = new HashMap<String, MethodWrapper>();
	private final Map<Class<?>, ResultDisplayer> displayerClassMap = new HashMap<Class<?>, ResultDisplayer>();
	private final Map<String, ResultDisplayer> displayerMimeTypeMap = new HashMap<String, ResultDisplayer>();
	private boolean pathParam;

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			throws IOException {

		if (baseRequest.isHandled()) {
			return;
		}
		String pathInfo = request.getPathInfo();
		MethodWrapper methodWrapper = methodWrappers.get(pathInfo);
		if (methodWrapper == null) {
			if (pathParam) {
				for (Map.Entry<String, MethodWrapper> entry : methodWrappers.entrySet()) {
					String methodPath = entry.getKey();
					/*
					 * TODO: is this right? Shouldn't we match up to the dynamic parameter or something? Need to test
					 * this.
					 */
					if (pathInfo.startsWith(methodPath) && pathInfo.length() > methodPath.length()
							&& pathInfo.charAt(methodPath.length()) == '/') {
						methodWrapper = entry.getValue();
						break;
					}
				}
			}
			if (methodWrapper == null) {
				return;
			}
		}

		// ignore the request if it does not match @GET, @POST, etc.
		if (!methodWrapper.isAllowedRequest(request)) {
			return;
		}

		// we need to take a look at the class here and find a renderer
		Object result;
		try {
			result = methodWrapper.processRequest(baseRequest, request, response);
		} finally {
			if (response.isCommitted()) {
				baseRequest.setHandled(true);
			}
		}
		if (result == null) {
			// if the process method returns void and we did not throw then we have handled the request we guess.
			if (methodWrapper.isReturnsVoid()) {
				baseRequest.setHandled(true);
			}
		} else {
			ResultDisplayer displayer = displayerClassMap.get(result.getClass());
			if (displayer == null) {
				// after we look up the class returned, we check the content-type
				displayer = displayerMimeTypeMap.get(response.getContentType());
			}
			if (displayer == null) {
				/*
				 * Result was returned but cannot be displayed so it is ignored and the request may not be marked as
				 * handled.
				 */
			} else {
				if (displayer.renderResult(baseRequest, request, response, result)) {
					baseRequest.setHandled(true);
				} else {
					/*
					 * Displayer was not able to render the result so the result is ignored and the request may not be
					 * marked as handled.
					 */
				}
			}
		}
	}

	/**
	 * Register a web-service with the service handler.
	 */
	public void registerWebService(Object webService) {
		if (!webService.getClass().isAnnotationPresent(WebService.class)) {
			throw new IllegalArgumentException("Expected @WebService annotation on class "
					+ webService.getClass().getSimpleName());
		}
	
		String classPathPrefix = "";
		Path servicePath = webService.getClass().getAnnotation(Path.class);
		if (servicePath != null) {
			classPathPrefix = servicePath.value();
		}
	
		Produces produces = webService.getClass().getAnnotation(Produces.class);
		String webServiceContentType = null;
		if (produces != null) {
			String contentTypes[] = produces.value();
			if (contentTypes != null && contentTypes.length > 0) {
				webServiceContentType = contentTypes[0];
			}
		}
	
		// now process the class' methods all the way up the object chain
		for (Class<?> clazz = webService.getClass(); clazz != Object.class; clazz = clazz.getSuperclass()) {
	
			// process the methods on the class looking for @WebMethod
			processMethods(webService, classPathPrefix, webServiceContentType, clazz);
	
			// process that classes interfaces as well
			for (Class<?> interfaceClass : clazz.getInterfaces()) {
				processMethods(webService, classPathPrefix, webServiceContentType, interfaceClass);
			}
		}
	}

	/**
	 * Register a result displayer with this service handler.
	 */
	public void registerResultDisplayer(ResultDisplayer resultDisplayer) {
		if (resultDisplayer.getHandledClasses() != null) {
			for (Class<?> clazz : resultDisplayer.getHandledClasses()) {
				displayerClassMap.put(clazz, resultDisplayer);
			}
		}
		if (resultDisplayer.getHandledMimeTypes() != null) {
			for (String mimeType : resultDisplayer.getHandledMimeTypes()) {
				displayerMimeTypeMap.put(mimeType, resultDisplayer);
			}
		}
	}

	public void setPathPrefix(String pathPrefix) {
		this.handlerPathPrefix = pathPrefix;
	}

	public void setWebServices(Object[] webServices) {
		for (Object webService : webServices) {
			registerWebService(webService);
		}
	}

	public void setResultDisplayers(ResultDisplayer[] resultDisplayers) {
		for (ResultDisplayer resultDisplayer : resultDisplayers) {
			registerResultDisplayer(resultDisplayer);
		}
	}

	private void processMethods(Object webService, String classPathPrefix, String webServiceContentType, Class<?> clazz) {
		for (Method method : clazz.getMethods()) {

			// we only care about methods with @WebMethod
			if (!method.isAnnotationPresent(WebMethod.class)) {
				continue;
			}

			MethodWrapper wrapper =
					new MethodWrapper(webService, webServiceContentType, method, handlerPathPrefix, classPathPrefix);
			methodWrappers.put(wrapper.getFullPath(), wrapper);
			if (wrapper.isPathParam()) {
				pathParam = true;
			}
		}
	}
}
