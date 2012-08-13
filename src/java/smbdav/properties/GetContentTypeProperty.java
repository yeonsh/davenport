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

package smbdav.properties;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import jcifs.smb.SmbFile;

import org.w3c.dom.Element;

import smbdav.AbstractProperty;

/**
 * Provides access to the <code>getcontenttype</code> property.
 * This implementation returns the content type of the resource
 * as set by the deployment descriptor.
 *
 * @author Eric Glass
 */
public class GetContentTypeProperty extends AbstractProperty {

    public int retrieve(SmbFile file, Element element)
            throws IOException {
        String contentType;
        if (file.isDirectory()) {
            contentType = "httpd/unix-directory";
        } else {
            contentType = getServletConfig().getServletContext().getMimeType(
                    file.getName());
            if (contentType == null) contentType = "application/octet-stream";
        }
        element.appendChild(element.getOwnerDocument().createTextNode(
                contentType));
        return HttpServletResponse.SC_OK;
    }

}
