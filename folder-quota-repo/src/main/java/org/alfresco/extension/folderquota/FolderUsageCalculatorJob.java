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
 * Scheduled job to recalcuate full folder sizes and perform incremental updates by processing
 * the update queue.
 * 
 * @author Rich McKnight
 * @author Nathan McMminn
 * @author Maurizio Pillitu
 */
package org.alfresco.extension.folderquota;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.apache.log4j.Logger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * this class is vestigial code from the experiments with a queue + job to process
 * the folder quota size updates.  Not removing it because we may have to go back
 * to that at some point.  Going to continue to experiment and see what scales best.
 */
public class FolderUsageCalculatorJob implements Job {

    private static Logger logger = Logger.getLogger(FolderUsageCalculatorJob.class.getName());

	public void execute(JobExecutionContext context) throws JobExecutionException
	{

        logger.info("[FolderQuota] Executing FolderUsageJob");

		JobDataMap jobData = context.getJobDetail().getJobDataMap();
		
		Object jobObj = jobData.get("folderUsageCalculator");
		//two modes are supported for this job, recalculate and incremental
		Object modeObj = jobData.get("mode");
		
		//if the mode is null, assume full recalculate
		if(modeObj == null)
		{
			modeObj = FolderQuotaConstants.QUOTA_JOB_MODE_RECALCULATE;
		}
		
		if (jobObj == null || !(jobObj instanceof FolderUsageCalculator))
		{
			throw new AlfrescoRuntimeException(
					"FolderUsage object must be valid reference");
		}
		final FolderUsageCalculator folderUsage = (FolderUsageCalculator) jobObj;
		final String mode = modeObj.toString();
		
		AuthenticationUtil.runAs(new AuthenticationUtil.RunAsWork<Object>()
		{

			public Object doWork() throws Exception
			{
				if(mode.equalsIgnoreCase(FolderQuotaConstants.QUOTA_JOB_MODE_RECALCULATE))
				{
					folderUsage.recalculate();
				}
				else if (mode.equalsIgnoreCase(FolderQuotaConstants.QUOTA_JOB_MODE_INCREMENTAL))
				{
					folderUsage.incremental();
				}
				return null;
			}
		}, AuthenticationUtil.getAdminUserName());
	}
}
