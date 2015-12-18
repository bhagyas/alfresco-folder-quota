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
 * Class to handle the calculation and updating of quota folder usage
 * 
 * @author Rich McKnight
 * @author Nathan McMminn
 * @author Maurizio Pillitu
 */
package org.alfresco.extension.folderquota;

import java.util.Iterator;
import java.util.List;

import javax.transaction.UserTransaction;

import org.alfresco.extension.folderquota.behaviour.FolderQuotaUpdateQueue;
import org.alfresco.model.ContentModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.ResultSetRow;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class FolderUsageCalculator {

	private final String query = "ASPECT:\"fq:quota\"";
	
	private static Log logger = LogFactory.getLog(FolderUsageCalculator.class);
	
	private ServiceRegistry serviceRegistry;
	private FolderQuotaUpdateQueue queue;
	
	public void setServiceRegistry(ServiceRegistry serviceRegistry)
	{
		this.serviceRegistry = serviceRegistry;
	}
	
	public void recalculate()
	{
		
		/*
		 * fq:quota aspect is used to identify folders that have a quota applied
		 * fq:currentSize is the current size
		 * fq:quotaSize
		 */
		SearchService search = serviceRegistry.getSearchService();
		NodeService nodeService = serviceRegistry.getNodeService();
		SearchParameters params = new SearchParameters();
		params.setLanguage(SearchService.LANGUAGE_LUCENE);
		params.setQuery(query);
		
		ResultSet rs = search.query(params);
		//for each folder, calculate the usage
		Iterator<ResultSetRow> it = rs.iterator();
		while(it.hasNext())
		{
			ResultSetRow row = it.next();
			long size = calculateFolderSize(row.getNodeRef());
			nodeService.setProperty(row.getNodeRef(), FolderQuotaConstants.PROP_FQ_SIZE_CURRENT, size);
		}
	}
	
	/**
	 * Calculate the size of all of the children of the provided NodeRef.  This is probably
	 * not the fastest way to do this, a search-based approach might be faster.  Will need
	 * additional performance tuning here.
	 * @param nodeRef
	 * @return
	 */
	public Long calculateFolderSize(NodeRef nodeRef)
	{
		Long size = 0L;
		FileFolderService fileFolderService = serviceRegistry.getFileFolderService();
		List<FileInfo> children = fileFolderService.list(nodeRef);
		Iterator<FileInfo> it = children.iterator();
		while(it.hasNext())
		{
			FileInfo fi = it.next();
			
			QName nodeType = serviceRegistry.getNodeService().getType(fi.getNodeRef());
			if(nodeType.isMatch(QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "folder")))
			{
				size = size + calculateFolderSize(fi.getNodeRef());
			}
			else
			{
				ContentData contentRef = (ContentData)serviceRegistry.getNodeService().getProperty(fi.getNodeRef(), ContentModel.PROP_CONTENT);
				size = size + contentRef.getSize();
			}
		}
		
		return size;
	}
	
    /**
     * Searches up the parent-child chain, looking for a folder with the quota aspect
     * applied.  If one is found, it is returned.  If one is not found, return null
     * @param nodeRef
     * @return
     */
    public NodeRef getParentFolderWithQuota(NodeRef nodeRef)
    {
    	
    	NodeService nodeService = serviceRegistry.getNodeService();
    	
    	if (nodeRef == null || !nodeService.exists(nodeRef)) {
    		logger.debug("[FolderQuota] - Found Null or non-existent nodeRef, returning null");
    		return null;
    	}
    	
    	// Do I have the Aspect?
	    if (nodeService.hasAspect(nodeRef, FolderQuotaConstants.ASPECT_FQ_QUOTA)) {
	    	if (logger.isDebugEnabled()) {
	    		logger.debug("[FolderQuota] - Returning folder with aspect " + nodeRef);
	    	}
	    	return nodeRef;
	    }
    	
    	// Am I the top of the Node Tree?
    	ChildAssociationRef parentFolderRef = nodeService.getPrimaryParent(nodeRef);
    	if (parentFolderRef == null) {
    		logger.debug("[FolderQuota] - Returning Parent Null");
    		return null;
    	}
    	
    	logger.debug("[FolderQuota] - Looking at the parent");
    	return getParentFolderWithQuota(parentFolderRef.getParentRef());
    }
    
	/**
	 * Gets the size change for a node, whether a folder or file.  Value is always returned as
	 * the number of bytes, it is up to the caller to determine whether this should
	 * be an increment or decrement to the quota folder usage.
	 * @param changed
	 * @return
	 */
	public Long getChangeSize(NodeRef changed)
	{
		logger.debug("[FolderQuota] - getChangeSize");
		Long change = 0L;
		
		//if this node is in the archive store, return 0 immediately
		if(changed.getStoreRef().getProtocol().equalsIgnoreCase(StoreRef.PROTOCOL_ARCHIVE)) return 0L;
		
		//if this is a folder delete, go through and tally up the size of the
		//children.  By the time the children are being deleted, the parent is gone
		QName nodeType = serviceRegistry.getNodeService().getType(changed);
		//TODO - also need to check for subtypes of folder
		if(nodeType.isMatch(QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "folder")))
		{
			change = calculateFolderSize(changed);
		}
		else
		{
    		if(serviceRegistry.getNodeService().exists(changed))
    		{
	    		ContentData contentRef = (ContentData)serviceRegistry.getNodeService().getProperty(changed, ContentModel.PROP_CONTENT);
	    		//only calculate the change if the content actually exists.
	    		//on thumbnail generation, contentRef can be null?
	    		if(contentRef != null)
	    		{
	    			change = contentRef.getSize();
	    		}
    		}
    		else
    		{
    			logger.warn("A node was deleted from a quota folder and was not available for size calculations, folder usage reporting may be inaccurate");
    		}
		}
		
		return change;
	}
	
	//on an incremental update, process all of the events in the update queue
	public void incremental()
	{
		//queue.processAllEvents();
	}
	
    public void setFolderQuotaUpdateQueue(FolderQuotaUpdateQueue queue)
    {
    	this.queue = queue;
    }
}
