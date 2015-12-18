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
 * Constants for folder quota module
 * 
 * @author Rich McKnight
 * @author Nathan McMminn
 * @author Maurizio Pillitu
 */

package org.alfresco.extension.folderquota;

import org.alfresco.service.namespace.QName;

public interface FolderQuotaConstants {
    // Content Model Related
    public static final String FOLDER_QUOTA_MODEL_PREFIX = "fq";
    public static final String FOLDER_QUOTA_MODEL_1_0_URI = "http://www.alfresco.org/model/folder-quota/1.0";

    public static final QName ASPECT_FQ_QUOTA =          QName.createQName(FOLDER_QUOTA_MODEL_1_0_URI, "quota");
    
    public static final QName PROP_FQ_SIZE_QUOTA =       QName.createQName(FOLDER_QUOTA_MODEL_1_0_URI, "sizeQuota");
    public static final QName PROP_FQ_SIZE_CURRENT =     QName.createQName(FOLDER_QUOTA_MODEL_1_0_URI, "sizeCurrent");

    public static final String QUOTA_JOB_MODE_RECALCULATE = "recalcultate";
    public static final String QUOTA_JOB_MODE_INCREMENTAL = "incremental";

}
