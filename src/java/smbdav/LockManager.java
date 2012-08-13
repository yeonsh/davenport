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

import java.io.IOException;

import java.security.Principal;

import jcifs.smb.SmbFile;

/**
 * This is the interface that must be implemented by lock management providers.
 * This provides the core operations used by Davenport to coordinate
 * locking of resources.  A provider would implement this interface and
 * install a subclass of <code>LockManagerFactory</code> to create instances
 * of the implementation.
 *
 * @author Eric Glass
 */
public interface LockManager {

    /**
     * Indicates that no locking support is provided for a given resource.
     */
    public static final int NO_LOCK_SUPPORT = 0;

    /**
     * Indicates that exclusive locking is supported for a given resource.
     */
    public static final int EXCLUSIVE_LOCK_SUPPORT = 1;

    /**
     * Indicates that shared locking is supported for a given resource.
     */
    public static final int SHARED_LOCK_SUPPORT = 2;

    /**
     * Retrieves the lock support mask for a specified resource.
     *
     * @param resource The resource for which lock support is being
     * inspected.
     * @return An <code>int</code> representing the supported locks for
     * the specified resource.  This value is the exclusive-OR of all
     * supported lock types.
     * @throws IOException If an IO error occurs. 
     */
    public int getLockSupport(SmbFile resource) throws IOException;

    /**
     * Indicates whether the specified resource is locked under the provided
     * lock token.
     *
     * @param resource The resource.
     * @param lockToken The lock token.
     * @return A <code>boolean</code> indicating whether the lock token
     * provided represents an active lock on the specified resource.
     * @throws IOException If an IO error occurs. 
     */
    public boolean isLocked(SmbFile resource, String lockToken)
            throws IOException;

    /**
     * Returns the set of active locks on the specified resource.
     *
     * @param resource The resource.
     * @return A <code>Lock[]</code> representing the set of all current
     * active locks held on the specified resource.
     * @throws IOException If an IO error occurs. 
     */
    public Lock[] getActiveLocks(SmbFile resource) throws IOException;

    /**
     * Returns a handle for manipulating a locked SMB resource.  A manager
     * enforcing locks at the SMB level will return a
     * singleton <code>SmbFile</code> instance for performing operations
     * against the resource.  If such management is not required, this method
     * returns the resource passed in by the caller.
     *
     * @param resource The resource for which the lock instance is to be
     * obtained.
     * @param principal The requesting principal.
     * @return The <code>SmbFile</code> object used to perform
     * write and delete operations on the resource.  If no special
     * resource management is required by this manager, this method returns
     * the resource passed in by the caller.
     * @throws IOException If an IO error occurs.
     */
    public SmbFile getLockedResource(SmbFile resource, Principal principal)
            throws IOException;

    /**
     * Locks the specified resource, using the provided lock information.
     *
     * @param resource The resource that will be locked.
     * @param principal The principal requesting the lock.
     * @param lockInfo Information regarding the lock that is to be applied.
     * @return A <code>String</code> containing the lock token that
     * was created as a result of this operation.
     * @throws LockException If the lock could not be created.
     * @throws IOException If an IO error occurs.
     */
    public String lock(SmbFile resource, Principal principal, LockInfo lockInfo)
            throws LockException, IOException;

    /**
     * Refreshes the locks represented by the provided lock tokens on the
     * specified resource.
     *
     * @param resource The resource whose locks will be refreshed.
     * @param principal The principal requesting the lock refresh. 
     * @param lockTokens The set of lock tokens for the locks that are to
     * be refreshed.
     * @param timeout The requested lock timeout value.  This is a value in
     * milliseconds, or one of
     * <code>SmbDAVUtilities.UNSPECIFIED_TIMEOUT</code> (if no timeout is
     * specified) or
     * <code>SmbDAVUtilities.INFINITE_TIMEOUT</code> (if an infinite timeout
     * is requested).
     * @throws LockException If the locks could not be refreshed.
     * @throws IOException If an IO error occurs.
     */
    public void refresh(SmbFile resource, Principal principal,
            String[] lockTokens, long timeout) throws LockException,
                    IOException;

    /**
     * Removes the lock on the specified resource represented by the
     * provided lock token.
     *
     * @param resource The resource whose lock is to be removed.
     * @param principal The principal requesting the lock removal. 
     * @param lockToken The lock token representing the lock that is
     * to be removed.
     * @throws LockException If the lock could not be removed.
     * @throws IOException If an IO error occurs.
     */
    public void unlock(SmbFile resource, Principal principal, String lockToken)
            throws LockException, IOException;

}
