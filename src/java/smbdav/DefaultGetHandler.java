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
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.URL;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerException;

import javax.xml.transform.dom.DOMSource;

import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

import org.w3c.dom.Document;

/**
 * Default implementation of a handler for requests using the HTTP GET
 * method.
 * <p>
 * In addition to providing standard GET functionality for resources,
 * this implementation provides directory listings for collections.
 * An XSL stylesheet can be specified to customize the appearance of
 * the listing.  The default stylesheet location is provided in the Davenport
 * servlet's deployment descriptor as the "directory.xsl" initialization
 * parameter, i.e.:
 * <p>
 * <pre>
 * &lt;init-param&gt;
 *     &lt;param-name&gt;directory.xsl&lt;/param-name&gt;
 *     &lt;param-value&gt;/mydir.xsl&lt;/param-value&gt;
 * &lt;/init-param&gt;
 * </pre>
 * <p>
 * The stylesheet location is resolved as follows:
 * <ul>
 * <li>
 * First, the system will look for the stylesheet as a servlet context resource
 * (via <code>ServletContext.getResourceAsStream()</code>).
 * </li>
 * <li>
 * Next, the system will attempt to load the stylesheet as a classloader
 * resource (via <code>ClassLoader.getResourceAsStream()</code>), using the
 * Davenport classloader, the thread context classloader, and the system
 * classloader (in that order).
 * </li>
 * <li>
 * Finally, the system will attempt to load the stylesheet directly.
 * This will only succeed if the location is specified as an absolute URL.
 * </li>
 * </ul>
 * <p>
 * If not specified, this is set to "<code>/META-INF/directory.xsl</code>",
 * which will load a default stylesheet from the Davenport jarfile.
 * <p>
 * Users can also configure their own directory stylesheets.  The
 * configuration page can be accessed by pointing your web browser
 * at any Davenport collection resource and passing "configure" as
 * a URL parameter:
 * </p>
 * <p>
 * <code>http://server/davenport/any/?configure</code>
 * </p>
 * <p>
 * The configuration page can be specified in the deployment descriptor
 * via the "directory.configuration" initialization parameter, i.e.:
 * <p>
 * <pre>
 * &lt;init-param&gt;
 *     &lt;param-name&gt;directory.configuration&lt;/param-name&gt;
 *     &lt;param-value&gt;/configuration.html&lt;/param-value&gt;
 * &lt;/init-param&gt;
 * </pre>
 * <p>
 * The configuration page's location is resolved in the same manner as the
 * default stylesheet described above.
 * <p>
 * If not specified, this is set to "<code>/META-INF/configuration.html</code>",
 * which will load and cache a default configuration page from the
 * Davenport jarfile.
 * <p>
 * Both the stylesheet and configuration page will attempt to load a resource
 * appropriate to the locale; the loading order is similar to that used by
 * resource bundles, i.e.:
 * <p>
 * directory_en_US.xsl
 * <br> 
 * directory_en.xsl
 * <br> 
 * directory.xsl
 * <p>
 * The client's locale will be tried first, followed by the server's locale. 
 * 
 * @author Eric Glass
 */
public class DefaultGetHandler extends AbstractHandler {

    private static final Timer TIMER = new Timer(true);

    private final Map templateMap = new HashMap();

    private final Map defaultTemplates = new HashMap();

    private final Map configurations = new HashMap();

    private String stylesheetLocation;

    private String configurationLocation;

    private PropertiesBuilder propertiesBuilder;

    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        propertiesBuilder = new DefaultPropertiesBuilder();
        propertiesBuilder.init(config);
        stylesheetLocation = config.getInitParameter("directory.xsl");
        if (stylesheetLocation == null) {
            stylesheetLocation = "/META-INF/directory.xsl";
        }
        configurationLocation =
                config.getInitParameter("directory.configuration");
        if (configurationLocation == null) {
            configurationLocation = "/META-INF/configuration.html";
        }
    }

    public void destroy() {
        propertiesBuilder.destroy();
        propertiesBuilder = null;
        stylesheetLocation = null;
        synchronized (defaultTemplates) {
            defaultTemplates.clear();
        }
        synchronized (configurations) {
            configurations.clear();
        }
        super.destroy();
    }

    /**
     * Services requests which use the HTTP GET method.
     * This implementation retrieves the content for non-collection resources,
     * using the content type information mapped in
     * {@link smbdav.SmbDAVUtilities}.  For collection resources, the
     * collection listing is retrieved as from a PROPFIND request with
     * a depth of 1 (the collection and its immediate contents).  The
     * directory listing stylesheet is applied to the resultant XML
     * document.
     * <br>
     * If the specified file does not exist, a 404 (Not Found) error is
     * sent to the client.
     *
     * @param request The request being serviced.
     * @param response The servlet response.
     * @param auth The user's authentication information.
     * @throws ServletException If an application error occurs.
     * @throws IOException If an IO error occurs while handling the request.
     *
     */
    public void service(HttpServletRequest request,
            HttpServletResponse response, NtlmPasswordAuthentication auth)
                    throws ServletException, IOException {
        SmbFile file = getSmbFile(request, auth);
        Log.log(Log.DEBUG, "GET Request for resource \"{0}\".", file);
        if (!file.exists()) {
            Log.log(Log.DEBUG, "File does not exist.");
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String requestUrl = getRequestURL(request);
        Log.log(Log.DEBUG, "Request URL: {0}", requestUrl);
        if (file.getName().endsWith("/") && !requestUrl.endsWith("/")) {
            StringBuffer redirect = new StringBuffer(requestUrl).append("/");
            String query = request.getQueryString();
            if (query != null) redirect.append("?").append(query);
            Log.log(Log.DEBUG, "Redirecting to \"{0}\".", redirect);
            response.sendRedirect(redirect.toString());
            return;
        }
        if (!file.isFile()) {
            if ("configure".equals(request.getQueryString())) {
                Log.log(Log.INFORMATION, "Configuration request received.");
                showConfiguration(request, response);
                return;
            }
            String view = request.getParameter("view");
            if (view == null) {
                Cookie[] cookies = request.getCookies();
                if (cookies != null) {
                    for (int i = cookies.length - 1; i >= 0; i--) {
                        if (cookies[i].getName().equals("view")) {
                            view = cookies[i].getValue();
                            break;
                        }
                    }
                }
            } else {
                view = view.trim();
                Cookie cookie = new Cookie("view", view);
                cookie.setPath("/");
                if (view.equals("")) {
                    view = null;
                    HttpSession session = request.getSession(false);
                    if (session != null) clearTemplates(session);
                    cookie.setMaxAge(0);
                } else {
                    cookie.setMaxAge(Integer.MAX_VALUE);
                }
                response.addCookie(cookie);
            }
            Locale locale = request.getLocale();
            Templates templates = getDefaultTemplates(locale);
            if (view != null) {
                Log.log(Log.DEBUG, "Custom view installed: {0}", view);
                templates = null;
                try {
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        templates = getTemplates(session);
                    }
                    if (templates == null) {
                        Source source = getStylesheet(view, false, locale);
                        templates = TransformerFactory.newInstance(
                                ).newTemplates(source);
                        if (session == null) session = request.getSession(true);
                        setTemplates(session, templates);
                    }
                } catch (Exception ex) {
                    Log.log(Log.WARNING, "Unable to install stylesheet: {0}",
                            ex);
                    HttpSession session = request.getSession(false);
                    if (session != null) clearTemplates(session);
                    showConfiguration(request, response);
                    return;
                }
            }
            PropertiesDirector director = new PropertiesDirector(
                    getPropertiesBuilder(), getFilter());
            Document properties = null;
            properties = director.getAllProperties(file, requestUrl, 1);
            try {
                Transformer transformer = templates.newTransformer();
                transformer.setParameter("href", requestUrl);
                transformer.setParameter("url", file.toString());
                transformer.setParameter("unc", file.getUncPath());
                String type;
                switch (file.getType()) {
                case SmbFile.TYPE_WORKGROUP:
                    type = "TYPE_WORKGROUP";
                    break;
                case SmbFile.TYPE_SERVER:
                    type = "TYPE_SERVER";
                    break;
                case SmbFile.TYPE_SHARE:
                    type = "TYPE_SHARE";
                    break;
                case SmbFile.TYPE_FILESYSTEM:
                    type = "TYPE_FILESYSTEM";
                    break;
                case SmbFile.TYPE_PRINTER:
                    type = "TYPE_PRINTER";
                    break;
                case SmbFile.TYPE_NAMED_PIPE:
                    type = "TYPE_NAMED_PIPE";
                    break;
                case SmbFile.TYPE_COMM:
                    type = "TYPE_COMM";
                    break;
                default:
                    type = "TYPE_UNKNOWN";
                }
                transformer.setParameter("type", type);
                transformer.setOutputProperty("encoding", "UTF-8");
                ByteArrayOutputStream collector = new ByteArrayOutputStream();
                transformer.transform(new DOMSource(properties),
                        new StreamResult(collector));
                response.setContentType("text/html; charset=\"utf-8\"");
                collector.writeTo(response.getOutputStream());
                response.flushBuffer();
            } catch (TransformerException ex) {
                throw new IOException(ex.getMessage());
            }
            return;
        }
        String etag = SmbDAVUtilities.getETag(file);
        if (etag != null) response.setHeader("ETag", etag);
        long modified = file.lastModified();
        if (modified != 0) {
            response.setHeader("Last-Modified",
                    SmbDAVUtilities.formatGetLastModified(modified));
        }
        int result = checkConditionalRequest(request, file);
        if (result != HttpServletResponse.SC_OK) {
            response.setStatus(result);
            response.flushBuffer();
            return;
        }
        String contentType = getServletConfig().getServletContext().getMimeType(
                file.getName());
        response.setContentType((contentType != null) ? contentType :
                "application/octet-stream");
        response.setContentLength((int) file.length());
        SmbFileInputStream input = new SmbFileInputStream(file);
        ServletOutputStream output = response.getOutputStream();
        byte[] buf = new byte[8192];
        int count;
        while ((count = input.read(buf)) != -1) {
            output.write(buf, 0, count);
        }
        output.flush();
        input.close();
    }

    /**
     * Returns the <code>PropertiesBuilder</code> that will be used
     * to build the PROPFIND result XML document for directory listings.
     *
     * @return The <code>PropertiesBuilder</code> used to build the
     * XML document.
     */
    protected PropertiesBuilder getPropertiesBuilder() {
        return propertiesBuilder;
    }

    private void showConfiguration(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/html");
        OutputStream output = response.getOutputStream();
        output.write(getConfiguration(request.getLocale()));
        response.flushBuffer();
    }

    private byte[] getConfiguration(Locale locale)
            throws ServletException, IOException {
        synchronized (configurations) {
            byte[] configuration = (byte[]) configurations.get(locale);
            if (configuration != null) return configuration;
            InputStream stream = getResourceAsStream(configurationLocation,
                    locale);
            if (stream == null) {
                throw new ServletException(SmbDAVUtilities.getResource(
                        DefaultGetHandler.class, "configurationPageError",
                                null, null));
            }
            ByteArrayOutputStream collector = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = stream.read(buffer, 0, 2048)) != -1) {
                collector.write(buffer, 0, count);
            }
            configuration = collector.toByteArray();
            configurations.put(locale, configuration);
            return configuration;
        }
    }

    private Templates getTemplates(HttpSession session) throws ServletException,
            IOException {
        String id = session.getId();
        TemplateTracker tracker;
        synchronized (templateMap) {
            tracker = (TemplateTracker) templateMap.get(id);
        }
        if (tracker == null) return null;
        Log.log(Log.DEBUG, "Retrieved precompiled stylesheet.");
        return tracker.getTemplates();
    }

    private void clearTemplates(HttpSession session)
            throws ServletException, IOException {
        String id = session.getId();
        TemplateTracker tracker;
        synchronized (templateMap) {
            tracker = (TemplateTracker) templateMap.remove(id);
        }
        if (tracker != null) {
            Log.log(Log.DEBUG, "Removing precompiled stylesheet.");
            tracker.cancel();
        }
    }

    private void setTemplates(HttpSession session, Templates templates)
            throws ServletException, IOException {
        String id = session.getId();
        long cacheTime = (long) session.getMaxInactiveInterval() * 1000;
        Log.log(Log.DEBUG, "Storing precompiled stylesheet.");
        synchronized (templateMap) {
            templateMap.put(id, new TemplateTracker(id, templates, cacheTime));
        }
    }

    private Templates getDefaultTemplates(Locale locale)
            throws ServletException, IOException {
        synchronized (defaultTemplates) {
            Templates templates = (Templates) defaultTemplates.get(locale);
            if (templates != null) return templates;
            try {
                Source source = getStylesheet(stylesheetLocation, true, locale);
                templates = TransformerFactory.newInstance().newTemplates(
                        source);
                defaultTemplates.put(locale, templates);
                return templates;
            } catch (Exception ex) {
                throw new ServletException(SmbDAVUtilities.getResource(
                        DefaultGetHandler.class, "stylesheetError", null,
                                null));
            }
        }
    }

    private InputStream getResourceAsStream(String location, Locale locale) {
        int index = location.indexOf('.');
        String prefix = (index != -1) ? location.substring(0, index) :
                location;
        String suffix = (index != -1) ? location.substring(index) : "";
        String language = locale.getLanguage();
        String country = locale.getCountry();
        String variant = locale.getVariant();
        InputStream stream = null;
        if (!variant.equals("")) {
            stream = getResourceAsStream(prefix + '_' + language + '_' +
                    country + '_' + variant + suffix);
            if (stream != null) return stream;
        }
        if (!country.equals("")) {
            stream = getResourceAsStream(prefix + '_' + language + '_' +
                    country + suffix);
            if (stream != null) return stream;
        }
        stream = getResourceAsStream(prefix + '_' + language + suffix);
        if (stream != null) return stream;
        Locale secondary = Locale.getDefault();
        if (!locale.equals(secondary)) {
            language = secondary.getLanguage();
            country = secondary.getCountry();
            variant = secondary.getVariant();
            if (!variant.equals("")) {
                stream = getResourceAsStream(prefix + '_' + language + '_' +
                        country + '_' + variant + suffix);
                if (stream != null) return stream;
            }
            if (!country.equals("")) {
                stream = getResourceAsStream(prefix + '_' + language + '_' +
                        country + suffix);
                if (stream != null) return stream;
            }
            stream = getResourceAsStream(prefix + '_' + language + suffix);
            if (stream != null) return stream;
        }
        return getResourceAsStream(location);
    }

    private InputStream getResourceAsStream(String location) {
        InputStream stream = null;
        try {
            stream = getServletConfig().getServletContext(
                    ).getResourceAsStream(location);
            if (stream != null) return stream;
        } catch (Exception ex) { }
        try {
            stream = getClass().getResourceAsStream(location);
            if (stream != null) return stream;
        } catch (Exception ex) { }
        try {
            ClassLoader loader = Thread.currentThread(
                    ).getContextClassLoader();
            if (loader != null) stream = loader.getResourceAsStream(location);
            if (stream != null) return stream;
        } catch (Exception ex) { }
        try {
            ClassLoader loader = ClassLoader.getSystemClassLoader();
            if (loader != null) stream = loader.getResourceAsStream(location);
            if (stream != null) return stream;
        } catch (Exception ex) { }
        return null;
    }

    private Source getStylesheet(String location, boolean allowExternal,
            Locale locale) throws Exception {
        InputStream stream = getResourceAsStream(location, locale);
        if (stream != null) {
            Log.log(Log.DEBUG, "Obtained stylesheet for \"{0}\".", location);
            return new StreamSource(stream);
        }
        if (!allowExternal) {
            throw new IllegalArgumentException(SmbDAVUtilities.getResource(
                    DefaultGetHandler.class, "stylesheetNotFound",
                            new Object[] { location }, null));
        }
        Log.log(Log.DEBUG, "Using external stylesheet at \"{0}\".", location);
        return new StreamSource(location);
    }

    private class TemplateTracker extends TimerTask {

        private final Templates templates;

        private final String id;

        public TemplateTracker(String id, Templates templates, long cacheTime) {
            this.templates = templates;
            this.id = id;
            TIMER.schedule(this, cacheTime);
        }

        public void run() {
            Log.log(Log.DEBUG, "Removing cached stylesheet for session {0}",
                    id);
            synchronized (templateMap) {
                templateMap.remove(id);
            }
        }

        public Templates getTemplates() {
            return templates;
        }

    }

}
