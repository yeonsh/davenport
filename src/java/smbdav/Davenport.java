/* Davenport WebDAV SMB Gateway
 * Copyright (C) 2003  Eric Glass
 * Copyright (C) 2003  Ronald Tschalar
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package smbdav;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import java.net.UnknownHostException;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import jcifs.Config;
import jcifs.UniAddress;

import jcifs.http.NtlmSsp;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.NtStatus;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileFilter;
import jcifs.smb.SmbSession;

import jcifs.util.Base64;
import jcifs.util.Hexdump;

/**
 * This servlet provides a WebDAV gateway to CIFS/SMB shared resources.
 * <p>
 * You can specify jCIFS configuration settings in the servlet's
 * initialization parameters which will be applied to the environment.
 * Settings of particular interest to the Davenport servlet include:
 * <p>
 * <table border="1">
 * <tr>
 *     <th>Parameter</th><th>Description</th>
 * </tr>
 * <tr>
 *     <td><code>jcifs.smb.client.domain</code></td>
 *     <td>Provides the default domain if not specified during HTTP
 *     Basic authentication.  If the user enters "username" rather
 *     than "DOMAIN&#92;username", this specifies the default domain against
 *     which the user should be authenticated.</td>
 * </tr>
 * <tr>
 *     <td><code>jcifs.http.domainController</code></td>
 *     <td>Provides the IP address or name of the server used to authenticate
 *     clients.  This is only used for browsing the root
 *     ("<code>smb://</code>") and workgroups (if a server cannot be found).
 *     For servers, shares, directories and files, the corresponding server
 *     is used.  If not specified, the system will attempt to locate a
 *     controller for the domain specified in
 *     <code>jcifs.smb.client.domain</code> (if present).  <b>If neither
 *     <code>jcifs.http.domainController</code> nor
 *     <code>jcifs.smb.client.domain</code> are specified, authentication
 *     will not be required for browsing the SMB root (or workgroups for
 *     which a server cannot be found).</b>  This may pose a security
 *     risk.
 *     <p>
 *     It is not necessary for this to specify a real domain controller;
 *     any machine offering SMB services can be used.</td>
 * </tr>
 * <tr>
 *     <td><code>jcifs.netbios.wins</code></td>
 *     <td>Specifies the IP address of a WINS server to be used in resolving
 *     server and domain/workgroup names.  This is needed to locate
 *     machines in other subnets.</td>
 * </tr>
 * <tr>
 *     <td><code>jcifs.http.enableBasic</code></td>
 *     <td>Enables/disables HTTP Basic authentication support.  This
 *     allows non-NTLM-capable browsers to successfully authenticate.
 *     NTLM-capable clients will authenticate using NTLM.  This defaults
 *     to <code>true</code>.  Setting this to <code>false</code> will
 *     disable HTTP Basic authentication entirely, allowing only
 *     NTLM-capable browsers to connect.</td>
 * </tr>
 * <tr>
 *     <td><code>jcifs.http.insecureBasic</code></td>
 *     <td>Enables/disables HTTP Basic authentication over an insecure
 *     channel.  Normally, HTTP Basic authentication will only be
 *     available over HTTPS.  Setting this to <code>true</code>
 *     will offer HTTP Basic authentication over insecure HTTP,
 *     sending login information over the network unencrypted.
 *     <b>This is a severe security risk, and is strongly advised against.</b>
 *     This defaults to <code>false</code>.</td>
 * </tr>
 * <tr>
 *     <td><code>jcifs.http.basicRealm</code></td>
 *     <td>Specifies the HTTP Basic realm that will be presented during
 *     authentication.  Defaults to "Davenport".</td>
 * </tr>
 * </table>
 * <p>
 * Further details regarding configuration of the jCIFS environment can be
 * found in the jCIFS documentation (available from
 * <a href="http://jcifs.samba.org">http://jcifs.samba.org</a>).
 * </p>
 * <p>
 * Additionally, you can specify your own custom handlers for HTTP methods.
 * By implementing {@link smbdav.MethodHandler}, you can provide your own
 * behavior for GET, PUT, etc. requests.  To enable your handler,
 * add an initialization parameter with the handler's classname.  For
 * example:
 * </p>
 * <pre>
 * &lt;init-param&gt;
 *     &lt;param-name&gt;handler.GET&lt;/param-name&gt;
 *     &lt;param-value&gt;com.foo.MyGetHandler&lt;/param-value&gt;
 * &lt;/init-param&gt;
 * </pre>
 * <p>
 * This will install a <code>com.foo.MyGetHandler</code> instance as
 * the handler for GET requests.
 * </p>
 *
 * @author Eric Glass
 */
public class Davenport extends HttpServlet {

    /**
     * The name of the servlet context attribute containing the
     * <code>SmbFileFilter</code> applied to resource requests.
     */
    public static final String RESOURCE_FILTER = "davenport.resourceFilter";

    /**
     * The name of the servlet context attribute containing the
     * <code>LockManager</code> which maintains WebDAV locks.
     */
    public static final String LOCK_MANAGER = "davenport.lockManager";

    /**
     * The name of the servlet context attribute containing the charset used
     * to interpret request URIs.
     */
    public static final String REQUEST_URI_CHARSET = "request-uri.charset";

    /**
     * The name of the request attribute containing the context base for
     * URL rewriting.
     */
    public static final String CONTEXT_BASE = "davenport.contextBase";

    /**
     * The name of the request attribute containing the authenticated
     * principal.
     */
    public static final String PRINCIPAL = "davenport.principal";

    private static final int DEFAULT_SMB_PORT = 139;

    private final Map handlers = new HashMap();

    private ErrorHandler[] errorHandlers;

    private ResourceFilter filter;

    private UniAddress defaultServer;

    private NtlmPasswordAuthentication anonymousCredentials;

    private String defaultDomain;

    private String realm;

    private String contextBase;

    private String contextBaseHeader;

    private boolean alwaysAuthenticate;

    private boolean acceptBasic;

    private boolean enableBasic;

    private boolean enableNtlm;

    private boolean closeOnAuthenticate;

    private boolean insecureBasic;

    public void init() throws ServletException {
        ServletConfig config = getServletConfig();
        String logProviderName = Log.class.getName();
        String logProvider = config.getInitParameter(logProviderName);
        if (logProvider != null) {
            try {
                System.setProperty(logProviderName, logProvider);
            } catch (Exception ignore) { }
        }
        String logThreshold = config.getInitParameter(logProviderName +
                ".threshold");
        if (logThreshold != null) {
            try {
                System.setProperty(logProviderName + ".threshold",
                        logThreshold);
            } catch (Exception ignore) { }
        }
        Log.log(Log.DEBUG, "Logging initialized.");
        if (Log.getThreshold() < Log.INFORMATION) {
            Properties props = new Properties();
            Enumeration params = config.getInitParameterNames();
            while (params.hasMoreElements()) {
                String paramName = (String) params.nextElement();
                props.setProperty(paramName,
                        config.getInitParameter(paramName));
            }
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            props.list(new PrintStream(stream));
            Log.log(Log.DEBUG, "Davenport init parameters: {0}", stream);
        }
        String requestUriCharset = config.getInitParameter(REQUEST_URI_CHARSET);
        if (requestUriCharset == null) requestUriCharset = "ISO-8859-1";
        contextBase = config.getInitParameter("contextBase");
        contextBaseHeader = config.getInitParameter("contextBaseHeader");
        config.getServletContext().setAttribute(REQUEST_URI_CHARSET,
                requestUriCharset);
        Config.setProperty("jcifs.netbios.cachePolicy", "600");
        Config.setProperty("jcifs.smb.client.soTimeout", "300000");
        Config.setProperty("jcifs.smb.client.attrExpirationPeriod", "60000");
        Enumeration enumeration = config.getInitParameterNames();
        while (enumeration.hasMoreElements()) {
            String name = (String) enumeration.nextElement();
            if (name.startsWith("jcifs.")) {
                Config.setProperty(name, config.getInitParameter(name));
            }
        }
        if (Log.getThreshold() < Log.INFORMATION) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            try {
                Config.list(new PrintStream(stream));
            } catch (Exception ignore) { }
            Log.log(Log.DEBUG, "jCIFS Properties: {0}", stream);
        }
        String defaultDomain = Config.getProperty("jcifs.smb.client.domain");
        String defaultServer = Config.getProperty(
                "jcifs.http.domainController");
        if (defaultServer == null) defaultServer = defaultDomain;
        if (defaultServer != null) {
            try {
                this.defaultServer = UniAddress.getByName(defaultServer, true);
            } catch (UnknownHostException ex) {
                throw new UnavailableException(SmbDAVUtilities.getResource(
                        Davenport.class, "unknownDefaultServer",
                                new Object[] { defaultServer }, null));
            }
        }
        String acceptBasic = config.getInitParameter("acceptBasic");
        this.acceptBasic = Boolean.valueOf(acceptBasic).booleanValue();
        String enableBasic = Config.getProperty("jcifs.http.enableBasic");
        this.enableBasic = (enableBasic == null) ||
                Boolean.valueOf(enableBasic).booleanValue();
        String enableNtlm = config.getInitParameter("enableNtlm");
        this.enableNtlm = (enableNtlm == null) ||
                Boolean.valueOf(enableNtlm).booleanValue();
        String closeOnAuthenticate =
                config.getInitParameter("closeOnAuthenticate");
        this.closeOnAuthenticate =
                Boolean.valueOf(closeOnAuthenticate).booleanValue();
        this.insecureBasic = Boolean.valueOf(
                Config.getProperty("jcifs.http.insecureBasic")).booleanValue();
        realm = Config.getProperty("jcifs.http.basicRealm");
        if (realm == null) realm = "Davenport";
        String alwaysAuthenticate =
                config.getInitParameter("alwaysAuthenticate");
        this.alwaysAuthenticate = (alwaysAuthenticate == null) ||
                Boolean.valueOf(alwaysAuthenticate).booleanValue();
        String anonymousCredentials =
                config.getInitParameter("anonymousCredentials");
        if (anonymousCredentials != null) {
            int index = anonymousCredentials.indexOf(':');
            String user = (index != -1) ?
                    anonymousCredentials.substring(0, index) :
                            anonymousCredentials;
            String password = (index != -1) ?
                    anonymousCredentials.substring(index + 1) : "";
            String domain;
            if ((index = user.indexOf('\\')) != -1 ||
                    (index = user.indexOf('/')) != -1) {
                domain = user.substring(0, index);
                user = user.substring(index + 1);
            } else {
                domain = defaultDomain;
            }
            this.anonymousCredentials =
                    new NtlmPasswordAuthentication(domain, user, password);
        }
        initLockManager(config);
        initFilter(config);
        initHandlers(config);
        initErrorHandlers(config);
    }

    public void destroy() {
        Iterator iterator = handlers.entrySet().iterator();
        while (iterator.hasNext()) {
            ((MethodHandler) ((Map.Entry)
                    iterator.next()).getValue()).destroy();
            iterator.remove();
        }
        if (errorHandlers != null) {
            for (int i = errorHandlers.length - 1; i >= 0; i--) {
                errorHandlers[i].destroy();
            }
            errorHandlers = null;
        }
        if (filter != null) {
            filter.destroy();
            filter = null;
        }
        ServletContext context = getServletContext();
        context.removeAttribute(LOCK_MANAGER);
        context.removeAttribute(RESOURCE_FILTER);
        context.removeAttribute(REQUEST_URI_CHARSET);
        Log.log(Log.DEBUG, "Davenport finished destroy.");
    }

    /**
     * Authenticates the user against a domain before forwarding the
     * request to the appropriate handler.
     *
     * @param request The request being handled.
     * @param response The response supplied by the servlet.
     * @throws IOException If an IO error occurs while handling the request.
     * @throws ServletException If an application error occurs.
     */
    protected void service(HttpServletRequest request,
            HttpServletResponse response) throws IOException, ServletException {
        Log.log(Log.INFORMATION, "Received {0} request for \"{1}\".",
                new Object[] { request.getMethod(), request.getRequestURL() });
        if (Log.getThreshold() < Log.INFORMATION) {
            StringBuffer headers = new StringBuffer();
            Enumeration headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = (String) headerNames.nextElement();
                headers.append("    ").append(headerName).append(": ");
                Enumeration headerValues = request.getHeaders(headerName);
                while (headerValues.hasMoreElements()) {
                    headers.append(headerValues.nextElement());
                    if (headerValues.hasMoreElements()) headers.append(", ");
                }
                if (headerNames.hasMoreElements()) headers.append("\n");
            }
            Log.log(Log.DEBUG, "Headers:\n{0}", headers);
            HttpSession session = request.getSession(false);
            if (session != null) {
                Log.log(Log.DEBUG, "Active session: {0}", session.getId());
            } else {
                Log.log(Log.DEBUG, "Session not yet established.");
            }
        }
        String contextBase = this.contextBase;
        if (contextBaseHeader != null) {
            String dynamicBase = request.getHeader(contextBaseHeader);
            if (dynamicBase != null) contextBase = dynamicBase;
        }
        if (contextBase != null) {
            if (!contextBase.endsWith("/")) contextBase += "/";
            request.setAttribute(CONTEXT_BASE, contextBase);
            Log.log(Log.DEBUG, "Using context base: " + contextBase);
        }
        boolean usingBasic = (acceptBasic || enableBasic) &&
                (insecureBasic || request.isSecure());
        if (Log.getThreshold() < Log.INFORMATION) {
            Log.log(Log.DEBUG, enableNtlm ? "NTLM auth offered to client." :
                    "NTLM auth NOT offered to client.");
            Log.log(Log.DEBUG, enableBasic ? "Basic auth offered to client." :
                    "Basic auth NOT offered to client.");
            if (acceptBasic) {
                Log.log(Log.DEBUG,
                        "Basic auth accepted if supplied by client.");
            }
            Log.log(Log.DEBUG, insecureBasic ?
                    "Basic auth enabled over insecure requests." :
                            "Basic auth disabled over insecure requests.");
            Log.log(Log.DEBUG, request.isSecure() ?
                    "This request is secure." : "This request is NOT secure.");
            Log.log(Log.DEBUG, usingBasic ?
                    "Basic auth ENABLED for this request." :
                            "Basic auth DISABLED for this request.");
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || "".equals(pathInfo)) pathInfo = "/";
        String target = "smb:/" + pathInfo;
        UniAddress server = null;
        int port = DEFAULT_SMB_PORT;
        try {
            server = getServer(target);
            port = getPort(target);
            Log.log(Log.DEBUG, "Target is \"{0}:{1}\".",
                    new Object[] { server, new Integer(port) });
        } catch (UnknownHostException ex) {
            Log.log(Log.DEBUG, "Unknown server: {0}", ex);
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    SmbDAVUtilities.getResource(Davenport.class,
                            "unknownServer", new Object[] { target },
                                    request.getLocale()));
            return;
        }
        NtlmPasswordAuthentication authentication = null;
        String authorization = request.getHeader("Authorization");
        Log.log(Log.DEBUG, "Authorization: " + authorization);
        if (authorization != null && (authorization.regionMatches(true, 0,
                "NTLM ", 0, 5) || (usingBasic && authorization.regionMatches(
                        true, 0, "Basic ", 0, 6)))) {
            if (authorization.regionMatches(true, 0, "NTLM ", 0, 5)) {
                Log.log(Log.INFORMATION, "Using NTLM.");
                byte[] challenge = SmbSession.getChallenge(server, port);
                if (Log.getThreshold() < Log.INFORMATION) {
                    ByteArrayOutputStream dump = new ByteArrayOutputStream();
                    byte[] auth = Base64.decode(authorization.substring(5));
                    Hexdump.hexdump(new PrintStream(dump), auth, 0,
                            auth.length);
                    Log.log(Log.DEBUG, "NTLM Message:\n{0}", dump);
                    dump.reset();
                    Hexdump.hexdump(new PrintStream(dump), challenge, 0,
                            challenge.length);
                    Log.log(Log.DEBUG, "Challenge:\n{0}", dump);
                }
                authentication = NtlmSsp.authenticate(request, response,
                        challenge);
                if (authentication == null) return;
            } else {
                Log.log(Log.INFORMATION, "Using Basic.");
                String authInfo = new String(Base64.decode(
                        authorization.substring(6)), "ISO-8859-1");
                Log.log(Log.DEBUG, authInfo);
                int index = authInfo.indexOf(':');
                String user = (index != -1) ? authInfo.substring(0, index) :
                        authInfo;
                String password = (index != -1) ?
                        authInfo.substring(index + 1) : "";
                String domain;
                if ((index = user.indexOf('\\')) != -1 ||
                        (index = user.indexOf('/')) != -1) {
                    domain = user.substring(0, index);
                    user = user.substring(index + 1);
                } else {
                    domain = defaultDomain;
                }
                authentication = new NtlmPasswordAuthentication(domain, user,
                        password);
            }
            try {
                Log.log(Log.DEBUG, "Authenticating \"{0}\" against \"{1}\".",
                        new Object[] { authentication, server });
                SmbSession.logon(server, port, authentication);
                Log.log(Log.DEBUG, "Authentication succeeded.");
            } catch (SmbAuthException ex) {
                Log.log(Log.DEBUG, "Authentication failed: {0}", ex);
                fail(server, request, response);
                return;
            }
            HttpSession session = request.getSession();
            if (session != null) {
                Log.log(Log.DEBUG, "Obtained handle to session \"{0}\".",
                        session.getId());
                Map credentials = (Map)
                        session.getAttribute("davenport.credentials");
                if (credentials == null) {
                    Log.log(Log.DEBUG, "Created new credential cache.");
                    credentials = new Hashtable();
                    session.setAttribute("davenport.credentials", credentials);
                }
                credentials.put(server.getHostAddress(), authentication);
                Log.log(Log.DEBUG, "Cached Credentials for {0}: {1}",
                        new Object[] { server.getHostAddress(),
                                authentication });
                if (Log.getThreshold() < Log.INFORMATION) {
                    Log.log(Log.DEBUG, "Dumping credential cache:\n{0}",
                            credentials);
                }
            }
        } else if (alwaysAuthenticate && server != null) {
            Log.log(Log.DEBUG, "Searching Cache for credentials.");
            HttpSession session = request.getSession(false);
            if (session != null) {
                Log.log(Log.DEBUG, "Obtained handle to session \"{0}\".",
                        session.getId());
                Map credentials = (Map)
                        session.getAttribute("davenport.credentials");
                if (credentials != null) {
                    Log.log(Log.DEBUG, "Dumping credential cache:\n{0}",
                            credentials);
                    authentication = (NtlmPasswordAuthentication)
                            credentials.get(server.getHostAddress());
                    if (authentication != null) {
                        Log.log(Log.DEBUG,
                                "Found cached credentials for {0}: {1}",
                                        new Object[] { server.getHostAddress(),
                                                authentication });
                    } else {
                        Log.log(Log.DEBUG,
                                "No cached credentials found for {0}.",
                                        server.getHostAddress());
                    }
                }
            }
            if (authentication == null) {
                Log.log(Log.DEBUG, "No credentials obtained (required).");
                fail(null, request, response);
                return;
            }
            Log.log(Log.DEBUG, "Using credentials: " + authentication);
        }
        if (authentication == null) authentication = anonymousCredentials;
        Log.log(Log.DEBUG, "Final credentials: " + authentication);
        if (authentication != null) {
            request.setAttribute(PRINCIPAL, authentication);
        }
        MethodHandler handler = getHandler(request.getMethod());
        if (handler != null) {
            try {
                Log.log(Log.DEBUG, "Handler is {0}", handler.getClass());
                handler.service(request, response, authentication);
            } catch (Throwable throwable) {
                Log.log(Log.INFORMATION,
                        "Error handler chain invoked for: {0}", throwable);
                for (int i = 0; i < errorHandlers.length; i++) {
                    try {
                        Log.log(Log.DEBUG, "Error handler is {0}",
                                errorHandlers[i].getClass());
                        errorHandlers[i].handle(throwable, request, response);
                        Log.log(Log.DEBUG, "Error handler consumed throwable.");
                        return;
                    } catch (Throwable t) {
                        throwable = t;
                        if (throwable instanceof ErrorHandlerException) {
                            throwable = ((ErrorHandlerException)
                                    throwable).getThrowable();
                            Log.log(Log.DEBUG,
                                    "Error chain circumvented with: {0}",
                                            throwable);
                            break;
                        }
                        Log.log(Log.DEBUG, "Handler output: {0}", throwable);
                    }
                }
                Log.log(Log.INFORMATION, "Unhandled error: {0}", throwable);
                if (throwable instanceof SmbAuthException) {
                    fail((((SmbAuthException) throwable).getNtStatus() ==
                            NtStatus.NT_STATUS_ACCESS_VIOLATION) ? server :
                                    null, request, response);
                } else if (throwable instanceof ServletException) {
                    throw (ServletException) throwable;
                } else if (throwable instanceof IOException) {
                    throw (IOException) throwable;
                } else if (throwable instanceof RuntimeException) {
                    throw (RuntimeException) throwable;
                } else if (throwable instanceof Error) {
                    throw (Error) throwable;
                } else {
                    throw new ServletException(throwable);
                }
            }
        } else {
            Log.log(Log.INFORMATION, "Unrecognized method: " +
                    request.getMethod());
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        }
    }

    /**
     * Returns the <code>MethodHandler</code> for the specified method.
     *
     * @param method The HTTP method (GET, POST, PUT, etc.) being handled.
     * @return A <code>MethodHandler</code> capable of servicing the request
     * using the given method.
     */
    protected MethodHandler getHandler(String method) {
        if (method == null) return null;
        return (MethodHandler) handlers.get(method.toUpperCase());
    }

    private void initLockManager(ServletConfig config) throws ServletException {
        String factoryClass = LockManagerFactory.class.getName();
        String lockProvider = config.getInitParameter(factoryClass);
        if (lockProvider != null) {
            try {
                System.setProperty(factoryClass, lockProvider);
            } catch (Exception ignore) { }
        }
        try {
            LockManager lockManager = null;
            LockManagerFactory factory = LockManagerFactory.newInstance();
            if (factory != null) {
                Properties properties = new Properties();
                String prefix = factoryClass + ".";
                int prefixLength = prefix.length();
                Enumeration parameters = config.getInitParameterNames();
                while (parameters.hasMoreElements()) {
                    String param = (String) parameters.nextElement();
                    if (!param.startsWith(prefix)) continue;
                    String property = param.substring(prefixLength);
                    properties.setProperty(property,
                            config.getInitParameter(param));
                }
                if (Log.getThreshold() < Log.INFORMATION) {
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    properties.list(new PrintStream(stream));
                    Object[] args = new Object[] { factory.getClass(), stream };
                    Log.log(Log.DEBUG,
                            "Initializing lock manager factory {0}):\n{1}",
                                    args);
                }
                factory.setProperties(properties);
                lockManager = factory.newLockManager();
            }
            if (lockManager != null) {
                config.getServletContext().setAttribute(LOCK_MANAGER,
                        lockManager);
                Log.log(Log.DEBUG, "Installed lock manager: {0}",
                        lockManager.getClass().getName());
            } else {
                Log.log(Log.INFORMATION,
                        "No lock manager available, locking support disabled.");
            }
        } catch (Exception ex) {
            Log.log(Log.CRITICAL, "Unable to obtain lock manager: {0}", ex);
            if (ex instanceof ServletException) throw (ServletException) ex;
            if (ex instanceof RuntimeException) throw (RuntimeException) ex;
            throw new ServletException(ex);
        }
    }

    private void initHandlers(ServletConfig config) throws ServletException {
        handlers.clear();
        handlers.put("OPTIONS", new DefaultOptionsHandler());
        handlers.put("HEAD", new DefaultHeadHandler());
        handlers.put("GET", new DefaultGetHandler());
        handlers.put("POST", new DefaultPostHandler());
        handlers.put("DELETE", new DefaultDeleteHandler());
        handlers.put("PROPFIND", new DefaultPropfindHandler());
        handlers.put("PROPPATCH", new DefaultProppatchHandler());
        handlers.put("COPY", new DefaultCopyHandler());
        handlers.put("MOVE", new DefaultMoveHandler());
        handlers.put("PUT", new DefaultPutHandler());
        handlers.put("MKCOL", new DefaultMkcolHandler());
        if (config.getServletContext().getAttribute(LOCK_MANAGER) != null) {
            handlers.put("LOCK", new DefaultLockHandler());
            handlers.put("UNLOCK", new DefaultUnlockHandler());
        }
        Enumeration parameters = config.getInitParameterNames();
        while (parameters.hasMoreElements()) {
            String name = (String) parameters.nextElement();
            if (!name.startsWith("handler.")) continue;
            String method = name.substring(8);
            try {
                handlers.put(method.toUpperCase(), Class.forName(
                        config.getInitParameter(name)).newInstance());
                Log.log(Log.DEBUG, "Created handler for {0}: {1}",
                        new Object[] { method,
                                handlers.get(method.toUpperCase()) });
            } catch (Exception ex) {
                String message = SmbDAVUtilities.getResource(Davenport.class,
                        "cantCreateHandler", new Object[] { method, ex }, null);
                Log.log(Log.CRITICAL, message + "\n{0}", ex);
                throw new UnavailableException(message);
            }
        }
        Iterator iterator = handlers.values().iterator();
        while (iterator.hasNext()) {
            ((MethodHandler) iterator.next()).init(config);
        }
    }

    private void initErrorHandlers(ServletConfig config)
            throws ServletException {
        List errorHandlers = new ArrayList();
        String errorHandlerClasses = config.getInitParameter("errorHandlers");
        if (errorHandlerClasses == null) {
            errorHandlerClasses =
                "smbdav.DefaultAuthErrorHandler smbdav.DefaultIOErrorHandler";
        }
        StringTokenizer tokenizer = new StringTokenizer(errorHandlerClasses);
        while (tokenizer.hasMoreTokens()) {
            String errorHandler = tokenizer.nextToken();
            try {
                errorHandlers.add(Class.forName(errorHandler).newInstance());
                Log.log(Log.DEBUG, "Created error handler: " + errorHandler);
            } catch (Exception ex) {
                String message = SmbDAVUtilities.getResource(Davenport.class,
                        "cantCreateErrorHandler",
                                new Object[] { errorHandler, ex }, null);
                Log.log(Log.CRITICAL, message + "\n{0}", ex);
                throw new UnavailableException(message);
            }
        }
        Iterator iterator = errorHandlers.iterator();
        while (iterator.hasNext()) {
            ((ErrorHandler) iterator.next()).init(config);
        }
        this.errorHandlers = (ErrorHandler[])
                errorHandlers.toArray(new ErrorHandler[0]);
    }

    private void initFilter(ServletConfig config) throws ServletException {
        String fileFilters = config.getInitParameter("fileFilters");
        if (fileFilters == null) return;
        List filters = new ArrayList();
        StringTokenizer tokenizer = new StringTokenizer(fileFilters);
        while (tokenizer.hasMoreTokens()) {
            String filter = tokenizer.nextToken();
            try {
                SmbFileFilter fileFilter = (SmbFileFilter) Class.forName(
                        config.getInitParameter(filter)).newInstance();
                Log.log(Log.DEBUG, "Created filter {0}: {1}",
                        new Object[] { filter, fileFilter.getClass() });
                if (fileFilter instanceof DavenportFileFilter) {
                    Properties properties = new Properties();
                    Enumeration parameters = config.getInitParameterNames();
                    String prefix = filter + ".";
                    int prefixLength = prefix.length();
                    while (parameters.hasMoreElements()) {
                        String parameter = (String) parameters.nextElement();
                        if (parameter.startsWith(prefix)) {
                            properties.setProperty(
                                    parameter.substring(prefixLength),
                                            config.getInitParameter(parameter));
                        }
                    }
                    if (Log.getThreshold() < Log.INFORMATION) {
                        ByteArrayOutputStream stream =
                                new ByteArrayOutputStream();
                        properties.list(new PrintStream(stream));
                        Object[] args = new Object[] { filter,
                                fileFilter.getClass(), stream };
                        Log.log(Log.DEBUG,
                                "Initializing filter \"{0}\" ({1}):\n{2}",
                                        args);
                    }
                    ((DavenportFileFilter) fileFilter).init(properties);
                }
                filters.add(fileFilter);
            } catch (Exception ex) {
                String message = SmbDAVUtilities.getResource(Davenport.class,
                        "cantCreateFilter", new Object[] { filter, ex }, null);
                Log.log(Log.CRITICAL, message + "\n{0}", ex);
                throw new UnavailableException(message);
            }
        }
        if (!filters.isEmpty()) {
            this.filter = new ResourceFilter((SmbFileFilter[])
                    filters.toArray(new SmbFileFilter[0]));
            config.getServletContext().setAttribute(RESOURCE_FILTER,
                    this.filter);
            Log.log(Log.DEBUG, "Filter installed.");
        }
    }

    private int getPort(String target) throws IOException {
        try {
            SmbFile file = new SmbFile(target);
            if (file.getServer() == null) return DEFAULT_SMB_PORT;
            int port = file.getURL().getPort();
            return (port != -1) ? port : DEFAULT_SMB_PORT;
        } catch (IOException ex) {
            Log.log(Log.INFORMATION, "IO Exception occurred: {0}", ex);
            throw ex;
        } catch (Exception ex) {
            String message = SmbDAVUtilities.getResource(Davenport.class,
                    "unknownError", new Object[] { ex }, null);
            Log.log(Log.WARNING, message + "\n{0}", ex);
            throw new IOException(message);
        }
    }

    private UniAddress getServer(String target) throws IOException {
        try {
            SmbFile file = new SmbFile(target);
            String host = file.getServer();
            if (host == null) return defaultServer;
            file = new SmbFile(file, "/");
            try {
                return UniAddress.getByName(host,
                        (file.getType() == SmbFile.TYPE_WORKGROUP));
            } catch (UnknownHostException ex) {
                Log.log(Log.DEBUG, "Unable to locate \"{0}\", " +
                        "using default server \"{1}\".",
                                new Object[] { host, defaultServer });
                return defaultServer;
            }
        } catch (IOException ex) {
            Log.log(Log.INFORMATION, "IO Exception occurred: {0}", ex);
            throw ex;
        } catch (Exception ex) {
            String message = SmbDAVUtilities.getResource(Davenport.class,
                    "unknownError", new Object[] { ex }, null);
            Log.log(Log.WARNING, message + "\n{0}", ex);
            throw new IOException(message);
        }
    }

    private void fail(UniAddress server, HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        if (server != null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                Map credentials = (Map)
                        session.getAttribute("davenport.credentials");
                if (credentials != null) {
                    credentials.remove(server.getHostAddress());
                }
                Log.log(Log.DEBUG, "Removed credentials for \"{0}\".", server);
            }
        }
        try {
            response.reset();
        } catch (IllegalStateException ex) {
            Log.log(Log.DEBUG, "Unable to reset response (already committed).");
        }
        if (enableNtlm) {
            Log.log(Log.DEBUG, "Requesting NTLM Authentication.");
            response.setHeader("WWW-Authenticate", "NTLM");
        }
        boolean usingBasic = (acceptBasic || enableBasic) &&
                (insecureBasic || request.isSecure());
        if (usingBasic && enableBasic) {
            Log.log(Log.DEBUG, "Requesting Basic Authentication.");
            response.addHeader("WWW-Authenticate", "Basic realm=\"" + realm +
                    "\"");
        }
        if (closeOnAuthenticate) {
            Log.log(Log.DEBUG, "Closing HTTP connection.");
            response.setHeader("Connection", "close");
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.flushBuffer();
    }

    private static class ResourceFilter implements SmbFileFilter {

        private SmbFileFilter[] filters;

        public ResourceFilter(SmbFileFilter[] filters) {
            this.filters = filters;
        }

        public boolean accept(SmbFile file) throws SmbException {
            if (filters == null) return true;
            Log.log(Log.DEBUG, "Filtering file \"{0}\".", file);
            for (int i = 0; i < filters.length; i++) {
                if (!filters[i].accept(file)) {
                    Log.log(Log.DEBUG, "Filter rejected file \"{0}\". ({1})",
                            new Object[] { file, filters[i].getClass() });
                    return false;
                }
            }
            Log.log(Log.DEBUG, "Filter accepted file \"{0}\".", file);
            return true;
        }

        public void destroy() {
            if (filters == null) return;
            for (int i = filters.length - 1; i >= 0; i--) {
                try {
                    if (filters[i] instanceof DavenportFileFilter) {
                        ((DavenportFileFilter) filters[i]).destroy();
                    }
                } catch (Throwable t) { }
                filters[i] = null;
            }
            filters = null;
        }

    }

}
