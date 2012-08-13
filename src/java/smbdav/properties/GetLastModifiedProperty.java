/* Davenport WebDAV SMB Gateway
 * Copyright (C) 2003  Eric Glass
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

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import smbdav.AbstractProperty;
import smbdav.SmbDAVUtilities;

/**
 * Provides access to the <code>creationdate</code> property.
 * This implementation returns the resource's last modified date.
 *
 * @author Eric Glass
 */
public class GetLastModifiedProperty extends AbstractProperty {

    public Element createElement(Document document, SmbFile file)
            throws IOException {
        return (file.lastModified() == 0) ? null :
                super.createElement(document, file);
    }

    public int retrieve(SmbFile file, Element element)
            throws IOException {
        long modified = file.lastModified();
        if (modified == 0) return HttpServletResponse.SC_NOT_FOUND;
        element.setAttributeNS(WEB_FOLDERS_NAMESPACE, "w:dt",
                "dateTime.rfc1123");
        element.appendChild(element.getOwnerDocument().createTextNode(
                SmbDAVUtilities.formatGetLastModified(modified)));
        return HttpServletResponse.SC_OK;
    }

}
