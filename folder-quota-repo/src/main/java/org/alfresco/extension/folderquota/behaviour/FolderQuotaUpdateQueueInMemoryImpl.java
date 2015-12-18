/*
 * Copyright 2012 Alfresco Software Limited.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This file is part of an unsupported extension to Alfresco.
 * 
 * In-memory queue.  If the system is shut down or halted with jobs in the queue the jobs 
 * will be lost and reported folder usages may be inconsistent ith reality.
 * 
 * @author Rich McKnight
 * @author Nathan McMminn
 * @author Maurizio Pillitu
 */
package org.alfresco.extension.folderquota.behaviour;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.alfresco.extension.folderquota.FolderQuotaConstants;
import org.alfresco.extension.folderquota.SizeChange;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.apache.log4j.Logger;

public class FolderQuotaUpdateQueueInMemoryImpl implements FolderQuotaUpdateQueue {
    private static Logger logger = Logger.getLogger(FolderQuotaBehaviour.class.getName());
	private NodeService nodeService;

	private static Queue<SizeChange> queue = new ConcurrentLinkedQueue<SizeChange> ();

	public void enqueueEvent(NodeRef nodeWithQuota, long sizeChange) {
		queue.add(new SizeChange(nodeWithQuota,sizeChange));
	}

	public void processAllEvents() {
		//TODO: set a limit
		SizeChange qi = null;
		while((qi = queue.poll()) != null) {
			Long currentSize = (Long) nodeService.getProperty(qi.nodeRef, FolderQuotaConstants.PROP_FQ_SIZE_CURRENT);
            if (currentSize == null)  {
                currentSize=0L;
            }
            Long newSize = currentSize+qi.sizeChange;
			nodeService.setProperty(qi.nodeRef, FolderQuotaConstants.PROP_FQ_SIZE_CURRENT, newSize);
            logger.debug(String.format("Property %s set to %s",FolderQuotaConstants.PROP_FQ_SIZE_CURRENT, newSize));
		}
	}
	public void setNodeService(NodeService nodeService) {
		logger.debug("Setting Node Service");
		this.nodeService = nodeService;
	}

	public void enqueueEvent(SizeChange change) {
		queue.add(change);
	}
}
