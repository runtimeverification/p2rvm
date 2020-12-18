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
package bitronix.tm.resource.ehcache;

import bitronix.tm.resource.common.AbstractXAResourceHolder;
import bitronix.tm.resource.common.ResourceBean;

import javax.transaction.xa.XAResource;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Ehcache implementation of BTM's XAResourceHolder.
 * <p>
 *   Copyright 2003-2010 Terracotta, Inc.
 * </p>
 * @author Ludovic Orban
 */
public class EhCacheXAResourceHolder extends AbstractXAResourceHolder<EhCacheXAResourceHolder> {

    private final XAResource resource;
    private final ResourceBean bean;

    /**
     * Create a new EhCacheXAResourceHolder for a particular XAResource
     * @param resource the required XAResource
     * @param bean the required ResourceBean
     */
    public EhCacheXAResourceHolder(XAResource resource, ResourceBean bean) {
        this.resource = resource;
        this.bean = bean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XAResource getXAResource() {
        return resource;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResourceBean getResourceBean() {
        return bean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws Exception {
        throw new UnsupportedOperationException("EhCacheXAResourceHolder cannot be used with an XAPool");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getConnectionHandle() throws Exception {
        throw new UnsupportedOperationException("EhCacheXAResourceHolder cannot be used with an XAPool");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Date getLastReleaseDate() {
        throw new UnsupportedOperationException("EhCacheXAResourceHolder cannot be used with an XAPool");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<EhCacheXAResourceHolder> getXAResourceHolders() {
        return Collections.singletonList(this);
    }

    public EhCacheXAResourceHolder getXAResourceHolderForXaResource(XAResource xaResource) {
        if (xaResource == resource) {
            return this;
        }
        return null;
    }
}
