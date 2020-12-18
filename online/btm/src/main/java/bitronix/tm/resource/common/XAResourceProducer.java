/*
 * Copyright (C) 2006-2013 Bitronix Software (http://www.bitronix.be)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bitronix.tm.resource.common;

import bitronix.tm.internal.XAResourceHolderState;
import bitronix.tm.recovery.RecoveryException;

import javax.naming.Referenceable;
import javax.transaction.xa.XAResource;
import java.io.Serializable;

/**
 * A {@link XAResourceProducer} is a {@link XAStatefulHolder} factory. It must be implemented by any class that is
 * able to produce pooled XA connections.
 *
 * @author Ludovic Orban
 */
public interface XAResourceProducer<R extends XAResourceHolder<R>, T extends XAStatefulHolder<T>> extends Referenceable, Serializable {

    /**
     * Get the resource name as registered in the transactions journal.
     * @return the unique name of the resource.
     */
    public String getUniqueName();

    /**
     * Prepare the recoverable {@link XAResource} producer for recovery.
     * @return a {@link XAResourceHolderState} object that can be used to call <code>recover()</code>.
     * @throws bitronix.tm.recovery.RecoveryException thrown when a {@link XAResourceHolderState} cannot be acquired.
     */
    public XAResourceHolderState startRecovery() throws RecoveryException;

    /**
     * Release internal resources held after call to <code>startRecovery()</code>.
     * @throws bitronix.tm.recovery.RecoveryException thrown when an error occurred while releasing reserved resources.
     */
    public void endRecovery() throws RecoveryException;

    /**
     * Mark this resource producer as failed or not. A resource is considered failed if recovery fails to run on it.
     * @param failed true is the resource must be considered failed, false it it must be considered sane.
     */
    public void setFailed(boolean failed);

    /**
     * Find in the {@link XAResourceHolder}s created by this {@link XAResourceProducer} the one which this
     * {@link XAResource} belongs to.
     * @param xaResource the {@link XAResource} to look for.
     * @return the associated {@link XAResourceHolder} or null if the {@link XAResource} does not belong to this
     *         {@link XAResourceProducer}.
     */
    public R findXAResourceHolder(XAResource xaResource);

    /**
     * Initialize this {@link XAResourceProducer}'s internal resources.
     */
    public void init();

    /**
     * Release this {@link XAResourceProducer}'s internal resources.
     */
    public void close();

    /**
     * Create a {@link XAStatefulHolder} that will be placed in an {@link XAPool}.
     * @param xaFactory the vendor's resource-specific XA factory.
     * @param bean the resource-specific bean describing the resource parameters.
     * @return a {@link XAStatefulHolder} that will be placed in an {@link XAPool}.
     * @throws Exception thrown when the {@link XAStatefulHolder} cannot be created.
     */
    public T createPooledConnection(Object xaFactory, ResourceBean bean) throws Exception;

}
