/* Davenport WebDAV SMB Gateway
 * Copyright (C) 2004  Eric Glass
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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.StringReader;

import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerException;

import javax.xml.transform.dom.DOMSource;

import javax.xml.transform.stream.StreamResult;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;

import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.xml.sax.InputSource;

/**
 * Default implementation of a handler for requests using the WebDAV LOCK
 * method.
 *
 * @author Eric Glass
 */
public class DefaultLockHandler extends AbstractHandler {

    private long maximumXmlRequest;

    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        String maximumXmlRequest = config.getInitParameter("maximumXmlRequest");
        this.maximumXmlRequest = (maximumXmlRequest != null) ?
                Long.parseLong(maximumXmlRequest) : 20000l;
    }

    /**
     * Services requests which use the WebDAV LOCK method.
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
        if (getLockManager() == null) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    SmbDAVUtilities.getResource(DefaultLockHandler.class,
                            "noLockManager", null, request.getLocale()));
            return;
        }
        SmbFile file = getSmbFile(request, auth);
        Log.log(Log.DEBUG, "LOCK Request for resource \"{0}\".", file);
        int result = checkLockOwnership(request, file);
        if (result != HttpServletResponse.SC_OK) {
            response.sendError(result);
            return;
        }
        result = checkConditionalRequest(request, file);
        if (result != HttpServletResponse.SC_OK) {
            response.setStatus(result);
            response.flushBuffer();
            return;
        }
        StringBuffer body = new StringBuffer();
        String encoding = request.getCharacterEncoding();
        if (encoding == null) encoding = "ISO-8859-1";
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new LimitInputStream(request.getInputStream(),
                        maximumXmlRequest), encoding));
        String line;
        while ((line = reader.readLine()) != null) body.append(line);
        line = body.toString().trim();
        if (!"".equals(line)) {
            Log.log(Log.DEBUG, "Received LOCK request body:\n{0}", line);
            Document lockRequest = null;
            try {
                DocumentBuilderFactory factory =
                        DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setExpandEntityReferences(false);
                factory.setIgnoringComments(true);
                factory.setCoalescing(true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                builder.setEntityResolver(BlockedEntityResolver.INSTANCE);
                lockRequest = builder.parse(
                        new InputSource(new StringReader(line)));
            } catch (IOException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ServletException(ex);
            }
            doLock(file, lockRequest, request, response);
        } else {
            Log.log(Log.DEBUG,
                    "Received empty LOCK request body; lock refresh assumed.");
            doRefresh(file, request, response);
        }
        response.flushBuffer();
    }

    private void doLock(SmbFile resource, Document lockRequest,
            HttpServletRequest request, HttpServletResponse response)
                    throws ServletException, IOException {
        LockInfo lockInfo = new LockInfo();
        boolean exclusive = true;
        DocumentFragment owner = null;
        Node node = lockRequest.getDocumentElement().getFirstChild();
        do {
            if (!(node instanceof Element)) continue;
            String namespace = node.getNamespaceURI();
            String name = (namespace != null) ? node.getLocalName() :
                    node.getNodeName();
            if ("lockscope".equals(name)) {
                NodeList shared =((Element) node).getElementsByTagNameNS(
                        namespace, "shared");
                if (shared != null && shared.getLength() > 0) exclusive = false;
            } else if ("owner".equals(name)) {
                owner = node.getOwnerDocument().createDocumentFragment();
                while (node.hasChildNodes()) {
                    owner.appendChild(node.removeChild(node.getFirstChild()));
                }
            }
        } while ((node = node.getNextSibling()) != null);
        lockInfo.setExclusive(exclusive);
        lockInfo.setOwner(owner);
        lockInfo.setDepth(SmbDAVUtilities.parseDepth(
                request.getHeader("Depth")));
        lockInfo.setTimeout(SmbDAVUtilities.parseTimeout(
                request.getHeader("Timeout")));
        Document output = null;
        try {
            LockManager lockManager = getLockManager();
            String lockToken = lockManager.lock(resource,
                    getPrincipal(request), lockInfo);
            output = createDocument();
            Element prop = output.createElementNS(Property.DAV_NAMESPACE,
                    "prop");
            prop.setAttributeNS(Property.XMLNS_NAMESPACE, "xmlns",
                    Property.DAV_NAMESPACE);
            output.appendChild(prop);
            Element destination = output.createElementNS(Property.DAV_NAMESPACE,
                    "lockdiscovery");
            SmbDAVUtilities.lockDiscovery(resource, lockManager, destination);
            prop.appendChild(destination);
            response.setStatus(HttpServletResponse.SC_OK);
            if (lockToken != null) response.setHeader("Lock-Token", lockToken);
        } catch (LockException ex) {
            response.setStatus(ex.getStatus());
        }
        if (output != null) outputDocument(output, response);
    }

    private void doRefresh(SmbFile resource, HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        Document output = null;
        try {
            LockManager lockManager = getLockManager();
            lockManager.refresh(resource, getPrincipal(request),
                    parseLockTokens(request.getHeader("If")),
                            SmbDAVUtilities.parseTimeout(
                                    request.getHeader("Timeout")));
            output = createDocument();
            Element prop = output.createElementNS(Property.DAV_NAMESPACE,
                    "prop");
            prop.setAttributeNS(Property.XMLNS_NAMESPACE, "xmlns",
                    Property.DAV_NAMESPACE);
            output.appendChild(prop);
            Element destination = output.createElementNS(Property.DAV_NAMESPACE,
                    "lockdiscovery");
            SmbDAVUtilities.lockDiscovery(resource, lockManager, destination);
            prop.appendChild(destination);
            response.setStatus(HttpServletResponse.SC_OK);
        } catch (IllegalStateException ex) {
            Log.log(Log.INFORMATION, "Error parsing lock tokens from the " +
                    "client's \"If\" header: {0}", ex);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        } catch (LockException ex) {
            response.setStatus(ex.getStatus());
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
        if (output != null) outputDocument(output, response);
    }

    private void outputDocument(Document output, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            Transformer transformer =
                    TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty("encoding", "UTF-8");
            ByteArrayOutputStream collector = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(output),
                    new StreamResult(collector));
            if (Log.getThreshold() < Log.INFORMATION) {
                Log.log(Log.DEBUG, "LOCK response body:\n{0}",
                        collector.toString("UTF-8"));
            }
            response.setContentType("text/xml; charset=\"utf-8\"");
            collector.writeTo(response.getOutputStream());
        } catch (TransformerException ex) {
            throw new IOException(ex.getMessage());
        }
    }

    private Document createDocument() throws ServletException {
        try {
            DocumentBuilderFactory builderFactory =
                    DocumentBuilderFactory.newInstance();
            builderFactory.setNamespaceAware(true);
            builderFactory.setExpandEntityReferences(false);
            DocumentBuilder builder = builderFactory.newDocumentBuilder();
            builder.setEntityResolver(BlockedEntityResolver.INSTANCE);
            Document document = builder.newDocument();
            return document;
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
    }

    private String[] parseLockTokens(String header) {
        boolean inQuote = false;
        boolean inLockToken = false;
        boolean inList = false;
        StringBuffer lockToken = null;
        Set lockTokens = new HashSet();
        StringTokenizer tokenizer = new StringTokenizer(header, "()<> \"",
                true);
        String token;
        while (tokenizer.hasMoreTokens()) {
            token = tokenizer.nextToken();
            if ("\"".equals(token)) {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) continue;
            if (" ".equals(token)) {
            } else if ("(".equals(token)) {
                if (inList) {
                    Log.log(Log.DEBUG, "( token encountered inside List.");
                    throw new IllegalStateException();
                }
                inList = true;
            } else if (")".equals(token)) {
                if (!inList) {
                    Log.log(Log.DEBUG, ") token encountered outside List.");
                    throw new IllegalStateException();
                }
                inList = false;
            } else if ("<".equals(token)) {
                if (!inList) continue;
                if (inLockToken) {
                    Log.log(Log.DEBUG, "< token encountered inside LockToken.");
                    throw new IllegalStateException();
                }
                inLockToken = true;
                if (lockToken == null) {
                    lockToken = new StringBuffer();
                } else {
                    lockToken.setLength(0);
                }
            } else if (">".equals(token)) {
                if (!inList) continue;
                if (!inLockToken) {
                    Log.log(Log.DEBUG,
                            "> token encountered outside LockToken.");
                    throw new IllegalStateException();
                }
                inLockToken = false;
                lockTokens.add(lockToken.toString());
            } else if (inLockToken) {
                lockToken.append(token);
            }
        }
        return (String[]) lockTokens.toArray(new String[0]);
    }

}
