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
 * Content policy behaviours to handle the calculation and maintenance of folder quotas
 * 
 * @author Rich McKnight
 * @author Nathan McMminn
 * @author Maurizio Pillitu
 */
package org.alfresco.extension.folderquota.behaviour;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

import org.alfresco.extension.folderquota.FolderQuotaConstants;
import org.alfresco.extension.folderquota.FolderUsageCalculator;
import org.alfresco.extension.folderquota.SizeChange;
import org.alfresco.repo.content.ContentServicePolicies;
import org.alfresco.repo.node.NodeServicePolicies;
import org.alfresco.repo.policy.Behaviour;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.repo.transaction.TransactionListener;
import org.alfresco.repo.transaction.TransactionListenerAdapter;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.InvalidNodeRefException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.log4j.Logger;

public class FolderQuotaBehaviour implements ContentServicePolicies.OnContentPropertyUpdatePolicy,
	NodeServicePolicies.BeforeDeleteNodePolicy, NodeServicePolicies.OnMoveNodePolicy, 
	NodeServicePolicies.OnAddAspectPolicy
{

    private static Logger logger = Logger.getLogger(FolderQuotaBehaviour.class.getName());
    private ServiceRegistry serviceRegistry;
    private PolicyComponent policyComponent;
    private Behaviour onContentPropertyUpdate;
    private Behaviour beforeDeleteNode;
    private Behaviour onMoveNode;
    private Behaviour onAddAspect;
    private FolderQuotaUpdateQueue queue;
    private FolderUsageCalculator usage;
    private boolean updateOnAddAspect = true;
    private boolean useJobQueue = false;
    
    private static final String KEY_FOLDER_SIZE_CHANGE = FolderQuotaBehaviour.class.getName() + ".sizeUpdate";
    private ThreadPoolExecutor threadPoolExecutor;
    private TransactionListener transactionListener;
    
    /**
     * Initialize this behaviour component, binding class behaviour.
     */
    public void init() {
    	
        this.onContentPropertyUpdate = new JavaBehaviour(this, "onContentPropertyUpdate", Behaviour.NotificationFrequency.TRANSACTION_COMMIT);
        this.beforeDeleteNode = new JavaBehaviour(this, "beforeDeleteNode", Behaviour.NotificationFrequency.FIRST_EVENT);
        this.onMoveNode = new JavaBehaviour(this, "onMoveNode", Behaviour.NotificationFrequency.TRANSACTION_COMMIT);
        this.onAddAspect = new JavaBehaviour(this, "onAddAspect", Behaviour.NotificationFrequency.TRANSACTION_COMMIT);
        
        this.policyComponent.bindClassBehaviour(
                ContentServicePolicies.OnContentPropertyUpdatePolicy.QNAME,
                QName.createQName(
                        NamespaceService.CONTENT_MODEL_1_0_URI,
                        "content"),
                this.onContentPropertyUpdate);
        
        this.policyComponent.bindClassBehaviour(
                NodeServicePolicies.BeforeDeleteNodePolicy.QNAME,
                QName.createQName(
                        NamespaceService.CONTENT_MODEL_1_0_URI,
                        "cmobject"),
                this.beforeDeleteNode);
        
        this.policyComponent.bindClassBehaviour(
                NodeServicePolicies.OnMoveNodePolicy.QNAME,
                QName.createQName(
                        NamespaceService.CONTENT_MODEL_1_0_URI,
                        "cmobject"),
                this.onMoveNode);
        
        this.policyComponent.bindClassBehaviour(
                NodeServicePolicies.OnAddAspectPolicy.QNAME,
                FolderQuotaConstants.ASPECT_FQ_QUOTA,
                this.onAddAspect);
        
        //set up transaction listener to run code after transaction
        this.transactionListener = new FolderSizeTransactionListener();
        
        logger.info("[FolderQuota] - Bound FolderQuotaBehaviour");

    }

    /**
     * ContentPropertyUpdate fails on actual updates.  When an update (upload new version)
     * comes through, the parent node quota check fails.
     * 
     */
    public void onContentPropertyUpdate(NodeRef nodeRef, QName propertyQName, ContentData beforeValue, ContentData afterValue) {

        logger.debug("[FolderQuota] - onContentPropertyUpdate");
    	long change = 0;
    	
    	//beforeValue can be null on new item creation
    	if(beforeValue == null) change = afterValue.getSize();
    	else change = afterValue.getSize() - beforeValue.getSize();
    	
        NodeRef quotaParent = usage.getParentFolderWithQuota(nodeRef);
        
        if(change > 0) {
	        if(quotaParent != null)
	        {
	        	NodeService nodeService = serviceRegistry.getNodeService();
	        	Long quotaSize = (Long) nodeService.getProperty(quotaParent, FolderQuotaConstants.PROP_FQ_SIZE_QUOTA);
	        	Long currentSize = (Long) nodeService.getProperty(quotaParent, FolderQuotaConstants.PROP_FQ_SIZE_CURRENT);
	        	//quotaSize can be null if the aspect has been added but the quota has not yet been set
	        	if(quotaSize != null) {
		        	if(currentSize + change > quotaSize)
		        	{
			        	//if the change size will push the folder over quota, roll back the current transaction
			        	UserTransaction tx = serviceRegistry.getTransactionService().getUserTransaction();
			        	try
			        	{
			        		tx.rollback();
			        	}
			        	catch(SystemException ex) 
			        	{
			        		logger.warn(String.format("[FolderQuota] - An upload to folder %s failed due to quota", quotaParent));
			        	}
		        	}
		        	else
		        	{
		        		//queue.enqueueEvent(quotaParent, change);
		        		updateSize(quotaParent, change);
		        		logger.debug(String.format("[FolderQuota] - Added nodeRef %s to the queue; size change %s", nodeRef, change));
		        	}
	        	}
	        	else
	        	{
	        		logger.warn(String.format("[FolderQuota] - Folder %s has the quota aspect added but no quota set", quotaParent));
	        	}
	        }
        }
    }

    /**
     * Handles updating the various folder sizes when a node is moved.  Old folder gets
     * its size decremented, new one gets incremented (only if quotas are applied).
     */
	public void onMoveNode(ChildAssociationRef before, ChildAssociationRef after) {
        logger.debug("[FolderQuota] - onMoveNode");
        NodeRef quotaParentBefore = usage.getParentFolderWithQuota(before.getParentRef());
        NodeRef quotaParentAfter = usage.getParentFolderWithQuota(after.getParentRef());
        long change = 0L;
        
        //only calculate the change size once, if either the before or after parent have a quota.
        if(quotaParentBefore != null || quotaParentAfter != null)
        {
        	change = usage.getChangeSize(before.getChildRef());
        }
        
    	if(quotaParentBefore != null)
    	{
    		//queue.enqueueEvent(quotaParentBefore, change * -1);
    		updateSize(quotaParentBefore, change * -1);
            logger.debug(String.format("[FolderQuota] - Node %s moved from a quota folder, added to the queue; size %s", quotaParentBefore, change * -1));
    	}
    	if(quotaParentAfter != null)
    	{
    		//queue.enqueueEvent(quotaParentAfter, change);
    		updateSize(quotaParentAfter, change);
    		logger.debug(String.format("[FolderQuota] - Node %s moved to a quota folder, added to the queue; size %s", quotaParentBefore, change));
    	}
	}

	/**
	 * Called before the node is deleted, decrements the parent quota folder size
	 */
	public void beforeDeleteNode(NodeRef deleted) {
		
        logger.debug("[FolderQuota] - beforeDeleteNode");
        
        NodeRef quotaParent = usage.getParentFolderWithQuota(deleted);
        
    	if(quotaParent != null)
    	{
    		Long size = usage.getChangeSize(deleted);
    		updateSize(quotaParent, size * -1);
			//queue.enqueueEvent(quotaParent, size * -1);
    	}	
	}
	
	/**
	 * When the folder quota aspect is added, should we go ahead and calculate current usage?
	 * I think so, but this could be an expensive operation.
	 */
	public void onAddAspect(NodeRef nodeRef, QName aspectTypeQName) 
	{
		logger.debug("[FolderQuota] - onAddAspect");
		//check the aspect and calculate the usage if configured to do so
		if (serviceRegistry.getNodeService().hasAspect(nodeRef, FolderQuotaConstants.ASPECT_FQ_QUOTA) && updateOnAddAspect) 
		{
			Long size = usage.calculateFolderSize(nodeRef);
			updateSize(nodeRef, size);
			//queue.enqueueEvent(nodeRef, size);
		}
	}

	/**
	 * 
	 * @param quotaFolder
	 * @param sizeChange
	 */
	private void updateSize(NodeRef quotaFolder, Long sizeChange)
	{
		logger.debug("[FolderQuota] - updateSize");
		AlfrescoTransactionSupport.bindListener(transactionListener);
        Set<SizeChange> sizeChanges = (Set<SizeChange>) AlfrescoTransactionSupport.getResource(KEY_FOLDER_SIZE_CHANGE);
        if (sizeChanges == null)
        {
        	sizeChanges = new HashSet<SizeChange>(10);
            AlfrescoTransactionSupport.bindResource(KEY_FOLDER_SIZE_CHANGE, sizeChanges);
        }
        SizeChange change = new SizeChange(quotaFolder, sizeChange);
        if(useJobQueue)
        {
        	if(queue != null)
        	{
        		queue.enqueueEvent(change);
        	}
        	else
        	{
        		logger.warn("Job queue is enabled in Spring config, but queue object is null");
        	}
        }
        else 
        {
        	sizeChanges.add(new SizeChange(quotaFolder, sizeChange));
        }
        
	}
	
	/**
	 * Sets the update queue.  This queue receives notifications that a folder's size has
	 * changed and by what amount.  The events that are pushed onto the queue are then 
	 * processed by a scheduled job that incrementally updates the folder size
	 * 
	 * @param queue
	 */
    public void setFolderQuotaUpdateQueue(FolderQuotaUpdateQueue queue)
    {
    	this.queue = queue;
    }

    public void setServiceRegistry(ServiceRegistry serviceRegistry)
    {
    	this.serviceRegistry = serviceRegistry;
    }

	public void setPolicyComponent(PolicyComponent policyComponent) 
	{
		this.policyComponent = policyComponent;
	}
	
	/**
	 * The folder usage calculator is used to calculate the initial size of a folder when
	 * the "quota" aspect is added.
	 * 
	 * @param usage
	 */
	public void setFolderUsageCalculator(FolderUsageCalculator usage) 
	{
		this.usage = usage;
	}
	
	/**
	 * Should the folder usage be automatically updated when the quota aspect
	 * is added?
	 * 
	 * @param updateOnAddAspect
	 */
	public void setUpdateUsageOnAddAspect(boolean updateOnAddAspect)
	{
		this.updateOnAddAspect = updateOnAddAspect;
	}

	/**
	 * Should the job queue be used (along with a scheduled job), or should the 
	 * transaction listener and thread be used instead?
	 * 
	 * @param useJobQueue
	 */
	public void setUseJobQueue(boolean useJobQueue)
	{
		this.useJobQueue = useJobQueue;
	}
	
	/**
	 * Sets the thread pool executor, this is built by 
	 * org.alfresco.util.ThreadPoolExecutorFactoryBean, see Spring config for details
	 * 
	 * @param threadPoolExecutor
	 */
	public void setThreadPoolExecutor(ThreadPoolExecutor threadPoolExecutor)
	{
		this.threadPoolExecutor = threadPoolExecutor;
	}
	
	/*
	 * Updating the folder size inside the transaction causes multiple uploads to fail.  Shamelessly
	 * borrowing the approach used in this JIRA attachment:
	 * 
	 * https://issues.alfresco.com/jira/secure/attachmentzip/unzip/66705/32483%5B39%5D/CustomAspect/source/org/alfresco/sample/ContentHitsAspect.java
	 */
	
	/**
	 *Transaction listener, fires off the new thread after transaction commit.
	 */
    private class FolderSizeTransactionListener extends TransactionListenerAdapter
    {
        @Override
        public void afterCommit()
        {
            @SuppressWarnings("unchecked")
            Set<SizeChange> sizeChanges = (Set<SizeChange>) AlfrescoTransactionSupport.getResource(KEY_FOLDER_SIZE_CHANGE);
            if (sizeChanges != null)
            {
                for (SizeChange change : sizeChanges)
                {
                    Runnable runnable = new FolderSizeUpdater(change.nodeRef, change.sizeChange);
                    threadPoolExecutor.execute(runnable);
                }
            }
        }
    }
    
    /**
     * Updates the folder's size, runs as system user
     */
    private class FolderSizeUpdater implements Runnable
    {
        private NodeRef quotaFolder;
        private Long sizeChange;
        private FolderSizeUpdater(NodeRef quotaFolder, Long sizeChange)
        {
            this.quotaFolder = quotaFolder;
            this.sizeChange = sizeChange;
        }
        /**
         * Increments the read count on the node
         */
        public void run()
        {
        	AuthenticationUtil.runAs(new RunAsWork<String>()
        	{
        		public String doWork() throws Exception
        		{
        			RetryingTransactionCallback<Long> callback = new RetryingTransactionCallback<Long>()
        			{
        				public Long execute() throws Throwable
        				{
        					Long currentSize = (Long) serviceRegistry.getNodeService().getProperty(quotaFolder, FolderQuotaConstants.PROP_FQ_SIZE_CURRENT);
        					if (currentSize == null)  {
        						currentSize=0L;
        					}
        					Long newSize = currentSize + sizeChange;
        					serviceRegistry.getNodeService().setProperty(quotaFolder, FolderQuotaConstants.PROP_FQ_SIZE_CURRENT, newSize);
        					logger.debug(String.format("Property %s set to %s",FolderQuotaConstants.PROP_FQ_SIZE_CURRENT, newSize));
        					return newSize;
        				}
        			};
        			try
        			{
        				RetryingTransactionHelper txnHelper = serviceRegistry.getTransactionService().getRetryingTransactionHelper();
        				Long newSize = txnHelper.doInTransaction(callback, false, true);

        				if (logger.isDebugEnabled())
        				{
        					logger.debug(
        							"Incremented folder size on quota folder node: \n" +
        									"   Node:      " + quotaFolder + "\n" +
        									"   New Size: " + newSize);
        				}
        			}
        			catch (InvalidNodeRefException e)
        			{
        				if (logger.isDebugEnabled())
        				{
        					logger.debug("Unable to update folder size on quota folder node: " + quotaFolder);
        				}
        			}
        			catch (Throwable e)
        			{
        				if (logger.isDebugEnabled())
        				{
        					logger.debug(e);
        				}
        				logger.error("Failed to update folder size on quota folder node: " + quotaFolder);
        			}
        			return "";
        		}
        	}, AuthenticationUtil.getSystemUserName());
        }
    }
}
