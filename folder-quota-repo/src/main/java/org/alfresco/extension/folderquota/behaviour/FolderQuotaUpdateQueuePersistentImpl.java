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
 * Persistent implementation of the folder quota queue
 * 
 * @author Rich McKnight
 * @author Nathan McMminn
 * @author Maurizio Pillitu
 */
package org.alfresco.extension.folderquota.behaviour;

import java.io.Serializable;

import org.alfresco.extension.folderquota.FolderQuotaConstants;
import org.alfresco.extension.folderquota.SizeChange;
import org.alfresco.service.cmr.attributes.AttributeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;

public class FolderQuotaUpdateQueuePersistentImpl implements FolderQuotaUpdateQueue, AttributeService.AttributeQueryCallback {
	private NodeService nodeService;
	private AttributeService attributeService;
	private final static String FOLDER_QUOTA_UPDATE_NAME_SPACE  = "..FOLDER_QUOTA_UPDATE..";
	
	static class IndexItem {
		long startTime;
		long count;
		IndexItem() {
			startTime = java.util.Calendar.getInstance().getTimeInMillis();
			count = 0;
		}
		synchronized long getNext() {
			return ++count;
		}
	}

	private static IndexItem indexItem = new IndexItem();

	public void enqueueEvent(NodeRef nodeWithQuota, long sizeChange) {
		//This will throw an error if we have an unlikely duplicate key
		attributeService.createAttribute(nodeWithQuota, FOLDER_QUOTA_UPDATE_NAME_SPACE,indexItem.getNext());
	}
	
	public void processAllEvents() {
		//TODO: set a limit
		attributeService.getAttributes(this, FOLDER_QUOTA_UPDATE_NAME_SPACE);
	}
	public void setNodeService(NodeService nodeService) {
		this.nodeService = nodeService;
	}

	public boolean handleAttribute(Long id, Serializable value,	Serializable[] keys) {
		SizeChange qi = (SizeChange) value;
		long currentSize = (Long) nodeService.getProperty(qi.nodeRef, FolderQuotaConstants.PROP_FQ_SIZE_CURRENT);
		nodeService.setProperty(qi.nodeRef, FolderQuotaConstants.PROP_FQ_SIZE_CURRENT, currentSize+qi.sizeChange);
		attributeService.removeAttribute(id);
		return true;
	}

	public void enqueueEvent(SizeChange change) {
		enqueueEvent(change.nodeRef, change.sizeChange);
	}


}
